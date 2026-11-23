package org.fossify.gallery.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.gallery.adapters.AiResultAdapter
import org.fossify.gallery.databases.AiDatabase
import org.fossify.gallery.helpers.AiIndexState
import org.fossify.gallery.helpers.AiIndexer
import org.fossify.gallery.helpers.AiMath
import org.fossify.gallery.helpers.AiPrefs
import org.fossify.gallery.helpers.BailianClient

class AiSearchActivity : SimpleActivity() {

    private lateinit var prefs: AiPrefs
    private lateinit var searchField: EditText
    private lateinit var statusText: TextView
    private lateinit var adapter: AiResultAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var searchThread: Thread? = null

    @Volatile
    private var indexedCount = 0

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AiPrefs(this)
        setContentView(buildUi())
        handler.post(statusTicker)
        refreshCount()
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
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), 0)

        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL

        searchField = EditText(this)
        searchField.hint = "描述照片内容，例如：消防演练"
        searchField.maxLines = 1
        searchField.setSingleLine(true)
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
        searchButton.setOnClickListener { runSearch() }
        searchRow.addView(searchButton)

        val settingsButton = Button(this)
        settingsButton.text = "设置"
        settingsButton.setOnClickListener { showApiKeyDialog() }
        searchRow.addView(settingsButton)
        root.addView(searchRow)

        statusText = TextView(this)
        statusText.textSize = 13f
        statusText.setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        root.addView(statusText)

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL

        val indexButton = Button(this)
        indexButton.text = "建立索引"
        indexButton.setOnClickListener { startIndexing() }
        actionRow.addView(indexButton)

        val stopButton = Button(this)
        stopButton.text = "停止"
        stopButton.setOnClickListener { AiIndexer.stop() }
        actionRow.addView(stopButton)
        root.addView(actionRow)

        val recycler = RecyclerView(this)
        adapter = AiResultAdapter(this)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun updateStatus() {
        val builder = StringBuilder()
        builder.append("已索引 ").append(indexedCount).append(" 张")
        if (AiIndexState.running) {
            builder.append("　|　进度 ").append(AiIndexState.indexed).append("/").append(AiIndexState.total)
            if (AiIndexState.lastMessage.isNotEmpty()) {
                builder.append("\n正在处理：").append(AiIndexState.lastMessage)
            }
            refreshCount()
        }
        if (AiIndexState.lastError.isNotEmpty()) {
            builder.append("\n上次错误：").append(AiIndexState.lastError.take(150))
        }
        statusText.text = builder.toString()
    }

    private fun refreshCount() {
        Thread {
            val value = try {
                AiDatabase.getInstance(applicationContext).aiIndexDao().count()
            } catch (e: Exception) {
                0
            }
            indexedCount = value
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
                    statusText.text = "命中 " + results.size + " 张　/　库内 " + all.size + " 张"
                }
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
        input.setText("50")
        AlertDialog.Builder(this)
            .setTitle("索引多少张")
            .setMessage("按拍摄时间从新到旧取前面的若干张，已索引过的会自动跳过。")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val limit = (input.text.toString().toIntOrNull() ?: 50).coerceIn(1, 5000)
                AiIndexer.start(applicationContext, prefs, limit)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "sk-..."
        input.setText(prefs.apiKey)
        AlertDialog.Builder(this)
            .setTitle("百炼 API Key")
            .setMessage("用于生成照片描述和向量。只保存在本机，不会上传。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                prefs.apiKey = input.text.toString().trim()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
