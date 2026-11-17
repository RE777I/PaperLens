package com.paperlens.app.ui.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.DocumentEntity
import com.paperlens.app.data.ExportQuality
import com.paperlens.app.data.PageEntity
import com.paperlens.app.data.addQr
import com.paperlens.app.data.createWordDocument
import com.paperlens.app.data.createExcelDocument
import com.paperlens.app.data.importDocx
import com.paperlens.app.data.importXlsx
import com.paperlens.app.data.moveToFolder
import com.paperlens.app.export.Exporter
import com.paperlens.app.export.GallerySaver
import com.paperlens.app.export.PdfImporter
import com.paperlens.app.export.Sharing
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.imaging.QrCodes
import com.paperlens.app.security.AppLock
import com.paperlens.app.ui.capture.CaptureMode
import com.paperlens.app.ui.capture.CaptureScreen
import com.paperlens.app.ui.components.BusyController
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.OcrTextSheet
import com.paperlens.app.ui.components.QrResultDialog
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.ToolButton
import com.google.mlkit.vision.barcode.common.Barcode
import com.paperlens.app.ui.components.barcodeFormatName
import com.paperlens.app.ui.components.findActivity
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.rememberDocumentScanner
import com.paperlens.app.ui.components.startQrScan
import com.paperlens.app.ui.components.toast
import com.paperlens.app.ui.word.DOCX_MIME
import com.paperlens.app.excel.PageToExcel
import com.paperlens.app.excel.XlsxReader
import com.paperlens.app.excel.XlsxWriter
import com.paperlens.app.word.DocxReader
import com.paperlens.app.word.DocxWriter
import com.paperlens.app.word.PageToWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PagesMode { EXTRACT, REORDER }

/** Where tools send the user next. */
class ToolNavigator(
    val openDocument: (Long) -> Unit,
    val openPage: (documentId: Long, index: Int) -> Unit,
    val openIdCard: () -> Unit,
    val openCodeHistory: () -> Unit,
    val openPagesTool: (documentId: Long, mode: PagesMode) -> Unit,
    val openToolsTab: () -> Unit,
    val openSign: (pageId: Long) -> Unit = {},
    val openErase: (pageId: Long) -> Unit = {},
    val openTranslate: (documentId: Long, index: Int) -> Unit = { _, _ -> },
)

private enum class PdfPurpose { TO_IMAGES, TO_LONG_IMAGE }

internal data class TextResult(val title: String, val text: String, val translate: Boolean)

/**
 * Runs every tool in [Tool] from anywhere (Home grid, Tools tab, Files tab, list actions).
 * Create it with [rememberToolRunner] and draw its dialogs with [ToolRunnerHost].
 */
