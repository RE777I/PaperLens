package com.paperlens.app.data

import com.paperlens.app.S
import com.paperlens.app.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.DocumentDetector
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.ImageProcessor
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.imaging.PageOutline
import com.paperlens.app.ml.Ocr
import com.paperlens.app.ml.OcrScript
import com.paperlens.app.ml.SearchableText
import com.paperlens.app.word.DocxReader
import com.paperlens.app.word.DocxWriter
import com.paperlens.app.word.WordDocument
import com.paperlens.app.word.WordThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Owns documents on disk (filesDir/documents/<id>/) and in the database.
 * Every page keeps its untouched scan ("original") so filters and rotation stay non-destructive.
 */
class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val settings: SettingsStore,
) {
    private val root = File(context.filesDir, "documents")

    fun observeDocuments(query: String, tag: String?): Flow<List<DocumentSummary>> = dao.observeDocuments(query, tag)
    fun observeTags(): Flow<List<String>> = dao.observeTags()
    fun observeDocument(id: Long): Flow<DocumentEntity?> = dao.observeDocument(id)
    fun observePages(documentId: Long): Flow<List<PageEntity>> = dao.observePages(documentId)
    fun observePage(id: Long): Flow<PageEntity?> = dao.observePage(id)
    fun observeQrHistory(): Flow<List<QrHistoryEntity>> = dao.observeQrHistory()

    suspend fun getDocument(id: Long) = dao.getDocument(id)
    suspend fun getPages(documentId: Long) = dao.getPages(documentId)
    suspend fun getPage(id: Long) = dao.getPage(id)
    suspend fun getAllDocuments() = dao.getAllDocuments()

    fun defaultTitle(prefix: String = S.str(R.string.scan)): String =
        "$prefix " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())

    // ---- creation / import ----------------------------------------------------------------------

    suspend fun createDocument(title: String = defaultTitle()): Long {
        val now = System.currentTimeMillis()
        return dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now))
    }

    /**
     * Creates a document from scanner / gallery URIs. Returns the new document id.
     * [rawPhotos] marks images that did not pass through the document scanner (gallery, shared files),
     * which get automatic page detection and perspective correction when enabled in settings.
     */
    suspend fun createFromUris(
        uris: List<Uri>,
        title: String = defaultTitle(),
        rawPhotos: Boolean = false,
        filter: ImageFilter? = null,
    ): Long {
        val id = createDocument(title)
        addUris(id, uris, rawPhotos, filter)
        return id
    }

    suspend fun createFromBitmaps(bitmaps: List<Bitmap>, title: String, filter: ImageFilter? = null): Long {
        val id = createDocument(title)
        addBitmaps(id, bitmaps, filter)
        return id
    }

    suspend fun addUris(
        documentId: Long,
        uris: List<Uri>,
        rawPhotos: Boolean = false,
        filter: ImageFilter? = null,
    ) = withContext(Dispatchers.IO) {
        val s = settings.current()
        for (uri in uris) {
            val bitmap = BitmapUtils.decode(context, uri)
            val captured = CaptureSidecar.read(uri)
            when {
                // In-app camera: already cropped; the whole photo and corners come alongside.
                captured != null -> addBitmapInternal(documentId, bitmap, filter ?: s.defaultFilter, captured.first, captured.second)
                rawPhotos && s.autoCrop -> {
                    val outline = DocumentDetector.detectOutline(bitmap)
                    if (outline == null) {
                        addBitmapInternal(documentId, bitmap, filter ?: s.defaultFilter)
                    } else {
                        addBitmapInternal(documentId, DocumentDetector.warp(bitmap, outline), filter ?: s.defaultFilter, bitmap, outline)
                    }
                }
                else -> addBitmapInternal(documentId, bitmap, filter ?: s.defaultFilter)
            }
        }
        afterPagesAdded(documentId)
    }

    suspend fun addBitmaps(documentId: Long, bitmaps: List<Bitmap>, filter: ImageFilter? = null) =
        withContext(Dispatchers.IO) {
            val f = filter ?: settings.current().defaultFilter
            bitmaps.forEach { addBitmapInternal(documentId, BitmapUtils.scaleDown(it, BitmapUtils.MAX_PAGE_DIMENSION), f) }
            afterPagesAdded(documentId)
        }

    /**
     * A page imported from a PDF: its picture, the page as a one-page PDF ([pdf] is moved into the
     * document) and the text the PDF holds (null when it has none, e.g. a scanned PDF).
     */
    suspend fun addPdfPage(documentId: Long, bitmap: Bitmap, pdf: File?, text: String?) = withContext(Dispatchers.IO) {
        addBitmapInternal(documentId, bitmap, ImageFilter.ORIGINAL, pdf = pdf, text = text)
        afterPagesAdded(documentId)
    }

    /**
     * [source] is the whole photo the page was cut from (a Bitmap or a saved File) and [corners] where
     * in it the page lies; both are kept so the crop can be adjusted later.
     */
    private suspend fun addBitmapInternal(
        documentId: Long,
        bitmap: Bitmap,
        filter: ImageFilter,
        source: Any? = null,
        outline: PageOutline? = null,
        pdf: File? = null,
        text: String? = null,
    ) {
        val dir = documentDir(documentId)
        val original = BitmapUtils.saveJpeg(bitmap, File(dir, "orig_${UUID.randomUUID()}.jpg"), 95)
        var savedOutline = outline
        val sourceFile = when (source) {
            is Bitmap -> {
                val kept = BitmapUtils.scaleDown(source, BitmapUtils.MAX_PAGE_DIMENSION)
                savedOutline = outline?.scaled(kept.width.toFloat() / source.width)
                BitmapUtils.saveJpeg(kept, File(dir, "src_${UUID.randomUUID()}.jpg"), 92)
            }
            is File -> source.copyTo(File(dir, "src_${UUID.randomUUID()}.jpg"))
            else -> null
        }
        val processed = File(dir, "page_${UUID.randomUUID()}.jpg")
        BitmapUtils.saveJpeg(ImageProcessor.render(bitmap, filter, 0), processed)
        val keptPdf = pdf?.let { p -> File(dir, "pdf_${UUID.randomUUID()}.pdf").also { p.copyTo(it); p.delete() } }
        val position = dao.getPages(documentId).size
        val page = PageEntity(
            documentId = documentId,
            position = position,
            originalPath = original.path,
            imagePath = processed.path,
            filter = filter.name,
            ocrText = text,
            sourcePath = sourceFile?.path,
            corners = if (sourceFile != null) savedOutline?.encode() else null,
            pdfPath = keptPdf?.path,
        )
        val id = dao.insertPage(page)
        // Adding pages is not an edit: keep them even if the edits being previewed are discarded.
        session?.takeIf { it.documentId == documentId }?.let { it.pages = it.pages + page.copy(id = id); saveSession() }
    }

    private suspend fun afterPagesAdded(documentId: Long) {
        dao.touchDocument(documentId, System.currentTimeMillis())
        val s = settings.current()
        if (s.autoOcr) {
            dao.getPages(documentId).filter { it.ocrText == null }.forEach { runCatching { ocrPage(it, s.ocrScript) } }
        }
    }

    // ---- page editing ---------------------------------------------------------------------------

    /** Re-renders a page from its original with a new filter and/or rotation. */
    suspend fun editPage(page: PageEntity, filter: ImageFilter, rotation: Int): PageEntity = withContext(Dispatchers.Default) {
        startEdit(page.documentId)
        val original = BitmapUtils.decode(File(page.originalPath))
        val rendered = ImageProcessor.render(original, filter, rotation)
        // New file name each time so image caches never show a stale render.
        val file = File(File(page.imagePath).parentFile, "page_${UUID.randomUUID()}.jpg")
        BitmapUtils.saveJpeg(rendered, file)
        dispose(page.documentId, page.imagePath)
        val updated = page.copy(imagePath = file.path, filter = filter.name, rotation = ((rotation % 360) + 360) % 360)
        dao.updatePage(updated)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
        updated
    }

    suspend fun applyFilterToAll(documentId: Long, filter: ImageFilter) {
        dao.getPages(documentId).forEach { editPage(it, filter, it.rotation) }
    }

    /**
     * Bakes a pixel edit (signature, annotation, watermark) into the page.
     * The edited image becomes the new original so later filter changes keep the edit.
     */
    suspend fun bakeEdit(page: PageEntity, edit: (Bitmap) -> Bitmap): PageEntity = withContext(Dispatchers.Default) {
        startEdit(page.documentId)
        val current = BitmapUtils.decode(File(page.imagePath))
        val edited = edit(current)
        val dir = File(page.imagePath).parentFile!!
        val newOriginal = BitmapUtils.saveJpeg(edited, File(dir, "orig_${UUID.randomUUID()}.jpg"), 95)
        val newImage = BitmapUtils.saveJpeg(edited, File(dir, "page_${UUID.randomUUID()}.jpg"))
        // The PDF can't show the added marks, so the page is a picture from now on.
        dispose(page.documentId, page.originalPath, page.imagePath, page.pdfPath)
        // The uncropped photo is kept so the crop can still be adjusted; doing so starts again from it.
        val updated = page.copy(
            originalPath = newOriginal.path,
            imagePath = newImage.path,
            filter = ImageFilter.ORIGINAL.name,
            rotation = 0,
            markup = page.sourcePath != null,
            pdfPath = null,
        )
        dao.updatePage(updated)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
        updated
    }

    /** Reads a page's text, keeping the word positions too so Find Text can highlight it straight away. */
    suspend fun ocrPage(page: PageEntity, script: OcrScript? = null): String {
        val reading = SearchableText.read(context, File(page.imagePath), script ?: settings.current().ocrScript)
        dao.updatePage(page.copy(ocrText = reading.plain, ocrLayout = reading.layout))
        return reading.plain
    }

    /** Runs OCR on every page lacking text; returns the full document text. */
    suspend fun ocrDocument(documentId: Long, force: Boolean = false): String {
        val script = settings.current().ocrScript
        val texts = mutableListOf<String>()
        for (page in dao.getPages(documentId)) {
            texts += if (page.ocrText == null || force) ocrPage(page, script) else page.ocrText
        }
        return texts.joinToString("\n\n")
    }

    /**
     * Keeps text that was recognised elsewhere (Find Text) so the page is not read again; [layout] is the
     * word positions, kept even when the text was already stored. Not an edit.
     */
    suspend fun storeOcrText(page: PageEntity, text: String, layout: String? = null) {
        dao.getPage(page.id)?.let { dao.updatePage(it.copy(ocrText = it.ocrText ?: text, ocrLayout = layout ?: it.ocrLayout)) }
    }

    suspend fun setOcrText(page: PageEntity, text: String) {
        startEdit(page.documentId)
        // Typed text no longer matches the recognised word positions.
        dao.updatePage(page.copy(ocrText = text, ocrLayout = null))
    }

    /** Forgets recognised text, e.g. after the OCR language changes, so it is read again in the new script. */
    suspend fun clearOcrText() = dao.clearOcrText()

    suspend fun deletePage(page: PageEntity) {
        startEdit(page.documentId)
        dao.deletePage(page)
        dispose(page.documentId, page.originalPath, page.imagePath, page.sourcePath, page.pdfPath)
        reindex(page.documentId)
    }

    // ---- several pages at once (long-press selection) -------------------------------------------

    suspend fun deletePages(documentId: Long, pageIds: Set<Long>) {
        startEdit(documentId)
        dao.getPages(documentId).filter { it.id in pageIds }.forEach { page ->
            dao.deletePage(page)
            dispose(page.documentId, page.originalPath, page.imagePath, page.sourcePath, page.pdfPath)
        }
        reindex(documentId)
    }

    suspend fun rotatePages(documentId: Long, pageIds: Set<Long>, degrees: Int) {
        dao.getPages(documentId).filter { it.id in pageIds }.forEach { editPage(it, it.imageFilter, it.rotation + degrees) }
    }

    /**
     * Moves the chosen pages (keeping their order) so they come right after page [afterPage] of the
     * current order (1-based; 0 = to the beginning, page count = to the end).
     */
    suspend fun movePages(documentId: Long, pageIds: Set<Long>, afterPage: Int) {
        startEdit(documentId)
        val pages = dao.getPages(documentId)
        val moving = pages.filter { it.id in pageIds }
        val rest = pages.filter { it.id !in pageIds }.toMutableList()
        val insertAt = pages.take(afterPage.coerceIn(0, pages.size)).count { it.id !in pageIds }
        rest.addAll(insertAt, moving)
        dao.updatePages(rest.mapIndexed { i, p -> p.copy(position = i) })
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    /** Moves a page from index [from] to [to] within its document. */
    suspend fun movePage(documentId: Long, from: Int, to: Int) {
        startEdit(documentId)
        val pages = dao.getPages(documentId).toMutableList()
        if (from !in pages.indices || to !in pages.indices) return
        val page = pages.removeAt(from)
        pages.add(to, page)
        dao.updatePages(pages.mapIndexed { i, p -> p.copy(position = i) })
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    private suspend fun reindex(documentId: Long) {
        dao.updatePages(dao.getPages(documentId).mapIndexed { i, p -> p.copy(position = i) })
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    // ---- previewing edits: changes to the open document wait for Save or Discard ----------------

    private class EditSession(val documentId: Long, var pages: List<PageEntity>)

    @Volatile private var session: EditSession? = loadSession()

    /** The document shown on screen; only its edits are previewed (tools run from elsewhere save at once). */
    @Volatile private var openDocument: Long? = null

    private val editing = MutableStateFlow(session?.documentId)

    /** The document whose edits are being previewed (not saved yet), or null. */
    val editingDocument: StateFlow<Long?> = editing

    /** Called when a document is opened; an unsaved preview of another document is saved first. */
    suspend fun openDocument(documentId: Long) {
        session?.takeIf { it.documentId != documentId }?.let { commitEdit(it.documentId) }
        openDocument = documentId
    }

    fun closeDocument(documentId: Long) {
        if (openDocument == documentId) openDocument = null
    }

    /** Before the first change to the open document, remembers its pages as they are now. */
    private suspend fun startEdit(documentId: Long) {
        if (documentId != openDocument || session?.documentId == documentId) return
        session = EditSession(documentId, dao.getPages(documentId))
        saveSession()
        editing.value = documentId
    }

    fun hasUnsavedEdits(documentId: Long): Boolean = session?.documentId == documentId

    /** Keeps the edits and deletes the files they replaced. */
    suspend fun commitEdit(documentId: Long) = withContext(Dispatchers.IO) {
        val s = session?.takeIf { it.documentId == documentId } ?: return@withContext
        endSession()
        val kept = filesOf(dao.getPages(documentId))
        (filesOf(s.pages) - kept).forEach { File(it).delete() }
    }

    /** Puts the pages back as they were before the edits and deletes the files the edits made. */
    suspend fun discardEdit(documentId: Long) = withContext(Dispatchers.IO) {
        val s = session?.takeIf { it.documentId == documentId } ?: return@withContext
        endSession()
        val edited = dao.getPages(documentId)
        dao.deletePagesOf(documentId)
        dao.insertPages(s.pages)
        (filesOf(edited) - filesOf(s.pages)).forEach { File(it).delete() }
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    private fun endSession() {
        session = null
        sessionFile.delete()
        editing.value = null
    }

    // The preview survives the app being closed: the "before" pages are kept in a small file.
    private val sessionFile get() = File(context.filesDir, "edit_session.json")

    private fun saveSession() {
        val s = session ?: return
        val pages = JSONArray()
        s.pages.forEach { p ->
            pages.put(
                JSONObject()
                    .put("id", p.id).put("documentId", p.documentId).put("position", p.position)
                    .put("originalPath", p.originalPath).put("imagePath", p.imagePath)
                    .put("filter", p.filter).put("rotation", p.rotation)
                    .put("ocrText", p.ocrText ?: JSONObject.NULL)
                    .put("sourcePath", p.sourcePath ?: JSONObject.NULL)
                    .put("corners", p.corners ?: JSONObject.NULL)
                    .put("markup", p.markup)
                    .put("pdfPath", p.pdfPath ?: JSONObject.NULL)
                    .put("ocrLayout", p.ocrLayout ?: JSONObject.NULL),
            )
        }
        runCatching { sessionFile.writeText(JSONObject().put("documentId", s.documentId).put("pages", pages).toString()) }
    }

    private fun loadSession(): EditSession? = runCatching {
        val file = File(context.filesDir, "edit_session.json")
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val arr = json.getJSONArray("pages")
        fun JSONObject.optText(key: String) = if (isNull(key)) null else getString(key)
        val pages = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            PageEntity(
                id = o.getLong("id"),
                documentId = o.getLong("documentId"),
                position = o.getInt("position"),
                originalPath = o.getString("originalPath"),
                imagePath = o.getString("imagePath"),
                filter = o.getString("filter"),
                rotation = o.getInt("rotation"),
                ocrText = o.optText("ocrText"),
                sourcePath = o.optText("sourcePath"),
                corners = o.optText("corners"),
                markup = o.optBoolean("markup", false),
                pdfPath = if (o.has("pdfPath")) o.optText("pdfPath") else null,
                ocrLayout = if (o.has("ocrLayout")) o.optText("ocrLayout") else null,
            )
        }
        EditSession(json.getLong("documentId"), pages)
    }.getOrNull()

    private fun filesOf(pages: List<PageEntity>): Set<String> =
        pages.flatMap { listOfNotNull(it.originalPath, it.imagePath, it.sourcePath, it.pdfPath) }.toSet()

    /** Deletes replaced files, unless the document is being edited (then they wait for save/discard). */
    private fun dispose(documentId: Long, vararg paths: String?) {
        if (session?.documentId == documentId) return
        paths.filterNotNull().forEach { File(it).delete() }
    }

    private fun copySource(page: PageEntity, dir: File): String? =
        page.sourcePath?.let(::File)?.takeIf { it.exists() }?.copyTo(File(dir, "src_${UUID.randomUUID()}.jpg"))?.path

    private fun copyPdf(page: PageEntity, dir: File): String? =
        page.pdfPath?.let(::File)?.takeIf { it.exists() }?.copyTo(File(dir, "pdf_${UUID.randomUUID()}.pdf"))?.path

    // ---- document operations --------------------------------------------------------------------

    suspend fun rename(documentId: Long, title: String) {
        val doc = dao.getDocument(documentId) ?: return
        dao.updateDocument(doc.copy(title = title.trim().ifEmpty { doc.title }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setTag(documentId: Long, tag: String?) {
        val doc = dao.getDocument(documentId) ?: return
        dao.updateDocument(doc.copy(tag = tag?.trim()?.ifEmpty { null }))
    }

    suspend fun setLocked(documentId: Long, locked: Boolean) {
        val doc = dao.getDocument(documentId) ?: return
        dao.updateDocument(doc.copy(locked = locked))
    }

    suspend fun deleteDocument(documentId: Long) = withContext(Dispatchers.IO) {
        if (session?.documentId == documentId) endSession()
        dao.deleteDocument(documentId)
        File(root, documentId.toString()).deleteRecursively()
    }

    /** Copies every page of [documentIds] (in order) into a new document. */
    suspend fun merge(documentIds: List<Long>, title: String, deleteSources: Boolean): Long = withContext(Dispatchers.IO) {
        val newId = createDocument(title)
        val dir = documentDir(newId)
        var position = 0
        for (sourceId in documentIds) {
            for (page in dao.getPages(sourceId)) {
                val original = File(page.originalPath).copyTo(File(dir, "orig_${UUID.randomUUID()}.jpg"))
                val image = File(page.imagePath).copyTo(File(dir, "page_${UUID.randomUUID()}.jpg"))
                dao.insertPage(
                    page.copy(
                        id = 0,
                        documentId = newId,
                        position = position++,
                        originalPath = original.path,
                        imagePath = image.path,
                        sourcePath = copySource(page, dir),
                        pdfPath = copyPdf(page, dir),
                    ),
                )
            }
        }
        if (deleteSources) documentIds.forEach { deleteDocument(it) }
        newId
    }

    /** Moves a page to another document (appended at the end). */
    suspend fun movePageToDocument(page: PageEntity, targetDocumentId: Long) {
        startEdit(page.documentId)
        val position = dao.getPages(targetDocumentId).size
        dao.updatePage(page.copy(documentId = targetDocumentId, position = position))
        reindex(page.documentId)
        dao.touchDocument(targetDocumentId, System.currentTimeMillis())
    }

    /** Retake: replaces a page's scan with a new image, keeping its position and filter. */
    suspend fun replacePageImage(page: PageEntity, uri: Uri): PageEntity = withContext(Dispatchers.IO) {
        startEdit(page.documentId)
        val bitmap = BitmapUtils.decode(context, uri)
        val dir = File(page.originalPath).parentFile!!
        val original = BitmapUtils.saveJpeg(bitmap, File(dir, "orig_${UUID.randomUUID()}.jpg"), 95)
        val captured = CaptureSidecar.read(uri)
        val keptPhoto = captured?.first?.copyTo(File(dir, "src_${UUID.randomUUID()}.jpg"))
        val image = BitmapUtils.saveJpeg(ImageProcessor.render(bitmap, page.imageFilter, 0), File(dir, "page_${UUID.randomUUID()}.jpg"))
        dispose(page.documentId, page.originalPath, page.imagePath, page.sourcePath, page.pdfPath)
        val updated = page.copy(
            originalPath = original.path,
            imagePath = image.path,
            rotation = 0,
            ocrText = null,
            sourcePath = keptPhoto?.path,
            corners = keptPhoto?.let { captured.second.encode() },
            markup = false,
            pdfPath = null,
        )
        dao.updatePage(updated)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
        updated
    }

    /** The photo the crop works on: the whole uncropped photo when kept, else the current scan. */
    fun cropSource(page: PageEntity): File =
        page.sourcePath?.let(::File)?.takeIf { it.exists() } ?: File(page.originalPath)

    /**
     * Crop: flattens the quadrilateral [corners] (TL, TR, BR, BL, in [cropSource] pixels) into the new
     * original, then re-applies the page's filter and rotation. The photo cropped from is kept, so the
     * corners can be adjusted again later.
     */
    suspend fun recropPage(page: PageEntity, outline: PageOutline): PageEntity = withContext(Dispatchers.Default) {
        startEdit(page.documentId)
        val sourceFile = cropSource(page)
        val source = BitmapUtils.decode(sourceFile)
        val cropped = DocumentDetector.warp(source, outline)
        val dir = File(page.originalPath).parentFile!!
        val original = BitmapUtils.saveJpeg(cropped, File(dir, "orig_${UUID.randomUUID()}.jpg"), 95)
        // First crop of a page without a kept photo: its current scan becomes the kept photo.
        if (sourceFile.path != page.originalPath) dispose(page.documentId, page.originalPath)
        dispose(page.documentId, page.pdfPath)
        val updated = page.copy(
            originalPath = original.path,
            ocrText = null,
            sourcePath = sourceFile.path,
            corners = outline.encode(),
            markup = false,
            pdfPath = null,
        )
        dao.updatePage(updated)
        editPage(updated, page.imageFilter, page.rotation)
    }

    // ---- Word documents --------------------------------------------------------------------------

    /** Saves [doc] as a new Word document (in [folderId]) and returns its id. */
    suspend fun createWordDocument(title: String, doc: WordDocument, folderId: Long? = null): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now, folderId = folderId))
        writeWord(id, doc)
        id
    }

    /** Imports a .docx file (kept byte for byte) as a Word document. */
    suspend fun importDocx(uri: Uri, title: String): Long = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open the file")
        val doc = DocxReader.read(bytes) // throws for files that are not Word documents
        val now = System.currentTimeMillis()
        val id = dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now))
        val file = File(documentDir(id), "document.docx").apply { writeBytes(bytes) }
        val cover = writeCover(id, doc)
        dao.getDocument(id)?.let { dao.updateDocument(it.copy(wordPath = file.path, wordCover = cover.path)) }
        id
    }

    suspend fun loadWord(documentId: Long): WordDocument = withContext(Dispatchers.IO) {
        val path = dao.getDocument(documentId)?.wordPath ?: error("Not a Word document")
        DocxReader.read(File(path))
    }

    suspend fun saveWord(documentId: Long, doc: WordDocument) = withContext(Dispatchers.IO) { writeWord(documentId, doc) }

    private suspend fun writeWord(id: Long, doc: WordDocument) {
        val file = File(documentDir(id), "document.docx")
        DocxWriter.write(doc, file)
        val cover = writeCover(id, doc)
        val entity = dao.getDocument(id) ?: return
        dao.updateDocument(entity.copy(wordPath = file.path, wordCover = cover.path, updatedAt = System.currentTimeMillis()))
    }

    /** A fresh cover file each time (lists cache pictures by path). */
    private suspend fun writeCover(id: Long, doc: WordDocument): File {
        val old = dao.getDocument(id)?.wordCover
        val cover = File(documentDir(id), "cover_${System.currentTimeMillis()}.jpg")
        BitmapUtils.saveJpeg(WordThumbnail.render(doc, 420), cover, 85)
        old?.let { if (it != cover.path) File(it).delete() }
        return cover
    }

    /** "Copy": a full duplicate of the document next to it (same folder). */
    suspend fun duplicateDocument(documentId: Long): Long {
        val doc = dao.getDocument(documentId) ?: return documentId
        doc.wordPath?.let { path ->
            return withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val id = dao.insertDocument(DocumentEntity(title = "${doc.title} (copy)", createdAt = now, updatedAt = now, folderId = doc.folderId))
                val file = File(path).copyTo(File(documentDir(id), "document.docx"), overwrite = true)
                val cover = doc.wordCover?.let { File(it).copyTo(File(documentDir(id), "cover_${now}.jpg"), overwrite = true) }
                dao.getDocument(id)?.let { dao.updateDocument(it.copy(wordPath = file.path, wordCover = cover?.path)) }
                id
            }
        }
        val id = merge(listOf(documentId), "${doc.title} (copy)", deleteSources = false)
        doc.folderId?.let { dao.moveToFolder(listOf(id), it) }
        return id
    }

    /** Copies the chosen pages (in document order) into a new document — "Extract pages". */
    suspend fun extractPages(documentId: Long, pageIds: Set<Long>, title: String): Long = withContext(Dispatchers.IO) {
        val newId = createDocument(title)
        val dir = documentDir(newId)
        var position = 0
        for (page in dao.getPages(documentId).filter { it.id in pageIds }) {
            val original = File(page.originalPath).copyTo(File(dir, "orig_${UUID.randomUUID()}.jpg"))
            val image = File(page.imagePath).copyTo(File(dir, "page_${UUID.randomUUID()}.jpg"))
            dao.insertPage(
                page.copy(
                    id = 0,
                    documentId = newId,
                    position = position++,
                    originalPath = original.path,
                    imagePath = image.path,
                    sourcePath = copySource(page, dir),
                    pdfPath = copyPdf(page, dir),
                ),
            )
        }
        newId
    }

    /** Applies a full new page order (page ids, first to last). */
    suspend fun reorderPages(documentId: Long, orderedPageIds: List<Long>) {
        startEdit(documentId)
        val byId = dao.getPages(documentId).associateBy { it.id }
        dao.updatePages(orderedPageIds.mapNotNull { byId[it] }.mapIndexed { i, p -> p.copy(position = i) })
        dao.touchDocument(documentId, System.currentTimeMillis())
    }

    /** Stamps date and time on every page, bottom right, like a timestamp camera. */
    suspend fun stampDateTime(documentId: Long, text: String) {
        dao.getPages(documentId).forEach { page -> bakeEdit(page) { PageComposer.timestamp(it, text) } }
    }

    // ---- folders --------------------------------------------------------------------------------

    /** Folders directly inside [parentId] (null = top level). */
    fun observeFolders(parentId: Long?): Flow<List<FolderSummary>> = dao.observeFolders(parentId)
    fun observeFolder(id: Long): Flow<FolderEntity?> = dao.observeFolder(id)
    fun observeFolderDocuments(folderId: Long?): Flow<List<DocumentSummary>> = dao.observeFolderDocuments(folderId)
    suspend fun getFolders() = dao.getFolders()

    /** Every folder with its full path ("Work / 2026 / Invoices"), sorted by path. */
    suspend fun folderPaths(): List<Pair<FolderEntity, String>> {
        val all = dao.getFolders()
        val byId = all.associateBy { it.id }
        fun path(f: FolderEntity, depth: Int = 0): String {
            val parent = f.parentId?.let { byId[it] }
            return if (parent == null || depth > 32) f.name else path(parent, depth + 1) + " / " + f.name
        }
        return all.map { it to path(it) }.sortedBy { it.second.lowercase() }
    }

    suspend fun createFolder(name: String, parentId: Long? = null): Long = dao.insertFolder(
        FolderEntity(name = name.trim().ifEmpty { S.str(R.string.new_folder) }, createdAt = System.currentTimeMillis(), parentId = parentId),
    )

    suspend fun renameFolder(id: Long, name: String) {
        if (name.isNotBlank()) dao.renameFolder(id, name.trim())
    }

    /** Deletes the folder only; its documents and sub-folders move up into the enclosing folder. */
    suspend fun deleteFolder(id: Long) {
        val parent = dao.getFolder(id)?.parentId
        dao.releaseFolder(id, parent)
        dao.reparentChildren(id, parent)
        dao.deleteFolder(id)
    }

    suspend fun moveToFolder(documentIds: List<Long>, folderId: Long?) = dao.moveToFolder(documentIds, folderId)

    // ---- stats ----------------------------------------------------------------------------------

    fun observeDocumentCount(): Flow<Int> = dao.observeDocumentCount()
    fun observePageCount(): Flow<Int> = dao.observePageCount()

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ---- QR history -----------------------------------------------------------------------------

    suspend fun addQr(value: String, format: String) =
        dao.insertQr(QrHistoryEntity(value = value, format = format, scannedAt = System.currentTimeMillis()))

    suspend fun clearQrHistory() = dao.clearQrHistory()

    // ---- signatures -----------------------------------------------------------------------------

    val signatureDir: File get() = File(context.filesDir, "signatures").apply { mkdirs() }

    fun signatures(): List<File> =
        signatureDir.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun saveSignature(bitmap: Bitmap): File =
        BitmapUtils.savePng(bitmap, File(signatureDir, "sig_${System.currentTimeMillis()}.png"))

    private fun documentDir(id: Long) = File(root, id.toString()).apply { mkdirs() }
}
