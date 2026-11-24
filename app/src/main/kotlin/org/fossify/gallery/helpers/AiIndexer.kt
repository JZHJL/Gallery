package org.fossify.gallery.helpers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.fossify.gallery.databases.AiDatabase
import org.fossify.gallery.databases.AiIndex
import org.fossify.gallery.databases.AiIndexDao
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
        AiIndexState.reset()
        AiIndexState.running = true
        val appContext = context.applicationContext
        Thread {
            var pool: java.util.concurrent.ExecutorService? = null
            try {
                val dao = AiDatabase.getInstance(appContext).aiIndexDao()
                val targets = collectImages(appContext, limit, dao)
                AiIndexState.total = targets.size
                val client = BailianClient(prefs)
                val done = AtomicInteger(0)
                val latch = CountDownLatch(targets.size)
                pool = Executors.newFixedThreadPool(CONCURRENCY)
                for (target in targets) {
                    pool.execute {
                        try {
                            when {
                                AiIndexState.cancelRequested -> {
                                    AiIndexState.skipCount++
                                }
                                dao.getByUri(target.uri) != null -> {
                                    AiIndexState.skipCount++
                                }
                                else -> {
                                    val jpeg = AiImagePrep.loadJpeg(appContext, Uri.parse(target.uri), MAX_SIDE)
                                    if (jpeg == null) {
                                        AiIndexState.skipCount++
                                    } else {
                                        val description = client.describeImage(jpeg).first
                                        if (description.isEmpty()) {
                                            AiIndexState.failCount++
                                        } else {
                                            val vector = client.embedText(description)
                                            dao.upsert(
                                                AiIndex(
                                                    uri = target.uri,
                                                    path = target.path,
                                                    description = description,
                                                    ocrText = description,
                                                    vector = AiMath.toBytes(vector),
                                                    dim = vector.size,
                                                    model = prefs.visionModel,
                                                    sizeBytes = target.size,
                                                    updatedAt = System.currentTimeMillis()
                                                )
                                            )
                                            AiIndexState.successCount++
                                            AiIndexState.lastMessage = target.path.substringAfterLast('/')
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            AiIndexState.failCount++
                            AiIndexState.lastError = e.message ?: e.javaClass.simpleName
                        } finally {
                            AiIndexState.indexed = done.incrementAndGet()
                            latch.countDown()
                        }
                    }
                }
                latch.await()
            } catch (e: Exception) {
                AiIndexState.lastError = e.message ?: e.javaClass.simpleName
            } finally {
                pool?.shutdown()
                AiIndexState.running = false
            }
        }.start()
    }

    fun stop() {
        AiIndexState.cancelRequested = true
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
