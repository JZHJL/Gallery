package org.fossify.gallery.helpers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.fossify.gallery.databases.AiDatabase
import org.fossify.gallery.databases.AiIndex
import org.fossify.gallery.databases.AiIndexDao
import org.fossify.gallery.databases.AiVector
import org.fossify.gallery.databases.AiVectorDao
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object AiIndexer {

    private const val CONCURRENCY = 4
    private const val MAX_SIDE = 768

    private data class Target(val uri: String, val path: String, val size: Long)

    fun start(context: Context, prefs: AiPrefs, limit: Int) {
        if (AiIndexState.running) {
            return
        }
        val appContext = context.applicationContext
        AiLog.init(appContext)
        AiLog.w("index", "start limit=" + limit + " model=" + prefs.visionModel + "/" + prefs.embedModel)
        AiIndexState.reset()
        AiIndexState.running = true

        Thread {
            var pool: java.util.concurrent.ExecutorService? = null
            try {
                val db = AiDatabase.getInstance(appContext)
                val dao = db.aiIndexDao()
                val vectorDao = db.aiVectorDao()
                val client = BailianClient(prefs)

                // 第一步：只补向量，不重新花钱生成描述
                backfillVectors(dao, vectorDao, client, prefs)

                // 第二步：抓取"还没索引过"的照片
                val targets = collectImages(appContext, limit, dao)
                AiIndexState.total = targets.size
                AiLog.w("index", "collected " + targets.size + " new targets (limit=" + limit + ")")
                if (targets.isEmpty()) {
                    AiLog.w("index", "nothing new to index")
                    return@Thread
                }

                val done = AtomicInteger(0)
                val latch = CountDownLatch(targets.size)
                pool = Executors.newFixedThreadPool(CONCURRENCY)

                for (target in targets) {
                    pool.execute {
                        try {
                            if (AiIndexState.cancelRequested) {
                                AiIndexState.skipCount++
                            } else {
                                indexOne(appContext, dao, vectorDao, client, prefs, target)
                            }
                        } catch (e: Exception) {
                            AiIndexState.failCount++
                            AiIndexState.lastError = e.message ?: e.javaClass.simpleName
                            AiLog.w("index", "fail " + target.path + " :: " + (e.message ?: e.javaClass.simpleName))
                        } finally {
                            AiIndexState.indexed = done.incrementAndGet()
                            latch.countDown()
                        }
                    }
                }
                latch.await()
            } catch (e: Exception) {
                AiIndexState.lastError = e.message ?: e.javaClass.simpleName
                AiLog.w("index", "aborted :: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                pool?.shutdown()
                AiIndexState.running = false
                AiLog.w(
                    "index",
                    "done success=" + AiIndexState.successCount + " fail=" + AiIndexState.failCount +
                        " skip=" + AiIndexState.skipCount + " costYuan=" + String.format("%.4f", prefs.estimatedCost())
                )
            }
        }.start()
    }

    fun stop() {
        AiIndexState.cancelRequested = true
    }

    private fun indexOne(
        context: Context,
        dao: AiIndexDao,
        vectorDao: AiVectorDao,
        client: BailianClient,
        prefs: AiPrefs,
        target: Target
    ) {
        val jpeg = AiImagePrep.loadJpeg(context, Uri.parse(target.uri), MAX_SIDE)
        if (jpeg == null) {
            AiIndexState.skipCount++
            AiLog.w("index", "skip unreadable " + target.path)
            return
        }

        val described = client.describeImage(jpeg)
        val description = described.first
        val usage = described.second
        prefs.addUsage(usage.inputTokens, usage.outputTokens, 0)

        if (description.isEmpty()) {
            AiIndexState.failCount++
            AiLog.w("index", "fail empty description " + target.path)
            return
        }

        // 描述已经花钱拿到了，无论向量是否成功都先入库，避免下次重复付费
        var vector = FloatArray(0)
        var embeddingTokens = 0
        try {
            val embedded = client.embedTextWithUsage(description)
            vector = embedded.first
            embeddingTokens = embedded.second
            prefs.addUsage(0, 0, embeddingTokens)
        } catch (e: Exception) {
            AiLog.w("index", "embedding failed, keep description " + target.path + " :: " + (e.message ?: ""))
        }

        val now = System.currentTimeMillis()
        dao.upsert(
            AiIndex(
                uri = target.uri,
                path = target.path,
                description = description,
                ocrText = description,
                model = prefs.visionModel,
                sizeBytes = target.size,
                updatedAt = now
            )
        )
        if (vector.isNotEmpty()) {
            vectorDao.upsert(
                AiVector(
                    uri = target.uri,
                    vector = AiMath.toBytes(vector),
                    dim = vector.size,
                    model = prefs.embedModel,
                    updatedAt = now
                )
            )
        }
        AiIndexState.successCount++
        AiIndexState.lastMessage = target.path.substringAfterLast('/')
        AiLog.w(
            "index",
            "ok " + target.path.substringAfterLast('/') + " dim=" + vector.size +
                " in=" + usage.inputTokens + " out=" + usage.outputTokens + " emb=" + embeddingTokens
        )
    }

    private fun backfillVectors(dao: AiIndexDao, client: BailianClient, prefs: AiPrefs) {
        val pending = try {
            dao.getPendingVectors()
        } catch (e: Exception) {
            return
        }
        if (pending.isEmpty()) {
            return
        }
        AiLog.w("index", "backfill vectors for " + pending.size + " rows")
        for (row in pending) {
            if (AiIndexState.cancelRequested) {
                break
            }
            try {
                val embedded = client.embedTextWithUsage(row.description)
                val vector = embedded.first
                prefs.addUsage(0, 0, embedded.second)
                if (vector.isNotEmpty()) {
                    dao.upsert(row.copy(vector = AiMath.toBytes(vector), dim = vector.size, updatedAt = System.currentTimeMillis()))
                }
            } catch (e: Exception) {
                AiLog.w("index", "backfill failed " + row.path + " :: " + (e.message ?: ""))
            }
        }
    }

    private fun collectImages(context: Context, limit: Int, dao: AiIndexDao): List<Target> {
        val result = ArrayList<Target>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE
        )
        context.contentResolver.query(collection, projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idIndex)
                val contentUri = ContentUris.withAppendedId(collection, id).toString()
                if (dao.getByUri(contentUri) != null) {
                    continue
                }
                val path = if (dataIndex >= 0) {
                    cursor.getString(dataIndex) ?: contentUri
                } else {
                    contentUri
                }
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                result.add(Target(contentUri, path, size))
            }
        }
        return result
    }
}
