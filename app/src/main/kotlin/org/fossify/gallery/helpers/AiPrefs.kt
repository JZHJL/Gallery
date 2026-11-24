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

    var accentColor: Int
        get() = prefs.getInt("accent_color", 0)
        set(value) = prefs.edit().putInt("accent_color", value).apply()

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
