package org.fossify.gallery.helpers

import android.content.Context
import android.content.SharedPreferences

class AiPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_search", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var visionModel: String
        get() = prefs.getString("vision_model", "qwen3-vl-plus") ?: "qwen3-vl-plus"
        set(value) = prefs.edit().putString("vision_model", value).apply()

    var history: String
        get() = prefs.getString("history", "") ?: ""
        set(value) = prefs.edit().putString("history", value).apply()

    var embedModel: String
        get() = prefs.getString("embed_model", "text-embedding-v4") ?: "text-embedding-v4"
        set(value) = prefs.edit().putString("embed_model", value).apply()
}
