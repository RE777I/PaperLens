package com.paperlens.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Save Scans to Gallery After Scanning": copies scanned pages into Pictures/PaperLens. */
object GallerySaver {

    /** Needs MediaStore relative paths (Android 10+); older phones would need a storage permission. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun save(context: Context, images: List<Uri>, baseName: String): Int = withContext(Dispatchers.IO) {
        if (!supported) return@withContext 0
        val resolver = context.contentResolver
        var saved = 0
        images.forEachIndexed { i, source ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${Exporter.safeName(baseName)}_${i + 1}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PaperLens")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
            runCatching {
                resolver.openInputStream(source)?.use { input -> resolver.openOutputStream(target)?.use { input.copyTo(it) } }
                resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                saved++
            }.onFailure {
                android.util.Log.w("GallerySaver", "Saving page ${i + 1} to the gallery", it)
                resolver.delete(target, null, null)
            }
        }
        saved
    }
}
