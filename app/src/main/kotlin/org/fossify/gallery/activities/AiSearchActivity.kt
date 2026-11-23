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
import android.widget.HorizontalScrollView
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
import java.io.File
import java.util.Locale

class AiSearchActivity : SimpleActivity() {

    private lateinit var prefs: AiPrefs
    private lateinit var searchField: EditText
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var historyRow: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionText: TextView
    private lateinit var listToggle: Button
    private lateinit var adapter: AiResultAdapter
    private lateinit var recycler: RecyclerView

    private val handler = Handler(Looper.getMainLooper())
    private var searchThread: Thread? = null

    @Volatile
    private var indexedCount = 0

    @Volatile
    private var mediaTotal = 0

    private var timeFilter = 0

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
        renderHistory()
        renderFilters()
        if (prefs.apiKey.isEmpty()) {
            showSettingsDialog()
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

    private fun withAlpha(color: Int, factor: Float): Int =
        Color.argb((Color.alpha(color) * factor).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun rounded(color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.cornerRadius = dp(14).toFloat()
        drawable.setColor(color)
        return drawable
    }

    private fun chip(label: String, active: Boolean, action: () -> Unit): TextView {
        val view = TextView(this)
        view.text = label
        view.textSize = 12.5f
        view.setPadding(dp(12), dp(6), dp(12), dp(6))
        val background = GradientDrawable()
        background.cornerRadius = dp(16).toFloat()
        background.setColor(if (active) getProperPrimaryColor() else withAlpha(getProperTextColor(), 0.10f))
        view.background = background
        view.setTextColor(if (active) Color.WHITE else getProperTextColor())
        view.setOnClickListener { action() }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.marginEnd = dp(8)
        view.layoutParams = params
        return view
    }

    private fun buildUi(): View {
        val textColor = getProperTextColor()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getProperBackgroundColor())
        root.setPadding(dp(14), dp(14), dp(14), dp(10))

        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL

        searchField = EditText(this)
        searchField.hint = "描述照片内容，如「消防演练」「第23题」"
        searchField.maxLines = 1
        searchField.setSingleLine(true)
        searchField.setTextColor(textColor)
        searchField.setHintTextColor(withAlpha(textColor, 0.45f))
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

        historyRow = LinearLayout(this)
        historyRow.orientation = LinearLayout.HORIZONTAL
        val historyScroll = HorizontalScrollView(this)
        historyScroll.isHorizontalScrollBarEnabled = false
        historyScroll.addView(historyRow)
        root.addView(historyScroll)

        filterRow = LinearLayout(this)
        filterRow.orientation = LinearLayout.HORIZONTAL
        val filterScroll = HorizontalScrollView(this)
        filterScroll.isHorizontalScrollBarEnabled = false
        filterScroll.addView(filterRow)
        val filterParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        filterParams.topMargin = dp(6)
        root.addView(filterScroll, filterParams)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(withAlpha(textColor, 0.06f))
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
        statusText.setTextColor(withAlpha(textColor, 0.7f))
        statusText.setPadding(0, dp(6), 0, 0)
        card.addView(statusText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.visibility = View.GONE
        val progressParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
        progressParams.topMargin = dp(8)
        card.addView(progressBar, progressParams)

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        val actionParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actionParams.topMargin = dp(10)
        root.addView(actionRow, actionParams)
        actionRow.addView(makeButton("建立索引", textColor) { startIndexing() })
        actionRow.addView(makeButton("停止", textColor) { AiIndexer.stop() })
        actionRow.addView(makeButton("设置", textColor) { showSettingsDialog() })
        actionRow.addView(makeButton("悬浮球", textColor) { toggleFloatingBall() })

        listToggle = makeButton("列表", textColor) { toggleListMode() }
        listToggle.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(listToggle)

        selectionBar = LinearLayout(this)
        selectionBar.orientation = LinearLayout.HORIZONTAL
        selectionBar.visibility = View.GONE
        selectionText = TextView(this)
        selectionText.textSize = 13f
        selectionText.setTextColor(textColor)
        selectionBar.addView(selectionText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(makeButton("分享", textColor) { shareSelected() })
        selectionBar.addView(makeButton("取消", textColor) { adapter.clearSelection(); updateSelectionBar() })
        root.addView(selectionBar)

        recycler = RecyclerView(this)
        adapter = AiResultAdapter(this, textColor, { openImage(it) }, { updateSelectionBar() })
        val layoutManager = GridLayoutManager(this, 3)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == AiResultAdapter.TYPE_HEADER) 3 else 1
            }
        }
        recycler.layoutManager = layoutManager
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
        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return button
    }

