package com.paperlens.app.data

import android.graphics.Bitmap
import android.net.Uri
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.PageOutline
import java.io.File

/**
 * The in-app camera hands over cropped page images. Next to each one it leaves the whole photo
 * (`<page>.src.jpg`) and the crop corners (`<page>.quad`), so the document can keep them and the crop
 * can be adjusted later.
 */
object CaptureSidecar {

    fun write(page: File, photo: Bitmap, outline: PageOutline) {
        val kept = BitmapUtils.scaleDown(photo, BitmapUtils.MAX_PAGE_DIMENSION)
        BitmapUtils.saveJpeg(kept, File(page.path + ".src.jpg"), 92)
        File(page.path + ".quad").writeText(outline.scaled(kept.width.toFloat() / photo.width).encode())
    }

    /** The whole photo and corners captured with [uri], or null for anything else. */
    fun read(uri: Uri): Pair<File, PageOutline>? {
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val photo = File("$path.src.jpg")
        val quad = File("$path.quad")
        if (!photo.exists() || !quad.exists()) return null
        val outline = PageOutline.decode(quad.readText()) ?: return null
        return photo to outline
    }
}
