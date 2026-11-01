package com.paperlens.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
            COALESCE((SELECT p.imagePath FROM pages p WHERE p.documentId = d.id ORDER BY p.position LIMIT 1), d.wordCover) AS coverPath
        FROM documents d
        WHERE (:query = ''
                OR d.title LIKE '%' || :query || '%'
                OR EXISTS (SELECT 1 FROM pages p WHERE p.documentId = d.id AND p.ocrText LIKE '%' || :query || '%'))
          AND (:tag IS NULL OR d.tag = :tag)
        ORDER BY d.updatedAt DESC
        """,
    )
    fun observeDocuments(query: String, tag: String?): Flow<List<DocumentSummary>>

    /** Documents directly inside [folderId] (null = top level of the Files tab). */
    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
            COALESCE((SELECT p.imagePath FROM pages p WHERE p.documentId = d.id ORDER BY p.position LIMIT 1), d.wordCover) AS coverPath
        FROM documents d
        WHERE d.folderId IS :folderId
        ORDER BY d.updatedAt DESC
        """,
    )
    fun observeFolderDocuments(folderId: Long?): Flow<List<DocumentSummary>>

    /** Folders directly inside [parentId] (null = top level). */
    @Query(
        """
        SELECT f.*,
            (SELECT COUNT(*) FROM documents d WHERE d.folderId = f.id)
                + (SELECT COUNT(*) FROM folders c WHERE c.parentId = f.id) AS documentCount,
            COALESCE((SELECT MAX(d.updatedAt) FROM documents d WHERE d.folderId = f.id), f.createdAt) AS updatedAt
        FROM folders f
        WHERE f.parentId IS :parentId
        ORDER BY f.name COLLATE NOCASE
        """,
    )
    fun observeFolders(parentId: Long?): Flow<List<FolderSummary>>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observeFolder(id: Long): Flow<FolderEntity?>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolder(id: Long): FolderEntity?

    @Query("UPDATE folders SET parentId = :newParent WHERE parentId = :folderId")
    suspend fun reparentChildren(folderId: Long, newParent: Long?)

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    suspend fun getFolders(): List<FolderEntity>

    @Insert
    suspend fun insertFolder(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("UPDATE documents SET folderId = :newFolder WHERE folderId = :folderId")
    suspend fun releaseFolder(folderId: Long, newFolder: Long?)

    @Query("UPDATE documents SET folderId = :folderId WHERE id IN (:documentIds)")
    suspend fun moveToFolder(documentIds: List<Long>, folderId: Long?)

    @Query("SELECT COUNT(*) FROM documents")
    fun observeDocumentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pages")
    fun observePageCount(): Flow<Int>

    @Query("SELECT DISTINCT tag FROM documents WHERE tag IS NOT NULL AND tag != '' ORDER BY tag")
    fun observeTags(): Flow<List<String>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Insert
    suspend fun insertDocument(document: DocumentEntity): Long

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("UPDATE documents SET updatedAt = :time WHERE id = :id")
    suspend fun touchDocument(id: Long, time: Long)

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    fun observePages(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    suspend fun getPages(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    fun observePage(id: Long): Flow<PageEntity?>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPage(id: Long): PageEntity?

    @Insert
    suspend fun insertPage(page: PageEntity): Long

    @Update
    suspend fun updatePage(page: PageEntity)

    @Update
    suspend fun updatePages(pages: List<PageEntity>)

    @Delete
    suspend fun deletePage(page: PageEntity)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deletePagesOf(documentId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Insert
    suspend fun insertQr(entry: QrHistoryEntity): Long

    @Query("SELECT * FROM qr_history ORDER BY scannedAt DESC")
    fun observeQrHistory(): Flow<List<QrHistoryEntity>>

    @Query("UPDATE pages SET ocrText = NULL, ocrLayout = NULL")
    suspend fun clearOcrText()

    @Query("DELETE FROM qr_history")
    suspend fun clearQrHistory()
}
