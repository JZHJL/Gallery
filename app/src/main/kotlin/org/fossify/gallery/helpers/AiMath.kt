package org.fossify.gallery.helpers

import org.fossify.gallery.databases.AiIndex
import java.nio.ByteBuffer

object AiMath {
    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4)
        for (value in vector) {
            buffer.putFloat(value)
        }
        return buffer.array()
    }

    fun toFloats(bytes: ByteArray): FloatArray {
        if (bytes.size < 4) {
            return FloatArray(0)
        }
        val buffer = ByteBuffer.wrap(bytes)
        val result = FloatArray(bytes.size / 4)
        for (i in result.indices) {
            result[i] = buffer.float
        }
        return result
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) {
            return 0f
        }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i]).toDouble()
            normA += (a[i] * a[i]).toDouble()
            normB += (b[i] * b[i]).toDouble()
        }
        val denominator = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    fun keywordScore(item: AiIndex, query: String): Float {
        if (query.isEmpty()) {
            return 0f
        }
        val lowerQuery = query.lowercase()
        val haystack = (item.description + " " + item.ocrText + " " + item.path).lowercase()
        if (!haystack.contains(lowerQuery)) {
            var matched = 0
            for (chunk in lowerQuery.split(" ")) {
                if (chunk.length > 1 && haystack.contains(chunk)) {
                    matched++
                }
            }
            return if (matched > 0) 0.3f * matched else 0f
        }
        return 0.6f
    }

    fun rank(
        queryVector: FloatArray,
        query: String,
        items: List<Pair<AiIndex, FloatArray>>,
        threshold: Float = 0.25f
    ): List<Pair<AiIndex, Float>> {
        val scored = ArrayList<Pair<AiIndex, Float>>()
        for (entry in items) {
            val item = entry.first
            val vector = entry.second
            val vectorScore = if (queryVector.isEmpty() || vector.isEmpty()) 0f else cosine(queryVector, vector)
            val textScore = keywordScore(item, query)
            val score = maxOf(vectorScore, textScore)
            if (score >= threshold) {
                scored.add(item to score)
            }
        }
        scored.sortByDescending { it.second }
        return scored
    }
}
