package com.paperlens.app.ui.tools

import com.paperlens.app.S
import com.paperlens.app.R

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.app
import com.paperlens.app.data.DocumentEntity
import com.paperlens.app.data.PageEntity
import com.paperlens.app.data.ExportQuality
import com.paperlens.app.export.Exporter
import com.paperlens.app.export.GallerySaver
import com.paperlens.app.export.PdfImporter
import com.paperlens.app.export.Sharing
import com.paperlens.app.export.XlsxWriter
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.ml.TableOcr
import com.paperlens.app.ui.word.DOCX_MIME
import com.paperlens.app.word.DocxReader
import com.paperlens.app.word.DocxWriter
import com.paperlens.app.word.PageToWord
import com.paperlens.app.security.AppLock
import com.paperlens.app.ui.capture.CaptureMode
import com.paperlens.app.ui.capture.CaptureScreen
import com.paperlens.app.ui.components.BusyController
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.DocumentThumbnail
import com.paperlens.app.ui.components.OcrTextSheet
import com.paperlens.app.ui.components.QrResultDialog
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.ToolButton
import com.paperlens.app.ui.components.barcodeFormatName
import com.paperlens.app.ui.components.findActivity
import com.paperlens.app.ui.components.formatDate
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.rememberDocumentScanner
import com.paperlens.app.ui.components.startQrScan
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** Non-null while the in-app camera is open, in this mode. */
    internal var capture by mutableStateOf<CaptureMode?>(null)

    internal var picking by mutableStateOf<Tool?>(null)
    internal var textResult by mutableStateOf<TextResult?>(null)
    internal var qrResult by mutableStateOf<Pair<String, String>?>(null)
    internal var watermarkFor by mutableStateOf<Long?>(null)
    internal var mergeIds by mutableStateOf<List<Long>?>(null)
    internal var showPdfTools by mutableStateOf(false)

    fun run(tool: Tool) {
        showPdfTools = false
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
            Tool.CODE_HISTORY -> nav.openCodeHistory()
            Tool.TIMESTAMP, Tool.TO_WORD, Tool.TO_EXCEL, Tool.SIGN, Tool.ADD_WATERMARK, Tool.MERGE_FILES,
            Tool.EXTRACT_PAGES, Tool.REORDER_PAGES, Tool.LOCK, Tool.COMPRESS, Tool.PRINT,
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
            Tool.TO_EXCEL -> busy.run(S.str(R.string.recognising_tables)) {
                val script = context.app.settings.current().ocrScript
                val sheets = repo.getPages(doc.id).mapIndexed { i, page ->
                    S.str(R.string.page_x, i + 1) to TableOcr.recognizeRows(context, File(page.imagePath), script)
                }
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    XlsxWriter.write(File(dir, Exporter.safeName(doc.title) + ".xlsx"), sheets)
                }
                Sharing.share(context, listOf(file), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", doc.title)
            }
            Tool.SIGN -> {
                context.toast(S.str(R.string.swipe_to_the_page_you_want_to_sign_then_tap))
                nav.openPage(doc.id, 0)
            }
            Tool.ADD_WATERMARK -> watermarkFor = doc.id
            Tool.MERGE_FILES -> {
                val scans = documents.filterNot { it.isWord }
                if (scans.size < documents.size) context.toast(S.str(R.string.word_documents_are_left_out_of_a_merge))
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
            Tool.COMPRESS -> busy.run(S.str(R.string.compressing)) {
                val pages = repo.getPages(doc.id)
                val before = pages.sumOf { File(it.imagePath).length() }
                val pdf = exporter.pdf(doc.title, pages, context.app.settings.current().pdfPageSize, ExportQuality.LOW)
                context.toast(S.str(R.string.compressed_x_x, size(before), size(pdf.length())))
                Sharing.share(context, listOf(pdf), "application/pdf", doc.title)
            }
            Tool.PRINT -> busy.run(S.str(R.string.preparing_print)) {
                val s = context.app.settings.current()
                Sharing.printPdf(context, exporter.pdf(doc.title, repo.getPages(doc.id), s.pdfPageSize, ExportQuality.HIGH), doc.title)
            }
            else -> run(tool)
        }
    }

    // ---- launcher results ------------------------------------------------------------------------

    /** Opens the in-app camera in [mode], or the Google scanner when that is chosen in Settings → Scan. */
    private fun startScan(mode: CaptureMode, into: Long? = null, replace: PageEntity? = null) {
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
            if (context.app.settings.current().saveToGallery) GallerySaver.save(context, uris, repo.defaultTitle())
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
            CaptureMode.SCAN, CaptureMode.SCAN_CODE -> busy.run(S.str(R.string.saving_scan)) { open(repo.createFromUris(uris)) }
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
                perform(tool, listOf(doc))
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

    internal fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy.run(S.str(R.string.importing_files)) {
            val (words, rest) = uris.partition { isDocx(context, it) }
            val (pdfs, images) = rest.partition { context.contentResolver.getType(it) == "application/pdf" }
            var last: Long? = null
            for (w in words) last = file(importDocx(w))
            for (pdf in pdfs) last = file(PdfImporter.import(context, pdf, repo))
            if (images.isNotEmpty()) last = file(repo.createFromUris(images, repo.defaultTitle(S.str(R.string.import_text)), rawPhotos = true))
            last?.let { nav.openDocument(it) }
        }
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

    /** Shares a scan as PDF, or a Word document as its .docx. */
    fun sharePdf(doc: DocumentEntity) = runOnUnlocked(doc) {
        val wordPath = doc.wordPath
        if (wordPath != null) {
            busy.run(S.str(R.string.preparing)) {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(wordPath).copyTo(File(dir, Exporter.safeName(doc.title) + ".docx"), overwrite = true)
                }
                Sharing.share(context, listOf(file), DOCX_MIME, doc.title)
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

/** Tools that work on scanned pages, not on Word documents. */
private val SCAN_ONLY = setOf(
    Tool.TIMESTAMP, Tool.TO_EXCEL, Tool.SIGN, Tool.ADD_WATERMARK, Tool.EXTRACT_PAGES, Tool.REORDER_PAGES, Tool.COMPRESS, Tool.PRINT,
)

/** A Word (.docx) file, by type or, when the provider gives no type, by name. */
fun isDocx(context: Context, uri: Uri): Boolean {
    val type = context.contentResolver.getType(uri)
    if (type == DOCX_MIME) return true
    if (type != null && type != "application/octet-stream" && type != "application/zip") return false
    val name = PdfImporter.displayName(context, uri) ?: uri.lastPathSegment ?: return false
    return name.endsWith(".docx", ignoreCase = true)
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
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { runner.onFilesPicked(it) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { runner.onPdfPicked(it) }
    runner.launchImages = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    runner.launchFiles = { files.launch(arrayOf("application/pdf", "image/*", DOCX_MIME)) }
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
            onDismiss = { runner.picking = null },
            onPicked = { runner.runOn(tool, it) },
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

@Composable
private fun DocumentPickerDialog(
    title: String,
    multiple: Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<DocumentEntity>) -> Unit,
) {
    val repo = LocalContext.current.app.repository
    val docs by remember { repo.observeDocuments("", null) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var chosen by remember { mutableStateOf(listOf<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (docs.isEmpty()) {
                Text(S.str(R.string.no_documents_yet_scan_or_import_something_fi))
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(docs, key = { it.document.id }) { summary ->
                        val id = summary.document.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (multiple) chosen = if (id in chosen) chosen - id else chosen + id
                                    else onPicked(listOf(summary.document))
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DocumentThumbnail(summary, Modifier.width(40.dp).height(52.dp))
                            Column(Modifier.weight(1f)) {
                                Text(summary.document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    S.str(R.string.x_x_p, formatDate(summary.document.updatedAt), summary.pageCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (multiple) {
                                val order = chosen.indexOf(id)
                                if (order >= 0) Text("${order + 1}", color = MaterialTheme.colorScheme.primary)
                                Checkbox(checked = order >= 0, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) {
                TextButton(
                    enabled = chosen.size >= 2,
                    onClick = { onPicked(chosen.mapNotNull { id -> docs.firstOrNull { it.document.id == id }?.document }) },
                ) { Text(S.str(R.string.merge_x, chosen.size)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) } },
    )
}
