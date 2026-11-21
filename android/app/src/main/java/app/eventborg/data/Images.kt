package app.eventborg.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object Images {
    fun scale(b: Bitmap, max: Int): Bitmap {
        val s = max.toFloat() / maxOf(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }

    // Decodes at most `max` px on the long side (EXIF rotation applied on Android 9+).
    fun fromUri(ctx: Context, uri: Uri, max: Int = 1280): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val s = max.toFloat() / maxOf(w, h)
                if (s < 1f) d.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }?.let { scale(it, max) }
        }
    }.getOrNull()

    // JPEG on white, so transparent screenshots don't turn black.
    fun jpeg(b: Bitmap, quality: Int = 82): ByteArray {
        val flat = if (b.hasAlpha()) {
            Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(b, 0f, 0f, null)
                }
            }
        } else {
            b
        }
        return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
