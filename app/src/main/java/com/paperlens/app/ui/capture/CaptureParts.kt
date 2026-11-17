package com.paperlens.app.ui.capture

import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.data.CaptureSidecar
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.DocumentDetector
import com.paperlens.app.imaging.PageOutline
import java.io.File
import java.util.UUID

/** Camera modes, in carousel order. */
enum class CaptureMode(private val labelRes: Int, val batchByDefault: Boolean = false) {
    EXTRACT_TEXT(R.string.extract_text),
    TRANSLATE(R.string.translate),
    TO_WORD(R.string.to_word),
    SIGN(R.string.sign),
    SCAN(R.string.scan, batchByDefault = true),
    SMART_ERASE(R.string.smart_erase),
    ID_CARD(R.string.id_card, batchByDefault = true),
    BOOK(R.string.book, batchByDefault = true),
    TO_EXCEL(R.string.to_excel),
    WHITEBOARD(R.string.whiteboard),
    TIMESTAMP(R.string.timestamp),
    SCAN_CODE(R.string.scan_code),
    ;
    val label: String get() = S.str(labelRes)


    val hint: String
        get() = when (this) {
            ID_CARD -> S.str(R.string.front_side_first_then_the_back)
            BOOK -> S.str(R.string.capture_the_open_book_both_pages_at_once)
            SCAN_CODE -> ""
            else -> label
        }
}

/** A photo waiting in the crop review: full-size, a screen-size copy, and the detected corners. */
internal class PendingShot(val photo: Bitmap, val preview: Bitmap, val outline: PageOutline?)

/** Decodes a fresh capture (and deletes the file) and finds the page in it. */
internal fun pendingShot(raw: File): PendingShot {
    val photo = BitmapUtils.decode(raw)
    raw.delete()
    return PendingShot(photo, BitmapUtils.scaleDown(photo, 1600), DocumentDetector.detectOutline(photo))
}

/** True when [corners] are the photo's own corners ("Full Page"), so no warp is needed. */
internal fun isWholePhoto(corners: List<PointF>, photo: Bitmap): Boolean {
    val w = photo.width.toFloat()
    val h = photo.height.toFloat()
    val expected = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(w, 0f), floatArrayOf(w, h), floatArrayOf(0f, h))
    for (i in 0 until 4) {
        val dx: Float = corners[i].x - expected[i][0]
        val dy: Float = corners[i].y - expected[i][1]
        if (dx * dx + dy * dy > 1f) return false
    }
    return true
}

internal enum class Flash(val mode: Int) { OFF(ImageCapture.FLASH_MODE_OFF), AUTO(ImageCapture.FLASH_MODE_AUTO), ON(ImageCapture.FLASH_MODE_ON) }

/** Analysed frames (about 350 ms apart) the page must hold still before an auto shot. */
internal const val STILL_FRAMES_TO_SHOOT = 3

internal val TrayWidth = 44.dp
internal val TrayHeight = 56.dp
internal val TraySpacing = 6.dp

/** One captured page in the tray: the frozen frame at once, the processed page when ready. */
internal class Shot(val thumb: ImageBitmap?) {
    var page by mutableStateOf<Uri?>(null)
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Crops a fresh capture to the page it finds (unless [crop] is false) and saves it for the document. */
internal fun processShot(context: android.content.Context, raw: File, crop: Boolean): File {
    val photo = BitmapUtils.decode(raw)
    raw.delete()
    val outline = if (crop) DocumentDetector.detectOutline(photo) else null
    val page = File(context.cacheDir, "capture/page_${UUID.randomUUID()}.jpg")
    BitmapUtils.saveJpeg(outline?.let { DocumentDetector.warp(photo, it) } ?: photo, page, 92)
    if (outline != null) CaptureSidecar.write(page, photo, outline)
    return page
}

/** Saves a single shot cropped to the corners chosen in the review. */
internal fun saveReviewed(context: android.content.Context, photo: Bitmap, outline: PageOutline): File {
    val page = File(context.cacheDir, "capture/page_${UUID.randomUUID()}.jpg")
    if (isWholePhoto(outline.corners, photo) && !outline.curved) {
        BitmapUtils.saveJpeg(photo, page, 92)
    } else {
        BitmapUtils.saveJpeg(DocumentDetector.warp(photo, outline), page, 92)
        CaptureSidecar.write(page, photo, outline)
    }
    return page
}
