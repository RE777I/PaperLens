package com.paperlens.app.export

import android.content.Context
import android.graphics.Bitmap
import com.paperlens.app.data.ExportQuality
import com.paperlens.app.data.PageEntity
import com.paperlens.app.data.PdfPageSize
import com.paperlens.app.data.pdfFile
import com.paperlens.app.app
import com.paperlens.app.ml.TextLayout
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.PageComposer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/** Writes export files into cache/exports, ready to share through the FileProvider. */
class Exporter(private val context: Context) {

    private val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    fun clearOldExports() {
        exportDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * PDF with the scanned JPEGs embedded as-is (no re-encode at HIGH quality), optional
     * downscaling for smaller files, and optional password protection (AES-128).
     *
     * With "Searchable PDF" on (Settings), each scan gets the recognised text hidden behind it
     * ([PdfTextLayer]), so the PDF can be searched and its text copied. Pages imported from a PDF and
     * left unchanged go in as the original PDF page: same quality and text as the file they came from.
     */
    suspend fun pdf(
        title: String,
        pages: List<PageEntity>,
        pageSize: PdfPageSize,
        quality: ExportQuality,
        password: String? = null,
    ): File = withContext(Dispatchers.IO) {
        val out = File(exportDir, safeName(title) + ".pdf")
        val settings = context.app.settings.current()
        // Source PDFs must stay open until the new one is saved (imported pages share their resources).
        val sources = mutableListOf<PDDocument>()
        PDDocument().use { doc ->
            val textLayer = PdfTextLayer(doc)
            for (page in pages) {
                val original = page.pdfFile?.let { f -> runCatching { PDDocument.load(f) }.getOrNull() }
                if (original != null && original.numberOfPages > 0) {
                    sources += original
                    doc.importPage(original.getPage(0))
                    continue
                }
                original?.close()
                val image = JPEGFactory.createFromByteArray(doc, jpegBytes(File(page.imagePath), quality))
                val rect = when (pageSize) {
                    PdfPageSize.A4 -> PDRectangle.A4
                    PdfPageSize.LETTER -> PDRectangle.LETTER
                    PdfPageSize.FIT -> PDRectangle(A4_WIDTH_PT, A4_WIDTH_PT * image.height / image.width)
                }
                val pdPage = PDPage(rect)
                doc.addPage(pdPage)
                val margin = if (pageSize == PdfPageSize.FIT) 0f else 18f
                val scale = min((rect.width - 2 * margin) / image.width, (rect.height - 2 * margin) / image.height)
                val w = image.width * scale
                val h = image.height * scale
                PDPageContentStream(doc, pdPage).use { stream ->
                    stream.drawImage(image, (rect.width - w) / 2, (rect.height - h) / 2, w, h)
                }
                if (settings.searchablePdf) {
                    // A page that can't be read keeps just its picture; the export still succeeds.
                    runCatching {
                        val (words, size) = TextLayout.words(context, File(page.imagePath), settings.ocrScript)
                        textLayer.add(pdPage, words, size.first, size.second, (rect.width - w) / 2, (rect.height - h) / 2, w, h)
                    }.onFailure { android.util.Log.w("Exporter", "No text layer for page ${page.position + 1}", it) }
                }
            }
            if (!password.isNullOrEmpty()) {
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            doc.save(out)
        }
        sources.forEach { it.close() }
        out
    }

    /** "Export Pages as Separate PDFs": one PDF per page. */
    suspend fun separatePdfs(title: String, pages: List<PageEntity>, pageSize: PdfPageSize, quality: ExportQuality): List<File> =
        pages.mapIndexed { i, page -> pdf("${title}_${(i + 1).toString().padStart(2, '0')}", listOf(page), pageSize, quality) }

    suspend fun jpegs(title: String, pages: List<PageEntity>, quality: ExportQuality): List<File> =
        withContext(Dispatchers.IO) {
            val base = safeName(title)
            pages.mapIndexed { i, page ->
                File(exportDir, "${base}_${(i + 1).toString().padStart(2, '0')}.jpg").apply {
                    writeBytes(jpegBytes(File(page.imagePath), quality))
                }
            }
        }

    suspend fun longImage(title: String, pages: List<PageEntity>): File = withContext(Dispatchers.Default) {
        val bitmaps = pages.map { BitmapUtils.decode(File(it.imagePath), 1754) }
        val stitched = PageComposer.longImage(bitmaps)
        BitmapUtils.saveJpeg(stitched, File(exportDir, safeName(title) + "_long.jpg"), 85)
    }

    suspend fun text(title: String, text: String): File = withContext(Dispatchers.IO) {
        File(exportDir, safeName(title) + ".txt").apply { writeText(text) }
    }

    /** Minimal Office Open XML document: one paragraph per text line, page break between scanned pages. */
    suspend fun docx(title: String, pageTexts: List<String>): File = withContext(Dispatchers.IO) {
        val body = StringBuilder()
        pageTexts.forEachIndexed { index, pageText ->
            if (index > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            pageText.lines().forEach { line ->
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(xmlEscape(line)).append("</w:t></w:r></w:p>")
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val out = File(exportDir, safeName(title) + ".docx")
        ZipOutputStream(out.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/document.xml", document)
        }
        out
    }

    private fun jpegBytes(file: File, quality: ExportQuality): ByteArray {
        if (quality == ExportQuality.HIGH) return file.readBytes()
        val bitmap = BitmapUtils.decode(file, quality.maxDimension)
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, stream)
            stream.toByteArray()
        }
    }

    private fun xmlEscape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' || c >= ' ' -> append(c)
                // other control characters are invalid in XML 1.0 and dropped
            }
        }
    }

    companion object {
        private const val A4_WIDTH_PT = 595.28f

        fun safeName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80).ifBlank { "PaperLens" }
    }
}