class ToolRunner internal constructor(
    private val context: Context,
    val busy: BusyController,
    private val scope: CoroutineScope,
) {
    internal lateinit var nav: ToolNavigator
    internal var scan: () -> Unit = {}
    internal var launchImages: () -> Unit = {}
    internal var launchFiles: () -> Unit = {}
    internal var launchPdf: () -> Unit = {}
    internal var launchSavePdf: (String) -> Unit = {}

    /** Documents created by this runner are filed here (set while browsing a folder). */
    var targetFolderId: Long? = null

    private val repo get() = context.app.repository
    private val exporter get() = context.app.exporter
    private var pendingScan = CaptureMode.SCAN

    // Camera results that go into an existing document (Add) or replace one page (Retake).
    private var targetDocument: Long? = null
    private var replacing: PageEntity? = null
    internal var captureSingle by mutableStateOf(false)
    private var pendingPdf = PdfPurpose.TO_IMAGES

    /** The tool waiting for the document that the picker's "Scan new" or "Import file" shortcut is making. */
    private var then: Tool? = null

    /** Builds the PDF that "Save to device" writes once the user has picked where. */
    private var pendingSave: (suspend () -> File)? = null

    /** Non-null while the in-app camera is open, in this mode. */
    internal var capture by mutableStateOf<CaptureMode?>(null)

    internal var picking by mutableStateOf<Tool?>(null)
    internal var textResult by mutableStateOf<TextResult?>(null)
    internal var qrResult by mutableStateOf<Pair<String, String>?>(null)
    internal var watermarkFor by mutableStateOf<Long?>(null)
    internal var mergeIds by mutableStateOf<List<Long>?>(null)
    internal var showPdfTools by mutableStateOf(false)
    internal var compressFor by mutableStateOf<DocumentEntity?>(null)
    internal var numbersFor by mutableStateOf<DocumentEntity?>(null)
    internal var creatingCode by mutableStateOf(false)
    internal var codeShown by mutableStateOf<String?>(null)

    fun run(tool: Tool) {
        showPdfTools = false
        then = null
        when (tool) {
            Tool.SMART_SCAN -> startScan(CaptureMode.SCAN)
            Tool.PDF_TOOLS -> showPdfTools = true
            Tool.ALL_TOOLS -> nav.openToolsTab()
            Tool.ID_CARDS -> nav.openIdCard()
            Tool.EXTRACT_TEXT -> startScan(CaptureMode.EXTRACT_TEXT)
            Tool.PHOTO_TRANSLATION -> startScan(CaptureMode.TRANSLATE)
            Tool.BOOK -> startScan(CaptureMode.BOOK)
            Tool.WHITEBOARD -> startScan(CaptureMode.WHITEBOARD)
            Tool.SMART_ERASE -> startScan(CaptureMode.SMART_ERASE)
            Tool.IMPORT_IMAGES -> launchImages()
            Tool.IMPORT_FILES -> launchFiles()
            Tool.PDF_TO_IMAGES -> { pendingPdf = PdfPurpose.TO_IMAGES; launchPdf() }
            Tool.PDF_TO_LONG_IMAGE -> { pendingPdf = PdfPurpose.TO_LONG_IMAGE; launchPdf() }
            Tool.SCAN_CODE -> scanCode()
            Tool.CREATE_CODE -> creatingCode = true
            Tool.CODE_HISTORY -> nav.openCodeHistory()
            Tool.TIMESTAMP, Tool.TO_WORD, Tool.TO_EXCEL, Tool.SIGN, Tool.ADD_WATERMARK, Tool.MERGE_FILES,
            Tool.EXTRACT_PAGES, Tool.REORDER_PAGES, Tool.PAGE_NUMBERS, Tool.LOCK, Tool.COMPRESS, Tool.PRINT,
            -> picking = tool
        }
    }

    /** Runs a document tool on documents the user already chose (Home "To Word", list selection). */
    fun runOn(tool: Tool, documents: List<DocumentEntity>) {
        picking = null
        if (documents.isEmpty()) return
        if (documents.any { it.locked }) {
            AppLock.authenticate(context.findActivity(), S.str(R.string.unlock_document)) { ok -> if (ok) perform(tool, documents) }
        } else {
            perform(tool, documents)
        }
    }

    private fun perform(tool: Tool, documents: List<DocumentEntity>) {
        val doc = documents.first()
        if (doc.isWord && tool in SCAN_ONLY) {
            context.toast(S.str(R.string.x_is_a_word_document_open_it_to_edit_or_shar, doc.title))
            return
        }
        if (doc.isExcel && tool != Tool.TO_EXCEL && tool in SCAN_ONLY + Tool.TO_WORD) {
            context.toast(S.str(R.string.x_is_an_excel_document_open_it_to_edit_or_sha, doc.title))
            return
        }
        when (tool) {
            Tool.TIMESTAMP -> busy.run(S.str(R.string.adding_timestamp)) {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                repo.stampDateTime(doc.id, stamp)
                nav.openDocument(doc.id)
            }
            Tool.TO_WORD -> if (doc.isWord) nav.openDocument(doc.id) else busy.run(S.str(R.string.converting_to_word)) {
                // A new Word document next to the scan (layout, tables and pictures rebuilt), opened for editing.
                val script = context.app.settings.current().ocrScript
                val word = PageToWord.convertPages(context, repo.getPages(doc.id), script)
                nav.openDocument(repo.createWordDocument("${doc.title} (Word)", word, doc.folderId))
            }
            Tool.TO_EXCEL -> if (doc.isExcel) nav.openDocument(doc.id) else busy.run(S.str(R.string.recognising_tables)) {
                // A new Excel document next to the scan (tables rebuilt with their cells), opened in the viewer.
                val script = context.app.settings.current().ocrScript
                val book = PageToExcel.convertPages(context, repo.getPages(doc.id), script)
                nav.openDocument(repo.createExcelDocument("${doc.title} (Excel)", book, doc.folderId))
            }
            Tool.SIGN -> {
                context.toast(S.str(R.string.swipe_to_the_page_you_want_to_sign_then_tap))
                nav.openPage(doc.id, 0)
            }
            Tool.ADD_WATERMARK -> watermarkFor = doc.id
            Tool.MERGE_FILES -> {
                val scans = documents.filterNot { it.isFile }
                if (scans.size < documents.size) context.toast(S.str(R.string.word_and_excel_documents_are_left_out_of_a_merge))
                if (scans.size < 2) context.toast(S.str(R.string.choose_at_least_two_scanned_documents)) else mergeIds = scans.map { it.id }
            }
            Tool.EXTRACT_PAGES -> nav.openPagesTool(doc.id, PagesMode.EXTRACT)
            Tool.REORDER_PAGES -> nav.openPagesTool(doc.id, PagesMode.REORDER)
            Tool.LOCK -> busy.run(S.str(R.string.updating)) {
                if (doc.locked) {
                    repo.setLocked(doc.id, false)
                    context.toast(S.str(R.string.x_unlocked, doc.title))
                } else if (!AppLock.isAvailable(context)) {
                    context.toast(S.str(R.string.set_up_a_screen_lock_or_fingerprint_on_this))
                } else {
                    repo.setLocked(doc.id, true)
                    context.toast(S.str(R.string.x_locked, doc.title))
                }
            }
            Tool.COMPRESS -> compressFor = doc
            Tool.PAGE_NUMBERS -> numbersFor = doc
            Tool.PRINT -> busy.run(S.str(R.string.preparing_print)) {
                val s = context.app.settings.current()
                Sharing.printPdf(context, exporter.pdf(doc.title, repo.getPages(doc.id), s.pdfPageSize, ExportQuality.HIGH), doc.title)
            }
            else -> run(tool)
        }
    }

    // ---- launcher results ------------------------------------------------------------------------

    /** Opens the in-app camera in [mode], or the Google scanner when that is chosen in Settings → Scan. */
    private fun startScan(mode: CaptureMode, into: Long? = null, replace: PageEntity? = null, then: Tool? = null) {
        this.then = then
        pendingScan = mode
        targetDocument = into
        replacing = replace
        captureSingle = replace != null
        scope.launch {
            if (context.app.settings.current().useGoogleScanner) useGoogleScanner(mode) else capture = mode
        }
    }

    /** Adds pages to [documentId] with the in-app camera, which keeps each page's original photo. */
    fun scanInto(documentId: Long) = startScan(CaptureMode.SCAN, into = documentId)

    /** Retakes [page] with the in-app camera (single shot); the new photo is kept for re-cropping. */
    fun retake(page: PageEntity) = startScan(CaptureMode.SCAN, into = page.documentId, replace = page)

    internal fun useGoogleScanner(mode: CaptureMode) {
        capture = null
        pendingScan = mode
        if (mode == CaptureMode.SMART_ERASE) context.toast(S.str(R.string.after_capturing_tap_clean_to_brush_away_mark))
        scan()
    }

    /** The in-app camera finished with cropped pages in [mode]. */
    internal fun finishCapture(mode: CaptureMode, uris: List<Uri>) {
        capture = null
        pendingScan = mode
        onScanned(uris)
    }

    internal fun onScanned(uris: List<Uri>) {
        val mode = pendingScan
        pendingScan = CaptureMode.SCAN
        scope.launch {
            if (context.app.settings.current().saveToGallery) {
                // This runs on its own after a scan, so say when pages didn't make it.
                val saved = GallerySaver.save(context, uris, repo.defaultTitle())
                if (GallerySaver.supported && saved < uris.size) {
                    context.toast(S.str(R.string.couldn_t_save_x_of_y_pages_to_the_gallery, uris.size - saved, uris.size))
                }
            }
        }
        val into = targetDocument
        val replace = replacing
        targetDocument = null
        replacing = null
        if (into != null) {
            if (replace != null) {
                busy.run(S.str(R.string.replacing_page)) { repo.replacePageImage(replace, uris.first()) }
            } else {
                busy.run(S.str(R.string.adding_pages)) { repo.addUris(into, uris) }
            }
            return
        }
        when (mode) {
            CaptureMode.SCAN, CaptureMode.SCAN_CODE -> {
                val tool = then
                then = null
                busy.run(S.str(R.string.saving_scan)) {
                    val id = file(repo.createFromUris(uris))
                    val doc = if (tool != null) repo.getDocument(id) else null
                    if (doc != null) performAfter(tool!!, doc) else nav.openDocument(id)
                }
            }
            CaptureMode.WHITEBOARD -> busy.run(S.str(R.string.cleaning_up_whiteboard)) {
                open(repo.createFromUris(uris, repo.defaultTitle(S.str(R.string.whiteboard)), filter = ImageFilter.MAGIC_COLOR))
            }
            CaptureMode.TRANSLATE -> busy.run(S.str(R.string.saving_scan)) {
                // Photo Translation: the translation is drawn on the photographed page.
                val id = file(repo.createFromUris(uris, repo.defaultTitle(S.str(R.string.translation))))
                nav.openTranslate(id, 0)
            }
            CaptureMode.EXTRACT_TEXT -> busy.run(S.str(R.string.recognising_text)) {
                val title = repo.defaultTitle(S.str(R.string.text))
                val id = file(repo.createFromUris(uris, title))
                textResult = TextResult(title, repo.ocrDocument(id), translate = false)
            }
            CaptureMode.BOOK -> busy.run(S.str(R.string.splitting_book_pages)) {
                val id = file(repo.createDocument(repo.defaultTitle(S.str(R.string.book))))
                for (uri in uris) {
                    val halves = withContext(Dispatchers.Default) { PageComposer.splitBook(BitmapUtils.decode(context, uri)) }
                    repo.addBitmaps(id, halves)
                }
                nav.openDocument(id)
            }
            CaptureMode.ID_CARD -> busy.run(S.str(R.string.creating_id_card_page)) {
                val sheet = withContext(Dispatchers.Default) {
                    PageComposer.composeIdCard(BitmapUtils.decode(context, uris[0], 1600), uris.getOrNull(1)?.let { BitmapUtils.decode(context, it, 1600) })
                }
                open(repo.createFromBitmaps(listOf(sheet), repo.defaultTitle(S.str(R.string.id_card)), ImageFilter.ORIGINAL))
            }
            CaptureMode.TO_WORD, CaptureMode.TO_EXCEL, CaptureMode.TIMESTAMP -> busy.run(S.str(R.string.saving_scan)) {
                val id = file(repo.createFromUris(uris))
                val doc = repo.getDocument(id) ?: return@run
                val tool = when (mode) {
                    CaptureMode.TO_WORD -> Tool.TO_WORD
                    CaptureMode.TO_EXCEL -> Tool.TO_EXCEL
                    else -> Tool.TIMESTAMP
                }
                if (tool != Tool.TIMESTAMP) nav.openDocument(id)
                performAfter(tool, doc)
            }
            CaptureMode.SMART_ERASE, CaptureMode.SIGN -> busy.run(S.str(R.string.saving_scan)) {
                val id = file(repo.createFromUris(uris))
                nav.openDocument(id)
                val first = repo.getPages(id).firstOrNull() ?: return@run
                if (mode == CaptureMode.SIGN) nav.openSign(first.id) else nav.openErase(first.id)
            }
        }
    }

    internal fun onImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy.run(S.str(R.string.importing_images)) { open(repo.createFromUris(uris, repo.defaultTitle(S.str(R.string.import_text)), rawPhotos = true)) }
    }

    /** [fromPicker]: the app's own file picker (a picker shortcut may be waiting), not a file shared into the app. */
    internal fun onFilesPicked(uris: List<Uri>, fromPicker: Boolean = false) {
        val tool = then.takeIf { fromPicker }
        then = null
        if (uris.isEmpty()) return
        busy.run(S.str(R.string.importing_files)) {
            val (words, notWord) = uris.partition { isDocx(context, it) }
            val (sheets, rest) = notWord.partition { isXlsx(context, it) }
            val (pdfs, images) = rest.partition { context.contentResolver.getType(it) == "application/pdf" }
            var last: Long? = null
            for (w in words) last = file(importDocx(w))
            for (x in sheets) last = file(importXlsx(x))
            for (pdf in pdfs) last = file(PdfImporter.import(context, pdf, repo))
            if (images.isNotEmpty()) last = file(repo.createFromUris(images, repo.defaultTitle(S.str(R.string.import_text)), rawPhotos = true))
            val id = last ?: return@run
            val doc = if (tool != null) repo.getDocument(id) else null
            if (doc != null) performAfter(tool!!, doc) else nav.openDocument(id)
        }
    }

    /** Picker shortcut: scan a new document, then carry on with [tool] on it. */
    internal fun scanFor(tool: Tool) {
        picking = null
        // Tools with a camera mode of their own keep it (it shows the tool's name on the shutter).
        val mode = when (tool) {
            Tool.TO_WORD -> CaptureMode.TO_WORD
            Tool.TO_EXCEL -> CaptureMode.TO_EXCEL
            Tool.TIMESTAMP -> CaptureMode.TIMESTAMP
            Tool.SIGN -> CaptureMode.SIGN
            else -> CaptureMode.SCAN
        }
        startScan(mode, then = tool.takeIf { mode == CaptureMode.SCAN })
    }

    /** Picker shortcut: import a file, then carry on with [tool] on it. */
    internal fun importFor(tool: Tool) {
        picking = null
        then = tool
        launchFiles()
    }

    /**
     * Runs [tool] on [doc] once the task that made the document has finished: the busy overlay takes one
     * task at a time and drops one started while another runs.
     */
    private fun performAfter(tool: Tool, doc: DocumentEntity) {
        scope.launch {
            snapshotFlow { busy.message }.first { it == null }
            perform(tool, listOf(doc))
        }
    }

    /** Whether [tool] can work on [doc] (the picker lists only these). */
    internal fun accepts(tool: Tool, doc: DocumentEntity): Boolean = when {
        doc.isWord -> tool !in SCAN_ONLY && tool != Tool.TO_WORD && tool != Tool.MERGE_FILES
        doc.isExcel -> tool !in SCAN_ONLY && tool != Tool.TO_WORD && tool != Tool.MERGE_FILES
        else -> true
    }

    // ---- compress, page numbers, codes -------------------------------------------------------------

    /** About how large the document's PDF is at [quality] (see [compress]). */
    internal suspend fun compressedSize(doc: DocumentEntity, quality: ExportQuality): Long =
        exporter.estimatePdfBytes(repo.getPages(doc.id), quality, shrinkPdfPages = quality != ExportQuality.HIGH)

    /**
     * Makes the PDF at [quality] and shares it, or saves it where the user picks ([save]). Below original
     * quality, pages imported from a PDF become pictures when that is smaller, so the result never grows.
     */
    internal fun compress(doc: DocumentEntity, quality: ExportQuality, save: Boolean) {
        compressFor = null
        val build: suspend () -> File = {
            val size = context.app.settings.current().pdfPageSize
            exporter.pdf(doc.title, repo.getPages(doc.id), size, quality, shrinkPdfPages = quality != ExportQuality.HIGH)
        }
        if (save) {
            pendingSave = build
            launchSavePdf(Exporter.safeName(doc.title) + ".pdf")
        } else {
            busy.run(S.str(R.string.compressing)) { Sharing.share(context, listOf(build()), "application/pdf", doc.title) }
        }
    }

    internal fun onSaveTarget(uri: Uri?) {
        val build = pendingSave
        pendingSave = null
        if (uri == null || build == null) return
        busy.run(S.str(R.string.compressing)) {
            val pdf = build()
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri) ?: error(S.str(R.string.something_went_wrong))
                out.use { stream -> pdf.inputStream().use { it.copyTo(stream) } }
            }
            context.toast(S.str(R.string.saved_pdf_x, size(pdf.length())))
        }
    }

    internal fun pageNumbers(doc: DocumentEntity, start: Int, place: PageComposer.NumberPlace, style: NumberStyle) {
        numbersFor = null
        busy.run(S.str(R.string.adding_page_numbers)) {
            repo.stampPageNumbers(doc.id, start, place) { number, count -> style.text(number, start + count - 1) }
            nav.openDocument(doc.id)
        }
    }

    /** Shows [value] as a QR code and keeps it in the code history. */
    internal fun createCode(value: String) {
        creatingCode = false
        if (value.isBlank()) return
        codeShown = value
        scope.launch { repo.addQr(value, barcodeFormatName(Barcode.FORMAT_QR_CODE)) }
    }

    internal fun shareCode(value: String) = busy.run(S.str(R.string.preparing)) {
        val file = withContext(Dispatchers.IO) {
            val code = QrCodes.bitmap(value, 1024) ?: error(S.str(R.string.too_long_for_a_qr_code))
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, "qr-code.png").also { f -> f.outputStream().use { code.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }
        Sharing.share(context, listOf(file), "image/png")
    }

    internal fun onPdfPicked(uri: Uri?) {
        uri ?: return
        val purpose = pendingPdf
        busy.run(S.str(R.string.converting_pdf)) {
            val id = file(PdfImporter.import(context, uri, repo))
            val title = repo.getDocument(id)?.title ?: S.str(R.string.pdf)
            val pages = repo.getPages(id)
            when (purpose) {
                PdfPurpose.TO_IMAGES -> Sharing.share(context, exporter.jpegs(title, pages, ExportQuality.HIGH), "image/jpeg", title)
                PdfPurpose.TO_LONG_IMAGE -> Sharing.share(context, listOf(exporter.longImage(title, pages)), "image/jpeg", title)
            }
        }
    }

    private fun scanCode() = startQrScan(context) { barcode ->
        val value = barcode.rawValue ?: barcode.displayValue ?: return@startQrScan
        val format = barcodeFormatName(barcode.format)
        qrResult = value to format
        scope.launch { repo.addQr(value, format) }
    }

    /** Shows recognised text in the text sheet (optionally translating it straight away). */
    fun showText(title: String, text: String, translate: Boolean = false) {
        textResult = TextResult(title, text, translate)
    }

    /** A .docx file opened with the app becomes a Word document in the library. */
    internal suspend fun importDocx(uri: Uri): Long {
        val title = PdfImporter.displayName(context, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: repo.defaultTitle(S.str(R.string.word))
        return try {
            repo.importDocx(uri, title)
        } catch (e: DocxReader.NotWordException) {
            throw IllegalStateException(e.message)
        }
    }

    /** An .xlsx file opened with the app becomes an Excel document in the library. */
    internal suspend fun importXlsx(uri: Uri): Long {
        val title = PdfImporter.displayName(context, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: repo.defaultTitle(S.str(R.string.excel))
        return try {
            repo.importXlsx(uri, title)
        } catch (e: XlsxReader.NotExcelException) {
            throw IllegalStateException(e.message)
        }
    }

    /** Share sheet "Share as Word": converts the scan and shares the .docx without keeping a copy. */
    fun shareAsWord(doc: DocumentEntity) = runOnUnlocked(doc) {
        busy.run(S.str(R.string.converting_to_word)) {
            val word = PageToWord.convertPages(context, repo.getPages(doc.id), context.app.settings.current().ocrScript)
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                File(dir, Exporter.safeName(doc.title) + ".docx").also { DocxWriter.write(word, it) }
            }
            Sharing.share(context, listOf(file), DOCX_MIME, doc.title)
        }
    }

    /** Shares a scan as PDF, a Word document as its .docx, or an Excel document as its .xlsx. */
    fun sharePdf(doc: DocumentEntity) = runOnUnlocked(doc) {
        val filePath = doc.wordPath ?: doc.excelPath
        if (filePath != null) {
            busy.run(S.str(R.string.preparing)) {
                val ext = if (doc.isWord) ".docx" else ".xlsx"
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(filePath).copyTo(File(dir, Exporter.safeName(doc.title) + ext), overwrite = true)
                }
                Sharing.share(context, listOf(file), if (doc.isWord) DOCX_MIME else XlsxWriter.MIME, doc.title)
            }
            return@runOnUnlocked
        }
        busy.run(S.str(R.string.creating_pdf)) {
            val s = context.app.settings.current()
            Sharing.share(context, listOf(exporter.pdf(doc.title, repo.getPages(doc.id), s.pdfPageSize, s.exportQuality)), "application/pdf", doc.title)
        }
    }

    /** Opens a document, asking for fingerprint / PIN first when it is locked. */
    fun openDocument(doc: DocumentEntity) = runOnUnlocked(doc) { nav.openDocument(doc.id) }

    private fun runOnUnlocked(doc: DocumentEntity, action: () -> Unit) {
        if (doc.locked) {
            AppLock.authenticate(context.findActivity(), S.str(R.string.open_x, doc.title)) { ok -> if (ok) action() }
        } else {
            action()
        }
    }

    internal fun merge(ids: List<Long>, title: String) {
        mergeIds = null
        busy.run(S.str(R.string.merging)) { open(repo.merge(ids, title, deleteSources = false)) }
    }

    internal fun watermark(documentId: Long, text: String) {
        watermarkFor = null
        if (text.isBlank()) return
        busy.run(S.str(R.string.adding_watermark)) {
            repo.getPages(documentId).forEach { page -> repo.bakeEdit(page) { PageComposer.watermark(it, text) } }
            nav.openDocument(documentId)
        }
    }

    /** Files a new document into [targetFolderId] and returns its id. */
    private suspend fun file(id: Long): Long {
        targetFolderId?.let { repo.moveToFolder(listOf(id), it) }
        return id
    }

    private suspend fun open(id: Long) = nav.openDocument(file(id))

    private fun size(bytes: Long): String =
        if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${bytes / 1000} KB"
}

