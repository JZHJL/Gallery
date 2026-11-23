package org.fossify.gallery.helpers

import android.content.Context
import android.content.SharedPreferences

class AiPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_search", Context.MODE_PRIVATE)

    var apiKeys: List<String>
        get() = (prefs.getString("api_keys", "") ?: "")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("api_keys", value.filter { it.isNotBlank() }.joinToString("\n")).apply()

    var apiKey: String
        get() = apiKeys.firstOrNull() ?: ""
        set(value) {
            val list = apiKeys.toMutableList()
            if (list.isEmpty()) {
                list.add(value)
            } else {
                list[0] = value
            }
            apiKeys = list
        }

    var visionModel: String
        get() = prefs.getString("vision_model", "qwen3-vl-plus") ?: "qwen3-vl-plus"
        set(value) = prefs.edit().putString("vision_model", value.trim().ifEmpty { "qwen3-vl-plus" }).apply()

    var embedModel: String
        get() = prefs.getString("embed_model", "text-embedding-v4") ?: "text-embedding-v4"
        set(value) = prefs.edit().putString("embed_model", value.trim().ifEmpty { "text-embedding-v4" }).apply()

    var history: List<String>
        get() = (prefs.getString("history", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("history", value.take(10).joinToString("\n")).apply()

    fun addHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val list = history.toMutableList()
        list.remove(trimmed)
        list.add(0, trimmed)
        history = list
    }
}