    private fun renderHistory() {
        historyRow.removeAllViews()
        val history = prefs.history
        if (history.isEmpty()) {
            historyRow.visibility = View.GONE
            return
        }
        historyRow.visibility = View.VISIBLE
        for (query in history) {
            historyRow.addView(chip(query, false) {
                searchField.setText(query)
                runSearch()
            })
        }
    }

    private fun renderFilters() {
        filterRow.removeAllViews()
        val labels = arrayListOf("全部时间", "近一年", "近一月")
        for (index in labels.indices) {
            filterRow.addView(chip(labels[index], timeFilter == index) {
                timeFilter = index
                renderFilters()
                if (searchField.text.toString().isNotEmpty()) {
                    runSearch()
                }
            })
        }
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
            builder.append("上次任务：成功 ").append(AiIndexState.successCount)
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
            showSettingsDialog()
            return
        }
        hideKeyboard()
        prefs.addHistory(query)
        renderHistory()
        statusText.text = "搜索中…"
        val previous = searchThread
        if (previous != null && previous.isAlive) {
            previous.interrupt()
        }
        val startedAt = System.currentTimeMillis()
        val filter = timeFilter
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
                val ranked = AiMath.rank(queryVector, query, all)
                val threshold = when (filter) {
                    1 -> System.currentTimeMillis() - 365L * 24 * 3600 * 1000
                    2 -> System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                    else -> 0L
                }
                val results = if (threshold == 0L) {
                    ranked
                } else {
                    ranked.filter { pair ->
                        val modified = try {
                            File(pair.first.path).lastModified()
                        } catch (e: Exception) {
                            0L
                        }
                        modified == 0L || modified >= threshold
                    }
                }
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

    private fun toggleListMode() {
        val newMode = !listMode
        listMode = newMode
        adapter.setListMode(newMode)
        listToggle.text = if (newMode) "网格" else "列表"
        if (searchField.text.toString().isNotEmpty()) {
            runSearch()
        }
    }

    private var listMode = false

    private fun updateSelectionBar() {
        if (adapter.isSelectionMode()) {
            selectionBar.visibility = View.VISIBLE
            selectionText.text = "已选 " + adapter.selectionSize() + " 张"
        } else {
            selectionBar.visibility = View.GONE
        }
    }

    private fun shareSelected() {
        val uris = ArrayList<Uri>()
        for (uri in adapter.selectedUris()) {
            uris.add(Uri.parse(uri))
        }
        if (uris.isEmpty()) {
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.type = "image/*"
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "分享所选照片"))
        adapter.clearSelection()
        updateSelectionBar()
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
            showSettingsDialog()
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
            .setMessage("按拍摄时间从新到旧取前若干张，已索引过的自动跳过。约 ¥0.0019/张。")
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
            toast("悬浮球已开启，点它就能搜照片，长按有菜单")
        }
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(20), dp(8), dp(20), dp(8))

        val keysInput = EditText(this)
        keysInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        keysInput.hint = "sk-...（一行一个，可填多个轮流用）"
        keysInput.setText(prefs.apiKeys.joinToString("\n"))
        container.addView(TextView(this).apply {
            text = "百炼 API Key"
            textSize = 13f
            setTextColor(getProperTextColor())
        })
        container.addView(keysInput)

        val visionInput = EditText(this)
        visionInput.inputType = InputType.TYPE_CLASS_TEXT
        visionInput.setText(prefs.visionModel)
        container.addView(TextView(this).apply {
            text = "描述模型"
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
            setTextColor(getProperTextColor())
        })
        container.addView(visionInput)

        val embedInput = EditText(this)
        embedInput.inputType = InputType.TYPE_CLASS_TEXT
        embedInput.setText(prefs.embedModel)
        container.addView(TextView(this).apply {
            text = "向量模型"
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
            setTextColor(getProperTextColor())
        })
        container.addView(embedInput)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val keys = keysInput.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                prefs.apiKeys = keys
                prefs.visionModel = visionInput.text.toString()
                prefs.embedModel = embedInput.text.toString()
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
