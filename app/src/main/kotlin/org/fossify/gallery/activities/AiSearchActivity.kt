package org.fossify.gallery.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.*
import org.fossify.gallery.adapters.AiResultAdapter
import org.fossify.gallery.databases.AiDatabase
import org.fossify.gallery.databases.AiIndex
import org.fossify.gallery.helpers.AiIndexState
import org.fossify.gallery.helpers.AiIndexer
import org.fossify.gallery.helpers.AiMath
import org.fossify.gallery.helpers.AiPrefs
import org.fossify.gallery.helpers.BailianClient
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.services.FloatingBallService
import java.util.Locale

class AiSearchActivity : SimpleActivity() {

    private lateinit var prefs: AiPrefs
    private lateinit var searchField: EditText
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: AiResultAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var searchThread: Thread? = null

    @Volatile
    private var indexedCount = 0

    @Volatile
    private var mediaTotal = 0

    @Volatile
    private var lastQueryCost = 0.0

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AiPrefs(this)
        setContentView(buildUi())
        handler.post(statusTicker)
        refreshCounters()
        if (prefs.apiKey.isEmpty()) {
            showApiKeyDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCounters()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun cardBackground(color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.cornerRadius = dp(14).toFloat()
        drawable.setColor(color)
        return drawable
    }

    private fun buildUi(): View {
        val textColor = getProperTextColor()
        val backgroundColor = getProperBackgroundColor()
        val surfaceColor = backgroundColor.adjustAlpha(0.06f)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(backgroundColor)
        root.setPadding(dp(14), dp(14), dp(14), 0)

        // 搜索行
        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL

        searchField = EditText(this)
        searchField.hint = "描述照片内容，如「消防演练」「第23题」"
        searchField.maxLines = 1
        searchField.setSingleLine(true)
        searchField.setTextColor(textColor)
        searchField.setHintTextColor(textColor.adjustAlpha(0.45f))
        searchField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val searchButton = Button(this)
        searchButton.text = "搜索"
        searchButton.setTextColor(textColor)
        searchButton.setOnClickListener { runSearch() }
        searchRow.addView(searchButton)
        root.addView(searchRow)

        // 状态卡片
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = cardBackground(surfaceColor)
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val cardParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardParams.topMargin = dp(8)
        root.addView(card, cardParams)

        statsText = TextView(this)
        statsText.textSize = 14f
        statsText.setTextColor(textColor)
        card.addView(statsText)

        statusText = TextView(this)
        statusText.textSize = 13f
        statusText.setTextColor(textColor.adjustAlpha(0.7f))
        statusText.setPadding(0, dp(6), 0, 0)
        card.addView(statusText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        val progressParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
        progressParams.topMargin = dp(8)
        card.addView(progressBar, progressParams)

        // 操作行
        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        val actionParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actionParams.topMargin = dp(10)
        root.addView(actionRow, actionParams)

        actionRow.addView(makeButton("建立索引", textColor) { startIndexing() })
        actionRow.addView(makeButton("停止", textColor) { AiIndexer.stop() })
        actionRow.addView(makeButton("设置", textColor) { showApiKeyDialog() })
        actionRow.addView(makeButton("悬浮球", textColor) { toggleFloatingBall() })

        // 结果网格
        val recycler = RecyclerView(this)
        adapter = AiResultAdapter(this, textColor) { openImage(it) }
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter
        val recyclerParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        recyclerParams.topMargin = dp(10)
        root.addView(recycler, recyclerParams)

        return root
    }

    private fun makeButton(label: String, textColor: Int, action: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.setTextColor(textColor)
        button.textSize = 13f
        button.setPadding(dp(4), 0, dp(4), 0)
        button.setOnClickListener { action() }
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        button.layoutParams = params
        return button
    }

    private fun updateStatus() {
        progressBar.visibility = if (AiIndexState.running) View.VISIBLE else View.GONE
        if (AiIndexState.running && AiIndexState.total > 0) {
            progressBar.max = AiIndexState.total
            progressBar.progress = AiIndexState.indexed
        }

        val cost = indexedCount * COST_PER_PHOTO
        statsText.text = "已索引 " + indexedCount + " / 媒体库 " + mediaTotal + " 张　·　估算花费 ¥" +
            String.format(Locale.CHINA, "%.3f", cost)

        val builder = StringBuilder()
        if (AiIndexState.running) {
            builder.append("进度 ").append(AiIndexState.indexed).append("/").append(AiIndexState.total)
            builder.append("　成功 ").append(AiIndexState.successCount)
            builder.append("　失败 ").append(AiIndexState.failCount)
            if (AiIndexState.lastMessage.isNotEmpty()) {
                builder.append("\n正在处理：").append(AiIndexState.lastMessage)
            }
        } else if (AiIndexState.indexed > 0) {
            builder.append("上次任务结束：成功 ").append(AiIndexState.successCount)
            builder.append("，失败 ").append(AiIndexState.failCount)
        } else {
            builder.append("输入一句话就能搜照片；先点「建立索引」让 AI 认识你的相册。")
        }
        if (AiIndexState.lastError.isNotEmpty()) {
            builder.append("\n上次错误：").append(AiIndexState.lastError.take(140))
        }
        statusText.text = builder.toString()
    }

    private fun refreshCounters() {
        Thread {
            try {
                indexedCount = AiDatabase.getInstance(applicationContext).aiIndexDao().count()
            } catch (e: Exception) {
                indexedCount = 0
            }
            try {
                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null, null
                )
                mediaTotal = cursor?.count ?: 0
                cursor?.close()
            } catch (e: Exception) {
                mediaTotal = 0
            }
        }.start()
    }

    private fun runSearch() {
        val query = searchField.text.toString().trim()
        if (query.isEmpty()) {
            toast("请先输入要搜索的内容")
            return
        }
        if (prefs.apiKey.isEmpty()) {
            showApiKeyDialog()
            return
        }
        hideKeyboard()
        statusText.text = "搜索中…"
        val previous = searchThread
        if (previous != null && previous.isAlive) {
            previous.interrupt()
        }
        val startedAt = System.currentTimeMillis()
        val thread = Thread {
            try {
                val client = BailianClient(prefs)
                val queryVector = try {
                    client.embedText(query)
                } catch (e: Exception) {
                    FloatArray(0)
                }
                val dao = AiDatabase.getInstance(applicationContext).aiIndexDao()
                val all = dao.getAll()
                val results = AiMath.rank(queryVector, query, all)
                indexedCount = all.size
                handler.post {
                    adapter.submitList(results)
                    val elapsed = System.currentTimeMillis() - startedAt
                    statusText.text = "命中 " + results.size + " 张 · 库内 " + all.size +
                        " 张 · 用时 " + elapsed + " 毫秒" + if (queryVector.isEmpty()) " · 已降级为关键词搜索" else ""
                }
            } catch (e: Exception) {
                handler.post { statusText.text = "搜索失败：" + (e.message ?: e.javaClass.simpleName) }
            }
        }
        searchThread = thread
        thread.start()
    }

    private fun openImage(item: AiIndex) {
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, item.path)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }

