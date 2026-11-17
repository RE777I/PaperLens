package com.paperlens.app.data

import android.graphics.Bitmap
import android.net.Uri
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.excel.SheetThumbnail
import com.paperlens.app.excel.Workbook
import com.paperlens.app.excel.XCell
import com.paperlens.app.excel.XlsxEditor
import com.paperlens.app.excel.XlsxReader
import com.paperlens.app.excel.XlsxWriter
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.word.DocxReader
import com.paperlens.app.word.DocxWriter
import com.paperlens.app.word.WordDocument
import com.paperlens.app.word.WordThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

// The library side of [DocumentRepository]: Word and Excel documents, folders, statistics, QR history and signatures.

// ---- Word documents --------------------------------------------------------------------------

/** Saves [doc] as a new Word document (in [folderId]) and returns its id. */
suspend fun DocumentRepository.createWordDocument(title: String, doc: WordDocument, folderId: Long? = null): Long = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val id = dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now, folderId = folderId))
    writeWord(id, doc)
    id
}

/** Imports a .docx file (kept byte for byte) as a Word document. */
suspend fun DocumentRepository.importDocx(uri: Uri, title: String): Long = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open the file")
    val doc = DocxReader.read(bytes) // throws for files that are not Word documents
    val now = System.currentTimeMillis()
    val id = dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now))
    val file = File(documentDir(id), "document.docx").apply { writeBytes(bytes) }
    val cover = writeCover(id, doc)
    dao.getDocument(id)?.let { dao.updateDocument(it.copy(wordPath = file.path, wordCover = cover.path)) }
    id
}

suspend fun DocumentRepository.loadWord(documentId: Long): WordDocument = withContext(Dispatchers.IO) {
    val path = dao.getDocument(documentId)?.wordPath ?: error("Not a Word document")
    DocxReader.read(File(path))
}

suspend fun DocumentRepository.saveWord(documentId: Long, doc: WordDocument) = withContext(Dispatchers.IO) { writeWord(documentId, doc) }

private suspend fun DocumentRepository.writeWord(id: Long, doc: WordDocument) {
    val file = File(documentDir(id), "document.docx")
    DocxWriter.write(doc, file)
    val cover = writeCover(id, doc)
    val entity = dao.getDocument(id) ?: return
    dao.updateDocument(entity.copy(wordPath = file.path, wordCover = cover.path, updatedAt = System.currentTimeMillis()))
}

/** A fresh cover file each time (lists cache pictures by path). */
private suspend fun DocumentRepository.writeCover(id: Long, doc: WordDocument): File {
    val old = dao.getDocument(id)?.wordCover
    val cover = File(documentDir(id), "cover_${System.currentTimeMillis()}.jpg")
    BitmapUtils.saveJpeg(WordThumbnail.render(doc, 420), cover, 85)
    old?.let { if (it != cover.path) File(it).delete() }
    return cover
}

// ---- Excel documents -------------------------------------------------------------------------

/** Saves [book] as a new Excel document (in [folderId]) and returns its id. */
suspend fun DocumentRepository.createExcelDocument(title: String, book: Workbook, folderId: Long? = null): Long = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val id = dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now, folderId = folderId))
    val file = XlsxWriter.write(book, File(documentDir(id), "workbook.xlsx"))
    val cover = writeExcelCover(id, book)
    dao.getDocument(id)?.let { dao.updateDocument(it.copy(excelPath = file.path, wordCover = cover.path)) }
    id
}

/** Imports an .xlsx file (kept byte for byte) as an Excel document. */
suspend fun DocumentRepository.importXlsx(uri: Uri, title: String): Long = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open the file")
    val book = XlsxReader.read(bytes) // throws for files that are not workbooks
    val now = System.currentTimeMillis()
    val id = dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now))
    val file = File(documentDir(id), "workbook.xlsx").apply { writeBytes(bytes) }
    val cover = writeExcelCover(id, book)
    dao.getDocument(id)?.let { dao.updateDocument(it.copy(excelPath = file.path, wordCover = cover.path)) }
    id
}

suspend fun DocumentRepository.loadExcel(documentId: Long): Workbook = withContext(Dispatchers.IO) {
    val path = dao.getDocument(documentId)?.excelPath ?: error("Not an Excel document")
    XlsxReader.read(File(path))
}

