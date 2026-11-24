package org.fossify.gallery.helpers

import android.content.Context
import android.content.SharedPreferences

class AiPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_search", Context.MODE_PRIVATE)

    var apiKeys: List<String>
        get() {
            val raw = prefs.getString("api_keys", "") ?: ""
            val list = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isNotEmpty()) {
                return list
            }
            val legacy = prefs.getString("api_key", "") ?: ""
            return if (legacy.isEmpty()) emptyList() else listOf(legacy)
        }
        set(value) {
            val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            prefs.edit().putString("api_keys", cleaned.joinToString("\n")).putString("api_key", "").apply()
        }

    var apiKey: String
        get() = apiKeys.firstOrNull() ?: ""
        set(value) {
            apiKeys = listOf(value)
        }

    val hasKey: Boolean
        get() = apiKeys.isNotEmpty()

    var visionModel: String
        get() = prefs.getString("vision_model", "qwen3-vl-plus") ?: "qwen3-vl-plus"
        set(value) = prefs.edit().putString("vision_model", value.trim()).apply()

    var embedModel: String
        get() = prefs.getString("embed_model", "text-embedding-v4") ?: "text-embedding-v4"
        set(value) = prefs.edit().putString("embed_model", value.trim()).apply()

    var ballSize: Int
        get() = prefs.getInt("ball_size", 56)
        set(value) = prefs.edit().putInt("ball_size", value.coerceIn(36, 88)).apply()

    var ballAlpha: Int
        get() = prefs.getInt("ball_alpha", 255)
        set(value) = prefs.edit().putInt("ball_alpha", value.coerceIn(110, 255)).apply()

    var ballX: Int
        get() = prefs.getInt("ball_x", -1)
        set(value) = prefs.edit().putInt("ball_x", value).apply()

    var ballY: Int
        get() = prefs.getInt("ball_y", -1)
        set(value) = prefs.edit().putInt("ball_y", value).apply()

    var widgetText: String
        get() = prefs.getString("widget_text", "🔍  搜照片") ?: "🔍  搜照片"
        set(value) = prefs.edit().putString("widget_text", value.ifBlank { "🔍  搜照片" }).apply()

    var accentColor: Int
        get() = prefs.getInt("accent_color", 0)
        set(value) = prefs.edit().putInt("accent_color", value).apply()

    var tokensIn: Long
        get() = prefs.getLong("tokens_in", 0L)
        set(value) = prefs.edit().putLong("tokens_in", value).apply()

    var tokensOut: Long
        get() = prefs.getLong("tokens_out", 0L)
        set(value) = prefs.edit().putLong("tokens_out", value).apply()

    var tokensEmb: Long
        get() = prefs.getLong("tokens_emb", 0L)
        set(value) = prefs.edit().putLong("tokens_emb", value).apply()

    fun addUsage(inputTokens: Int, outputTokens: Int, embeddingTokens: Int) {
        tokensIn = tokensIn + inputTokens
        tokensOut = tokensOut + outputTokens
        tokensEmb = tokensEmb + embeddingTokens
    }

    fun estimatedCost(): Double {
        return (tokensIn * 1.0 + tokensOut * 10.0 + tokensEmb * 0.5) / 1000000.0
    }

    var searchHistory: List<String>
        get() = (prefs.getString("search_history", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("search_history", value.take(10).joinToString("\n")).apply()

    fun addHistory(query: String) {
        if (query.isBlank()) {
            return
        }
        val current = searchHistory.toMutableList()
        current.remove(query)
        current.add(0, query)
        searchHistory = current
    }
}
