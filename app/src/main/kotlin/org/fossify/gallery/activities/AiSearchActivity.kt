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
    private lateinit var recycler: RecyclerView
    private lateinit var modeButton: Button
    private lateinit var adapter: AiResultAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var searchThread: Thread? = null

    private var timeFilter = 0
    private var listMode = false

    @Volatile
    private var indexedCount = 0

    @Volatile
    private var mediaTotal = 0

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
        if (!prefs.hasKey) {
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

    private fun withAlpha(color: Int, factor: Float): Int {
        return Color.argb(Math.round(255 * factor), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun accent(): Int = if (prefs.accentColor != 0) prefs.accentColor else getProperPrimaryColor()

    private fun buildUi(): View {
        val textColor = getProperTextColor()
        val background = getProperBackgroundColor()
        val surface = withAlpha(textColor, 0.07f)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(background)
        root.setPadding(dp(14), dp(14), dp(14), 0)

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
        root.addView(historyRow)

        filterRow = LinearLayout(this)
        filterRow.orientation = LinearLayout.HORIZONTAL
        root.addView(filterRow)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val drawable = GradientDrawable()
        drawable.cornerRadius = dp(14).toFloat()
        drawable.setColor(surface)
        card.background = drawable
        card.setPadding(dp(14), dp(10), dp(14), dp(10))
        val cardParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardParams.topMargin = dp(8)
        root.addView(card, cardParams)

        statsText = TextView(this)
        statsText.textSize = 14f
        statsText.setTextColor(textColor)
        card.addView(statsText)

        statusText = TextView(this)
        statusText.textSize = 12.5f
        statusText.setTextColor(withAlpha(textColor, 0.7f))
        statusText.setPadding(0, dp(5), 0, 0)
        card.addView(statusText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        val progressParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5))
        progressParams.topMargin = dp(6)
        card.addView(progressBar, progressParams)

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        val actionParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actionParams.topMargin = dp(6)
        root.addView(actionRow, actionParams)
        actionRow.addView(makeButton("建立索引", textColor) { startIndexing() })
        actionRow.addView(makeButton("停止", textColor) { AiIndexer.stop() })
        actionRow.addView(makeButton("设置", textColor) { showSettingsDialog() })
        actionRow.addView(makeButton("悬浮球", textColor) { toggleFloatingBall() })

        val modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        root.addView(modeRow)
        modeButton = makeButton("列表模式", textColor) { toggleMode() }
        modeRow.addView(modeButton)

        recycler = RecyclerView(this)
        adapter = AiResultAdapter(this, textColor, { openImage(it) }, { toggleSelection(it) })
        setupLayoutManager()
        recycler.adapter = adapter
        val recyclerParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        recyclerParams.topMargin = dp(6)
        root.addView(recycler, recyclerParams)

        selectionBar = LinearLayout(this)
        selectionBar.orientation = LinearLayout.HORIZONTAL
        selectionBar.gravity = Gravity.CENTER_VERTICAL
        selectionBar.visibility = View.GONE
        selectionBar.setPadding(0, dp(6), 0, dp(10))
        selectionText = TextView(this)
        selectionText.textSize = 13f
        selectionText.setTextColor(textColor)
        selectionBar.addView(selectionText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(makeButton("分享", textColor) { shareSelected() })
        selectionBar.addView(makeButton("取消", textColor) {
            adapter.clearSelection()
            selectionBar.visibility = View.GONE
        })
        root.addView(selectionBar)

        renderHistory()
        renderFilters()
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
        val history = prefs.searchHistory
        if (history.isEmpty()) {
            return
        }
        historyRow.addView(smallLabel("最近：", withAlpha(getProperTextColor(), 0.55f)))
        for (query in history.take(4)) {
            val chip = smallLabel(query, getProperTextColor())
            chip.setOnClickListener {
                searchField.setText(query)
                runSearch()
            }
            historyRow.addView(chip)
        }
    }

    private fun renderFilters() {
        filterRow.removeAllViews()
        val labels = arrayOf("不限时间", "近一年", "近一月")
        for (index in labels.indices) {
            val selected = timeFilter == index
            val chip = smallLabel(labels[index], if (selected) accent() else withAlpha(getProperTextColor(), 0.55f))
            chip.setOnClickListener {
                timeFilter = index
                renderFilters()
                val query = searchField.text.toString().trim()
                if (query.isNotEmpty()) {
                    runSearch()
                }
            }
            filterRow.addView(chip)
        }
    }

    private fun smallLabel(text: String, color: Int): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = 12.5f
        view.setTextColor(color)
        view.setPadding(dp(8), dp(6), dp(8), dp(6))
        return view
    }

    private fun setupLayoutManager() {
        val spanCount = if (listMode) 1 else 3
        val manager = GridLayoutManager(this, spanCount)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.isHeader(position)) spanCount else 1
            }
        }
        recycler.layoutManager = manager
    }

    private fun toggleMode() {
        listMode = !listMode
        adapter.listMode = listMode
        modeButton.text = if (listMode) "网格模式" else "列表模式"
        setupLayoutManager()
    }

    private fun toggleSelection(item: AiIndex) {
        adapter.toggleSelection(item)
        val count = adapter.selectedItems().size
        if (count > 0) {
            selectionBar.visibility = View.VISIBLE
            selectionText.text = "已选 " + count + " 张"
        } else {
            selectionBar.visibility = View.GONE
        }
    }

    private fun shareSelected() {
        val items = adapter.selectedItems()
        if (items.isEmpty()) {
            return
        }
        val uris = ArrayList<Uri>()
        for (item in items) {
            uris.add(Uri.parse(item.uri))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.type = "image/*"
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "分享 " + uris.size + " 张照片"))
    }

    private fun updateStatus() {
        progressBar.visibility = if (AiIndexState.running) View.VISIBLE else View.GONE
        if (AiIndexState.running && AiIndexState.total > 0) {
            progressBar.max = AiIndexState.total
            progressBar.progress = AiIndexState.indexed
        }

        statsText.text = "已索引 " + indexedCount + " / 媒体库 " + mediaTotal + " 张　·　估算 ¥" +
            String.format(Locale.CHINA, "%.3f", indexedCount * COST_PER_PHOTO)

        val builder = StringBuilder()
        if (AiIndexState.running) {
            builder.append("进度 ").append(AiIndexState.indexed).append("/").append(AiIndexState.total)
            builder.append("　成功 ").append(AiIndexState.successCount)
            builder.append("　失败 ").append(AiIndexState.failCount)
            if (AiIndexState.lastMessage.isNotEmpty()) {
                builder.append("\n正在处理：").append(AiIndexState.lastMessage)
            }
        } else if (AiIndexState.indexed > 0) {
            builder.append("上次任务：成功 ").append(AiIndexState.successCount).append("，失败 ").append(AiIndexState.failCount)
        } else {
            builder.append("先点「建立索引」让 AI 认识你的相册，再输入一句话搜索。")
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
                    arrayOf(MediaStore.Images.Media._ID), null, null, null
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
        if (!prefs.hasKey) {
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
                var ranked = AiMath.rank(queryVector, query, all)
                if (filter > 0) {
                    ranked = ranked.filter { matchesTimeFilter(it.first.path, filter) }
                }
                indexedCount = all.size
                val rows = buildRows(ranked)
                handler.post {
                    adapter.submitList(rows)
                    selectionBar.visibility = View.GONE
                    val elapsed = System.currentTimeMillis() - startedAt
                    statusText.text = "命中 " + ranked.size + " 张 · 库内 " + all.size + " 张 · " + elapsed +
                        " 毫秒" + if (queryVector.isEmpty()) " · 已降级为关键词" else ""
                }
            } catch (e: Exception) {
                handler.post { statusText.text = "搜索失败：" + (e.message ?: e.javaClass.simpleName) }
            }
        }
        searchThread = thread
        thread.start()
    }

    private fun matchesTimeFilter(path: String, filter: Int): Boolean {
        val modified = try {
            File(path).lastModified()
        } catch (e: Exception) {
            0L
        }
        if (modified <= 0L) {
            return true
        }
        val now = System.currentTimeMillis()
        val limit = when (filter) {
            1 -> 365L * 24 * 60 * 60 * 1000
            2 -> 30L * 24 * 60 * 60 * 1000
            else -> return true
        }
        return now - modified <= limit
    }

    private fun buildRows(ranked: List<Pair<AiIndex, Float>>): List<AiResultAdapter.Row> {
        val rows = ArrayList<AiResultAdapter.Row>()
        val high = ranked.filter { it.second >= 0.9f }
        val medium = ranked.filter { it.second in 0.7f..0.8999f }
        val rest = ranked.filter { it.second < 0.7f }
        if (high.isNotEmpty()) {
            rows.add(AiResultAdapter.Row.Header("高相似（90% 以上）· " + high.size + " 张"))
            high.forEach { rows.add(AiResultAdapter.Row.Item(it.first, it.second)) }
        }
        if (medium.isNotEmpty()) {
            rows.add(AiResultAdapter.Row.Header("较相似（70~90%）· " + medium.size + " 张"))
            medium.forEach { rows.add(AiResultAdapter.Row.Item(it.first, it.second)) }
        }
        if (rest.isNotEmpty()) {
            rows.add(AiResultAdapter.Row.Header("其他 · " + rest.size + " 张"))
            rest.forEach { rows.add(AiResultAdapter.Row.Item(it.first, it.second)) }
        }
        return rows
    }

    private fun openImage(item: AiIndex) {
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, item.path)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }

    private fun startIndexing() {
        if (!prefs.hasKey) {
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
            .setMessage("按拍摄时间从新到旧取前若干张，已索引的自动跳过。约 ¥0.0019/张。")
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

    private fun showSettingsDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(20), dp(8), dp(20), 0)

        var picked = prefs.accentColor
        val presets = intArrayOf(0, 0xFF4C8DFF.toInt(), 0xFF2E7D32.toInt(), 0xFF7B1FA2.toInt(), 0xFFEF6C00.toInt(), 0xFFC2185B.toInt(), 0xFF455A64.toInt())
        val colorRow = LinearLayout(this)
        colorRow.orientation = LinearLayout.HORIZONTAL
        colorRow.gravity = Gravity.CENTER_VERTICAL

        fun renderSwatches() {
            colorRow.removeAllViews()
            val follow = TextView(this)
            follow.text = if (picked == 0) "跟随主题 ✓" else "跟随主题"
            follow.textSize = 12.5f
            follow.setTextColor(if (picked == 0) accent() else withAlpha(getProperTextColor(), 0.6f))
            follow.setPadding(dp(6), dp(6), dp(12), dp(6))
            follow.setOnClickListener {
                picked = 0
                renderSwatches()
            }
            colorRow.addView(follow)
            for (index in 1 until presets.size) {
                val swatch = View(this)
                val shape = GradientDrawable()
                shape.shape = GradientDrawable.OVAL
                shape.setColor(presets[index])
                if (picked == presets[index]) {
                    shape.setStroke(dp(3), getProperTextColor())
                }
                swatch.background = shape
                val params = LinearLayout.LayoutParams(dp(26), dp(26))
                params.marginEnd = dp(8)
                swatch.layoutParams = params
                swatch.setOnClickListener {
                    picked = presets[index]
                    renderSwatches()
                }
                colorRow.addView(swatch)
            }
        }
        renderSwatches()
        container.addView(smallLabel("配色（悬浮球 / 小组件 / 高亮）", withAlpha(getProperTextColor(), 0.6f)))
        container.addView(colorRow)

        container.addView(smallLabel("悬浮球大小", withAlpha(getProperTextColor(), 0.6f)))
        var pickedSize = prefs.ballSize
        val sizeRow = LinearLayout(this)
        sizeRow.orientation = LinearLayout.HORIZONTAL
        val sizePresets = intArrayOf(44, 56, 68)
        val sizeLabels = arrayOf("小", "中", "大")

        fun renderSizes() {
            sizeRow.removeAllViews()
            for (index in sizePresets.indices) {
                val selected = pickedSize == sizePresets[index]
                val chip = smallLabel(sizeLabels[index], if (selected) accent() else withAlpha(getProperTextColor(), 0.6f))
                chip.setOnClickListener {
                    pickedSize = sizePresets[index]
                    renderSizes()
                }
                sizeRow.addView(chip)
            }
        }
        renderSizes()
        container.addView(sizeRow)

        container.addView(smallLabel("悬浮球透明度", withAlpha(getProperTextColor(), 0.6f)))
        var pickedAlpha = prefs.ballAlpha
        val alphaRow = LinearLayout(this)
        alphaRow.orientation = LinearLayout.HORIZONTAL
        val alphaPresets = intArrayOf(255, 216, 178)
        val alphaLabels = arrayOf("不透明", "85%", "70%")

        fun renderAlphas() {
            alphaRow.removeAllViews()
            for (index in alphaPresets.indices) {
                val selected = pickedAlpha == alphaPresets[index]
                val chip = smallLabel(alphaLabels[index], if (selected) accent() else withAlpha(getProperTextColor(), 0.6f))
                chip.setOnClickListener {
                    pickedAlpha = alphaPresets[index]
                    renderAlphas()
                }
                alphaRow.addView(chip)
            }
        }
        renderAlphas()
        container.addView(alphaRow)

        container.addView(smallLabel("小组件文字", withAlpha(getProperTextColor(), 0.6f)))
        val widgetInput = EditText(this)
        widgetInput.setText(prefs.widgetText)
        container.addView(widgetInput)

        container.addView(smallLabel("API Key（一行一个，遇到限流自动换下一个）", withAlpha(getProperTextColor(), 0.6f)))
        val keysInput = EditText(this)
        keysInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        keysInput.minLines = 2
        keysInput.setText(prefs.apiKeys.joinToString("\n"))
        container.addView(keysInput)

        container.addView(smallLabel("视觉模型", withAlpha(getProperTextColor(), 0.6f)))
        val visionInput = EditText(this)
        visionInput.setText(prefs.visionModel)
        container.addView(visionInput)

        container.addView(smallLabel("向量模型", withAlpha(getProperTextColor(), 0.6f)))
        val embedInput = EditText(this)
        embedInput.setText(prefs.embedModel)
        container.addView(embedInput)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                prefs.apiKeys = keysInput.text.toString().split("\n")
                prefs.visionModel = visionInput.text.toString()
                prefs.embedModel = embedInput.text.toString()
                prefs.accentColor = picked
                prefs.ballSize = pickedSize
                prefs.ballAlpha = pickedAlpha
                prefs.widgetText = widgetInput.text.toString()
                if (ballRunning) {
                    stopService(Intent(this, FloatingBallService::class.java))
                    startService(Intent(this, FloatingBallService::class.java))
                }
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
