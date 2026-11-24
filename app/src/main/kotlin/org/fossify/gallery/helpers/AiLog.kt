package org.fossify.gallery.helpers

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiLog {

    private const val MAX_BYTES = 512 * 1024
    private const val MAX_MEMORY_LINES = 400

    private val lock = Any()
    private val memory = ArrayDeque<String>()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) {
            return
        }
        synchronized(lock) {
            if (initialized) {
                return
            }
            val base = context.getExternalFilesDir("logs") ?: context.filesDir
            base.mkdirs()
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            logFile = File(base, "ai-" + day + ".log")
            initialized = true
        }
    }

    fun w(tag: String, message: String) {
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + " [" + tag + "] " + message
        synchronized(lock) {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY_LINES) {
                memory.removeFirst()
            }
            val target = logFile ?: return
            try {
                if (target.length() > MAX_BYTES) {
                    val backup = File(target.parentFile, target.name + ".old")
                    backup.delete()
                    target.renameTo(backup)
                }
                target.appendText(line + "\n")
            } catch (ignored: Exception) {
            }
        }
    }

    fun currentFile(): File? = logFile

    fun tail(maxLines: Int = 120): String {
        synchronized(lock) {
            return memory.toList().takeLast(maxLines).joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            memory.clear()
            try {
                logFile?.writeText("")
            } catch (ignored: Exception) {
            }
        }
    }
}