/** Tools that work on scanned pages, not on Word or Excel documents. */
private val SCAN_ONLY = setOf(
    Tool.TIMESTAMP, Tool.TO_EXCEL, Tool.SIGN, Tool.ADD_WATERMARK, Tool.EXTRACT_PAGES, Tool.REORDER_PAGES, Tool.PAGE_NUMBERS,
    Tool.COMPRESS, Tool.PRINT,
)

/** How a page number is written. */
enum class NumberStyle(val text: (number: Int, last: Int) -> String) {
    PLAIN({ n, _ -> "$n" }),
    OF_LAST({ n, last -> "$n / $last" }),
    DASHES({ n, _ -> "- $n -" }),
}

/** A Word (.docx) file, by type or, when the provider gives no type, by name. */
fun isDocx(context: Context, uri: Uri): Boolean {
    val type = context.contentResolver.getType(uri)
    if (type == DOCX_MIME) return true
    if (type != null && type != "application/octet-stream" && type != "application/zip") return false
    val name = PdfImporter.displayName(context, uri) ?: uri.lastPathSegment ?: return false
    return name.endsWith(".docx", ignoreCase = true)
}

/** An Excel (.xlsx) file, by type or, when the provider gives no type, by name. */
fun isXlsx(context: Context, uri: Uri): Boolean {
    val type = context.contentResolver.getType(uri)
    if (type == XlsxWriter.MIME) return true
    if (type != null && type != "application/octet-stream" && type != "application/zip") return false
    val name = PdfImporter.displayName(context, uri) ?: uri.lastPathSegment ?: return false
    return name.endsWith(".xlsx", ignoreCase = true)
}

