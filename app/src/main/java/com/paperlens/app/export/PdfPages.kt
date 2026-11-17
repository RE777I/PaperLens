package com.paperlens.app.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.paperlens.app.ml.SearchableText
import com.paperlens.app.runLogged
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Pages imported from a PDF keep that page as a one-page PDF (see `PageEntity.pdfPath`). This reads
 * them: the page's own text with positions (no OCR needed), and sharp renders of any part of it for zoom.
 */
object PdfPages {

    /** A page's text: plain (as stored in `ocrText`) and searchable with a box for every character. */
    class PageText(val plain: String, val searchable: SearchableText)

    /** Splits [doc] into one-page PDFs, written by [target] for each page index; null for a page that fails. */
    fun split(doc: PDDocument, target: (Int) -> File): List<File?> = List(doc.numberOfPages) { i ->
        runLogged("PdfPages", "Keeping page ${i + 1} as its own PDF") {
            PDDocument().use { single ->
                single.importPage(doc.getPage(i))
                target(i).also { single.save(it) }
            }
        }.getOrNull()
    }

    /** The text of page [index] of [doc] (0-based), or null when it has none (a scanned PDF). */
    fun text(doc: PDDocument, index: Int): PageText? {
        val page = doc.getPage(index)
        val box = page.cropBox
        val turned = page.rotation % 180 != 0
        val w = if (turned) box.height else box.width
        val h = if (turned) box.width else box.height
        if (w <= 0f || h <= 0f) return null
        val collector = Collector(w, h)
        collector.startPage = index + 1
        collector.endPage = index + 1
        collector.getText(doc)
        val plain = collector.plain.toString().trim()
        if (plain.isEmpty()) return null
        return PageText(plain, SearchableText.fromChars(collector.chars, collector.boxes, collector.lines, w / h))
    }

    /** Collects every character with its box (as fractions of the page), and the plain text with breaks. */
    private class Collector(private val w: Float, private val h: Float) : PDFTextStripper() {
        val chars = ArrayList<Char>()
        val boxes = ArrayList<RectF?>()
        val lines = ArrayList<Int>()
        val plain = StringBuilder()
        private var line = 0

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                val u = tp.unicode ?: continue
                val top = tp.yDirAdj - tp.heightDir * 1.25f
                val r = RectF(tp.xDirAdj / w, top / h, (tp.xDirAdj + tp.widthDirAdj) / w, tp.yDirAdj / h)
                for (c in u) {
                    chars += c; boxes += r; lines += line
                }
                plain.append(u)
            }
        }

        override fun writeWordSeparator() {
            chars += ' '; boxes += null; lines += line
            plain.append(' ')
        }

        override fun writeLineSeparator() {
            line++
            chars += '\n'; boxes += null; lines += line
            plain.append('\n')
        }
    }

    /** Page size in points (as shown, i.e. after its rotation), or null when the file can't be opened. */
    fun size(file: File): Pair<Int, Int>? = runLogged("PdfPages", "Page size of ${file.name}") {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> r.openPage(0).use { it.width to it.height } }
        }
    }.getOrNull()

    /**
     * Renders the part [region] (fractions of the page) of [file] into a [width] × [height] bitmap: crisp
     * text and line art at any zoom. White where the page has no background of its own.
     */
    fun render(file: File, region: RectF, width: Int, height: Int): Bitmap? = runLogged("PdfPages", "Rendering part of ${file.name}") {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    // Page points → bitmap pixels, so that [region] fills the bitmap.
                    val sx = width / (region.width() * page.width)
                    val sy = height / (region.height() * page.height)
                    val m = Matrix().apply {
                        setScale(sx, sy)
                        postTranslate(-region.left * page.width * sx, -region.top * page.height * sy)
                    }
                    page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }.getOrNull()
}