    private fun startIndexing() {
        if (prefs.apiKey.isEmpty()) {
            showApiKeyDialog()
            return
        }
        if (AiIndexState.running) {
            toast("索引正在进行中")
            return
        }
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText("100")
        AlertDialog.Builder(this)
            .setTitle("索引多少张")
            .setMessage("按拍摄时间从新到旧取前若干张，已索引过的会自动跳过。约 ¥0.0019/张。")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val limit = (input.text.toString().toIntOrNull() ?: 100).coerceIn(1, 20000)
                AiIndexer.start(applicationContext, prefs, limit)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleFloatingBall() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先允许「显示在其他应用上层」")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            return
        }
        val serviceIntent = Intent(this, FloatingBallService::class.java)
        if (ballRunning) {
            stopService(serviceIntent)
            ballRunning = false
            toast("悬浮球已关闭")
        } else {
            startService(serviceIntent)
            ballRunning = true
            toast("悬浮球已开启，点它就能搜照片")
        }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "sk-..."
        input.setText(prefs.apiKey)
        AlertDialog.Builder(this)
            .setTitle("百炼 API Key")
            .setMessage("用于生成照片描述和向量。只保存在本机，不上传。\n描述模型：" + prefs.visionModel + "\n向量模型：" + prefs.embedModel)
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                prefs.apiKey = input.text.toString().trim()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        @Volatile
        var ballRunning = false

        private const val COST_PER_PHOTO = 0.0019
    }
}
