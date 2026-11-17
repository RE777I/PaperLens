package com.paperlens.app.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.paperlens.app.runLogged
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapUtils {

    /** Longest edge kept for stored pages: ~300 dpi for A4, plenty for OCR and print. */
    const val MAX_PAGE_DIMENSION = 3508

    fun imageSize(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        return opts.outWidth to opts.outHeight
    }

    /** Decodes [file] no larger than [maxDimension] on its longest edge, honouring EXIF orientation. */
    fun decode(file: File, maxDimension: Int = MAX_PAGE_DIMENSION): Bitmap {
        val (w, h) = imageSize(file)
        val bitmap = BitmapFactory.decodeFile(file.path, sampledOptions(w, h, maxDimension))
            ?: error("Cannot decode image ${file.name}")
        val rotated = applyExif(bitmap, file.inputStream().use { exifRotation(it) })
        return scaleDown(rotated, maxDimension)
    }

    fun decode(context: Context, uri: Uri, maxDimension: Int = MAX_PAGE_DIMENSION): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, sampledOptions(bounds.outWidth, bounds.outHeight, maxDimension))
        } ?: error("Cannot decode image $uri")
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        return scaleDown(applyExif(bitmap, rotation), maxDimension)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun scaleDown(src: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxDimension) return src
        val scale = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    fun savePng(bitmap: Bitmap, file: File): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun sampledOptions(width: Int, height: Int, maxDimension: Int): BitmapFactory.Options {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDimension) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }

    private fun exifRotation(input: InputStream): Int = runLogged("BitmapUtils", "Reading the photo's EXIF rotation") {
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun applyExif(bitmap: Bitmap, rotation: Int): Bitmap = rotate(bitmap, rotation)
}
