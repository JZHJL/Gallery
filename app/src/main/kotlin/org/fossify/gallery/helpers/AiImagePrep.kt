package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

object AiImagePrep {
    fun loadJpeg(context: Context, uri: Uri, maxSide: Int = 1024, quality: Int = 85): ByteArray? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        var sampleSize = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) {
            return null
        }
        while (longest / sampleSize > maxSide * 2) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options()
        options.inSampleSize = sampleSize
        val original = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        val scale = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height).toFloat())
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
        } else {
            original
        }

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (bitmap !== original) {
            bitmap.recycle()
        }
        original.recycle()
        return output.toByteArray()
    }
}
