package org.fossify.gallery.helpers

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class BailianClient(private val prefs: AiPrefs) {

    data class TokenUsage(val inputTokens: Int, val outputTokens: Int)

    companion object {
        private const val BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        private const val VISION_PROMPT = "请用中文详细描述这张照片：场景、主要对象、人物及其动作、环境与光线氛围。如果画面中有文字、标题、公式、板书、路牌、标签，请逐字转写出来。不超过200字。"
    }

    fun describeImage(jpegBytes: ByteArray): Pair<String, TokenUsage> {
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + base64))
        })
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", VISION_PROMPT)
        })

        val payload = JSONObject().apply {
            put("model", prefs.visionModel)
            put("max_tokens", 500)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }

        val response = post(BASE_URL + "/chat/completions", payload.toString())
        val text = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        val usage = response.optJSONObject("usage")
        val tokens = TokenUsage(
            usage?.optInt("prompt_tokens") ?: 0,
            usage?.optInt("completion_tokens") ?: 0
        )
        return text.trim() to tokens
    }

    fun embedText(text: String): FloatArray {
        val payload = JSONObject().apply {
            put("model", prefs.embedModel)
            put("dimensions", 1024)
            put("encoding_format", "float")
            put("input", JSONArray().put(text))
        }
        val response = post(BASE_URL + "/embeddings", payload.toString())
        val array = response.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("embedding") ?: return FloatArray(0)
        val vector = FloatArray(array.length())
        for (i in 0 until array.length()) {
            vector[i] = array.optDouble(i, 0.0).toFloat()
        }
        return vector
    }

    private fun post(url: String, body: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 90000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer " + prefs.apiKey)
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP " + code + ": " + text.take(300))
        }
        return JSONObject(text)
    }
}