@Composable
fun rememberToolRunner(nav: ToolNavigator): ToolRunner {
    val context = LocalContext.current
    val busy = rememberBusy()
    val scope = rememberCoroutineScope()
    val runner = remember { ToolRunner(context, busy, scope) }
    runner.nav = nav
    runner.scan = rememberDocumentScanner { runner.onScanned(it) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { runner.onImagesPicked(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { runner.onFilesPicked(it, fromPicker = true) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { runner.onSaveTarget(it) }
    runner.launchSavePdf = { savePdf.launch(it) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { runner.onPdfPicked(it) }
    runner.launchImages = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    runner.launchFiles = { files.launch(arrayOf("application/pdf", "image/*", DOCX_MIME, XlsxWriter.MIME)) }
    runner.launchPdf = { pdf.launch(arrayOf("application/pdf")) }
    return runner
}

/** Progress overlay, document picker, results and prompts for [runner]. Draw it last, above the screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolRunnerHost(runner: ToolRunner) {
    runner.capture?.let { mode ->
        CaptureScreen(
            initialMode = mode,
            onFinish = { m, uris -> runner.finishCapture(m, uris) },
            onCancel = { runner.capture = null },
            onFallback = { runner.useGoogleScanner(it) },
            onAllFeatures = { runner.capture = null; runner.nav.openToolsTab() },
            onImportImages = { runner.capture = null; runner.launchImages() },
            onImportFiles = { runner.capture = null; runner.launchFiles() },
            onScanCode = { runner.capture = null; runner.run(Tool.SCAN_CODE) },
            singleShot = runner.captureSingle,
        )
    }
    runner.picking?.let { tool ->
        DocumentPickerDialog(
            title = if (tool == Tool.MERGE_FILES) S.str(R.string.choose_documents_to_merge_in_order) else S.str(R.string.x_choose_a_document, tool.label),
            multiple = tool == Tool.MERGE_FILES,
            accepts = { runner.accepts(tool, it) },
            onDismiss = { runner.picking = null },
            onPicked = { runner.runOn(tool, it) },
            // Merging needs documents that are already there; Lock a new scan makes no sense either.
            onScanNew = if (tool == Tool.MERGE_FILES || tool == Tool.LOCK) null else ({ runner.scanFor(tool) }),
            onImport = if (tool == Tool.MERGE_FILES || tool == Tool.LOCK) null else ({ runner.importFor(tool) }),
        )
    }
    runner.textResult?.let { result ->
        OcrTextSheet(
            title = result.title,
            text = result.text,
            onDismiss = { runner.textResult = null },
            translateOnOpen = result.translate,
        )
    }
    runner.qrResult?.let { (value, format) -> QrResultDialog(value, format) { runner.qrResult = null } }
    runner.compressFor?.let { doc -> CompressDialog(doc, runner) }
    runner.numbersFor?.let { doc -> PageNumbersDialog(doc, runner) }
    if (runner.creatingCode) CreateCodeDialog(runner)
    runner.codeShown?.let { value -> CodeImageDialog(value, runner) }
    runner.watermarkFor?.let { id ->
        TextInputDialog(S.str(R.string.add_watermark_2), "", S.str(R.string.watermark_text), S.str(R.string.apply), onDismiss = { runner.watermarkFor = null }) { runner.watermark(id, it) }
    }
    runner.mergeIds?.let { ids ->
        val context = LocalContext.current
        TextInputDialog(
            S.str(R.string.merge_x_documents, ids.size),
            context.app.repository.defaultTitle(S.str(R.string.merged)),
            S.str(R.string.new_document_name),
            S.str(R.string.merge),
            onDismiss = { runner.mergeIds = null },
        ) { runner.merge(ids, it) }
    }
    if (runner.showPdfTools) {
        ModalBottomSheet(
            onDismissRequest = { runner.showPdfTools = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(S.str(R.string.pdf_tools), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                Tool.pdf.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { tool -> ToolButton(tool.label, tool.icon, tool.tint, { runner.run(tool) }, Modifier.weight(1f)) }
                        repeat(4 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    BusyOverlay(runner.busy)
}
