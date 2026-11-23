package org.fossify.gallery.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.*
import org.fossify.gallery.adapters.AiGridAdapter
import org.fossify.gallery.databases.AiDatabase
import org.fossify.gallery.helpers.AiIndexState
import org.fossify.gallery.helpers.AiIndexer
import org.fossify.gallery.helpers.AiMath
import org.fossify.gallery.helpers.AiPrefs
import org.fossify.gallery.helpers.BailianClient
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.services.FloatingBallService

class AiSearchActivity : SimpleActivity() {

    private lateinit var prefs: AiPrefs
    private lateinit var searchField: EditText
    private lateinit var statsText: TextView
    private lateinit var statusText: TextView
    private lateinit var historyRow: LinearLayout
    private lateinit var historyScroll: HorizontalScrollView
    private lateinit var adapter: AiGridAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var searchThread: Thread? = null

    @Volatile
    private var indexedCount = 0
    @Volatile
    private var lastHitCount = -1

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 900)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AiPrefs(this)
        setContentView(buildUi())
        handler.post(statusTicker)
        refreshCount()
        renderHistory()
        if (prefs.apiKey.isEmpty()) {
            showApiKeyDialog()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val textColor = getProperTextColor()
        val backgroundColor = getProperBackgroundColor()
        val accentColor = getProperPrimaryColor()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(backgroundColor)
        root.setPadding(dp(12), dp(10), dp(12), 0)

        // ---- 统计卡 ----
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(10), dp(14), dp(10))
        val cardBg = GradientDrawable()
        cardBg.cornerRadius = dp(12).toFloat()
        cardBg.setColor(Color.argb(20, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
        card.background = cardBg

        statsText = TextView(this)
        statsText.textSize = 14f
        statsText.setTextColor(textColor)
        card.addView(statsText)

        statusText = TextView(this)
        statusText.textSize = 12f
        statusText.setTextColor(Color.argb(160, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
        card.addView(statusText)
        root.addView(card)

        // ---- 搜索行 ----
        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchRow.setPadding(0, dp(8), 0, 0)

        searchField = EditText(this)
        searchField.hint = "说说照片里有什么"
        searchField.maxLines = 1
        searchField.setSingleLine(true)
        searchField.setTextColor(textColor)
        searchField.setHintTextColor(Color.argb(120, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
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

        // ---- 历史 ----
        historyScroll = HorizontalScrollView(this)
        historyScroll.isHorizontalScrollBarEnabled = false
        historyRow = LinearLayout(this)
        historyRow.orientation = LinearLayout.HORIZONTAL
        historyRow.setPadding(0, dp(6), 0, 0)
        historyScroll.addView(historyRow)
        root.addView(historyScroll)

        // ---- 操作行 ----
        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        val indexButton = Button(this)
        indexButton.text = "索引"
        indexButton.setTextColor(textColor)
        indexButton.setOnClickListener { startIndexing() }
        actionRow.addView(indexButton)

        val stopButton = Button(this)
        stopButton.text = "停止"
        stopButton.setTextColor(textColor)
        stopButton.setOnClickListener { AiIndexer.stop() }
        actionRow.addView(stopButton)

        val ballButton = Button(this)
        ballButton.text = "悬浮球"
        ballButton.setTextColor(textColor)
        ballButton.setOnClickListener { toggleFloatingBall() }
        actionRow.addView(ballButton)

        val moreButton = Button(this)
        moreButton.text = "设置"
        moreButton.setTextColor(textColor)
        moreButton.setOnClickListener { showSettingsDialog() }
        actionRow.addView(moreButton)
        root.addView(actionRow)

        // ---- 结果网格 ----
        val grid = RecyclerView(this)
        adapter = AiGridAdapter(this, textColor) { item -> openPhoto(item.path) }
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter
        val gridParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        gridParams.topMargin = dp(6)
        root.addView(grid, gridParams)

        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openPhoto(path: String) {
        if (path.isEmpty()) {
            return
        }
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }

    private fun updateStatus() {
        val estimate = indexedCount * 0.0009
        statsText.text = "库内 " + indexedCount + " 张　·　估算花费 ≈ " + String.format("%.2f", estimate) + " 元"

        val builder = StringBuilder()
        if (AiIndexState.running) {
            builder.append("索引中 ").append(AiIndexState.indexed).append("/").append(AiIndexState.total)
            builder.append("　成功 ").append(AiIndexState.successCount).append("　失败 ").append(AiIndexState.failCount)
            if (AiIndexState.lastMessage.isNotEmpty()) {
                builder.append("\n正在处理：").append(AiIndexState.lastMessage)
            }
            refreshCount()
        } else if (lastHitCount >= 0) {
            builder.append("命中 ").append(lastHitCount).append(" 张")
        } else {
            builder.append("点「索引」开始建立语义索引")
        }
        if (AiIndexState.lastError.isNotEmpty()) {
            builder.append("\n上次错误：").append(AiIndexState.lastError.take(90))
        }
        statusText.text = builder.toString()
    }

    private fun refreshCount() {
        Thread {
            indexedCount = try {
                AiDatabase.getInstance(applicationContext).aiIndexDao().count()
            } catch (e: Exception) {
                0
            }
        }.start()
    }

    private fun renderHistory() {
        historyRow.removeAllViews()
        val items = prefs.history.split("|").filter { it.isNotEmpty() }.take(8)
        if (items.isEmpty()) {
            historyScroll.visibility = View.GONE
            return
        }
        historyScroll.visibility = View.VISIBLE
        val textColor = getProperTextColor()
        val accentColor = getProperPrimaryColor()
        for (query in items) {
            val chip = TextView(this)
            chip.text = query
            chip.textSize = 12f
            chip.setTextColor(textColor)
            chip.setPadding(dp(11), dp(6), dp(11), dp(6))
            val background = GradientDrawable()
            background.cornerRadius = dp(14).toFloat()
            background.setColor(Color.argb(26, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            chip.background = background
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.marginEnd = dp(6)
            chip.layoutParams = params
            chip.setOnClickListener {
                searchField.setText(query)
                runSearch()
            }
            historyRow.addView(chip)
        }
    }

    private fun rememberQuery(query: String) {
        val existing = prefs.history.split("|").filter { it.isNotEmpty() && it != query }
        val updated = (listOf(query) + existing).take(8).joinToString("|")
        prefs.history = updated
        renderHistory()
    }

    private fun runSearch() {
        val query = searchField.text.toString().trim()
        if (query.isEmpty()) {
            toast("先输入想找的内容")
            return
        }
        if (prefs.apiKey.isEmpty()) {
            showApiKeyDialog()
            return
        }
        hideKeyboard()
        rememberQuery(query)
        statusText.text = "搜索中…"
        val previous = searchThread
        if (previous != null && previous.isAlive) {
            previous.interrupt()
        }
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
                lastHitCount = results.size
                handler.post { adapter.submitList(results) }
            } catch (e: Exception) {
                handler.post { statusText.text = "搜索失败：" + (e.message ?: e.javaClass.simpleName) }
            }
        }
        searchThread = thread
        thread.start()
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
            .setMessage("按拍摄时间从新到旧，已索引的自动跳过。")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val limit = (input.text.toString().toIntOrNull() ?: 100).coerceIn(1, 5000)
                AiIndexer.start(applicationContext, prefs, limit)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettingsDialog() {
        val options = arrayOf("设置 API Key", "调整模型", "清空索引", "悬浮球开关")
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showApiKeyDialog()
                    1 -> showModelDialog()
                    2 -> confirmClearIndex()
                    3 -> toggleFloatingBall()
                }
            }
            .show()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "sk-..."
        input.setText(prefs.apiKey)
        AlertDialog.Builder(this)
            .setTitle("百炼 API Key")
            .setMessage("用于生成照片描述与向量，只保存在本机。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                prefs.apiKey = input.text.toString().trim()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showModelDialog() {
        val input = EditText(this)
        input.setText(prefs.visionModel)
        AlertDialog.Builder(this)
            .setTitle("描述模型")
            .setMessage("默认 qwen3-vl-plus。向量模型固定 text-embedding-v4（1024 维）。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                prefs.visionModel = input.text.toString().trim().ifEmpty { "qwen3-vl-plus" }
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearIndex() {
        AlertDialog.Builder(this)
            .setTitle("清空索引？")
            .setMessage("会删除全部描述与向量，照片本身不受影响。已花的钱不会退。")
            .setPositiveButton("清空") { _, _ ->
                Thread {
                    AiDatabase.getInstance(applicationContext).aiIndexDao().clear()
                    refreshCount()
                    handler.post { adapter.submitList(emptyList()) }
                }.start()
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
            toast("悬浮球已开启")
        }
    }

    companion object {
        @Volatile
        var ballRunning = false
    }
}
