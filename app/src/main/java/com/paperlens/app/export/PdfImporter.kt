package com.paperlens.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.paperlens.app.data.DocumentRepository
import com.paperlens.app.imaging.BitmapUtils
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns each page of a PDF into an editable document page (rendered at 300 dpi, like an A4 scan). The
 * page itself is kept as a one-page PDF with its text, so it zooms sharp, is searchable without OCR and
 * exports unchanged (see [PdfPages]); a PDF that PDFBox can't read is imported as pictures only.
 */
object PdfImporter {

    // Small print stays sharp when zoomed; an A4 page comes out at 2480 × 3508, the largest page size kept.
    private const val DPI = 300f
    private const val MAX_DIMENSION = BitmapUtils.MAX_PAGE_DIMENSION

    suspend fun import(context: Context, uri: Uri, repository: DocumentRepository): Long = withContext(Dispatchers.IO) {
        val title = displayName(context, uri)?.substringBeforeLast('.') ?: repository.defaultTitle("PDF")
        // Open the file first, so an unreadable PDF leaves no empty document behind.
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
        val documentId = repository.createDocument(title)
        val work = File(context.cacheDir, "pdf_import").apply { deleteRecursively(); mkdirs() }
        val source = runCatching { context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) } }.getOrNull()
        val pagePdfs = source?.let { doc -> PdfPages.split(doc) { File(work, "page_$it.pdf") } }
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        var scale = DPI / 72f
                        val longest = max(page.width, page.height) * scale
                        if (longest > MAX_DIMENSION) scale *= MAX_DIMENSION / longest
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).roundToInt().coerceAtLeast(1),
                            (page.height * scale).roundToInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        // One page at a time keeps memory flat for long PDFs.
                        val text = source?.let { doc -> runCatching { PdfPages.text(doc, index) }.getOrNull() }
                        repository.addPdfPage(documentId, bitmap, pagePdfs?.getOrNull(index), text?.plain)
                        bitmap.recycle()
                    }
                }
            }
        }
        source?.close()
        work.deleteRecursively()
        documentId
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