/** Writes cell [edits] (sheet index → cell key → new cell, null = cleared) into the stored file and returns the workbook as saved. */
suspend fun DocumentRepository.saveExcelEdits(documentId: Long, book: Workbook, edits: Map<Int, Map<Long, XCell?>>): Workbook = withContext(Dispatchers.IO) {
    val entity = dao.getDocument(documentId) ?: error("Document not found")
    val file = File(entity.excelPath ?: error("Not an Excel document"))
    val bytes = XlsxEditor.apply(file.readBytes(), book, edits)
    // Write next to it first, so a failure can't leave half a file.
    val tmp = File(file.parentFile, "workbook.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    val saved = XlsxReader.read(bytes)
    val cover = writeExcelCover(documentId, saved)
    dao.getDocument(documentId)?.let { dao.updateDocument(it.copy(wordCover = cover.path, updatedAt = System.currentTimeMillis())) }
    saved
}

private suspend fun DocumentRepository.writeExcelCover(id: Long, book: Workbook): File {
    val old = dao.getDocument(id)?.wordCover
    val cover = File(documentDir(id), "cover_${System.currentTimeMillis()}.jpg")
    BitmapUtils.saveJpeg(SheetThumbnail.render(book, 420), cover, 85)
    old?.let { if (it != cover.path) File(it).delete() }
    return cover
}

// ---- folders --------------------------------------------------------------------------------

/** Folders directly inside [parentId] (null = top level). */
fun DocumentRepository.observeFolders(parentId: Long?): Flow<List<FolderSummary>> = dao.observeFolders(parentId)
fun DocumentRepository.observeFolder(id: Long): Flow<FolderEntity?> = dao.observeFolder(id)
fun DocumentRepository.observeFolderDocuments(folderId: Long?): Flow<List<DocumentSummary>> = dao.observeFolderDocuments(folderId)
suspend fun DocumentRepository.getFolders() = dao.getFolders()

/** Every folder with its full path ("Work / 2026 / Invoices"), sorted by path. */
suspend fun DocumentRepository.folderPaths(): List<Pair<FolderEntity, String>> {
    val all = dao.getFolders()
    val byId = all.associateBy { it.id }
    fun path(f: FolderEntity, depth: Int = 0): String {
        val parent = f.parentId?.let { byId[it] }
        return if (parent == null || depth > 32) f.name else path(parent, depth + 1) + " / " + f.name
    }
    return all.map { it to path(it) }.sortedBy { it.second.lowercase() }
}

suspend fun DocumentRepository.createFolder(name: String, parentId: Long? = null): Long = dao.insertFolder(
    FolderEntity(name = name.trim().ifEmpty { S.str(R.string.new_folder) }, createdAt = System.currentTimeMillis(), parentId = parentId),
)

suspend fun DocumentRepository.renameFolder(id: Long, name: String) {
    if (name.isNotBlank()) dao.renameFolder(id, name.trim())
}

/** Deletes the folder only; its documents and sub-folders move up into the enclosing folder. */
suspend fun DocumentRepository.deleteFolder(id: Long) {
    val parent = dao.getFolder(id)?.parentId
    dao.releaseFolder(id, parent)
    dao.reparentChildren(id, parent)
    dao.deleteFolder(id)
}

suspend fun DocumentRepository.moveToFolder(documentIds: List<Long>, folderId: Long?) = dao.moveToFolder(documentIds, folderId)

// ---- stats ----------------------------------------------------------------------------------

fun DocumentRepository.observeDocumentCount(): Flow<Int> = dao.observeDocumentCount()
fun DocumentRepository.observePageCount(): Flow<Int> = dao.observePageCount()

suspend fun DocumentRepository.storageBytes(): Long = withContext(Dispatchers.IO) {
    root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

// ---- QR history -----------------------------------------------------------------------------

suspend fun DocumentRepository.addQr(value: String, format: String) =
    dao.insertQr(QrHistoryEntity(value = value, format = format, scannedAt = System.currentTimeMillis()))

suspend fun DocumentRepository.clearQrHistory() = dao.clearQrHistory()

// ---- signatures -----------------------------------------------------------------------------

val DocumentRepository.signatureDir: File get() = File(context.filesDir, "signatures").apply { mkdirs() }

fun DocumentRepository.signatures(): List<File> =
    signatureDir.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() } ?: emptyList()

fun DocumentRepository.saveSignature(bitmap: Bitmap): File =
    BitmapUtils.savePng(bitmap, File(signatureDir, "sig_${System.currentTimeMillis()}.png"))
