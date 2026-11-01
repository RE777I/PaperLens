package com.paperlens.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.paperlens.app.imaging.ImageFilter

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Folder-like label; null means "no tag". */
    val tag: String? = null,
    /** Opening the document requires biometric / device credential. */
    val locked: Boolean = false,
    /** Folder in the Files tab; null = top level. */
    val folderId: Long? = null,
    /** Word documents: the .docx file (they have no scanned pages). */
    val wordPath: String? = null,
    /** Word documents: a small picture of the first page for lists. */
    val wordCover: String? = null,
) {
    val isWord: Boolean get() = wordPath != null
}

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Enclosing folder; null = top level. */
    val parentId: Long? = null,
)

data class FolderSummary(
    @Embedded val folder: FolderEntity,
    /** Documents plus sub-folders directly inside. */
    val documentCount: Int,
    /** Newest document change inside the folder, or the folder's creation time when empty. */
    val updatedAt: Long,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val position: Int,
    /** Cropped scan as delivered by the scanner; filters are always re-rendered from this file. */
    val originalPath: String,
    /** Rendered result (filter + rotation) shown to the user and exported. */
    val imagePath: String,
    val filter: String = ImageFilter.ORIGINAL.name,
    val rotation: Int = 0,
    val ocrText: String? = null,
    /** The whole photo before cropping, kept so the crop can be adjusted later (null: nothing was cut off). */
    val sourcePath: String? = null,
    /** Crop corners TL, TR, BR, BL in [sourcePath] pixels, as "x,y,x,y,x,y,x,y". */
    val corners: String? = null,
    /** Text, signatures or drawings were added after cropping (re-cropping from [sourcePath] drops them). */
    val markup: Boolean = false,
    /**
     * Imported from a PDF: that page as a one-page PDF, kept for sharp zoom, its real text and lossless
     * export. Dropped when the picture is changed in a way the PDF can't show (edits, retake, re-crop).
     */
    val pdfPath: String? = null,
    /**
     * The recognised words with their boxes (`SearchableText.encodeLayout`), so Find Text highlights
     * without reading the page again. Belongs to the image file it names; ignored once the image changes.
     */
    val ocrLayout: String? = null,
)

// Extension rather than member so Room never treats it as a column.
val PageEntity.imageFilter: ImageFilter get() = ImageFilter.fromName(filter)

/** The page's PDF while the page still looks like it: no filter, no rotation. */
val PageEntity.pdfFile: java.io.File?
    get() = pdfPath?.takeIf { filter == ImageFilter.ORIGINAL.name && rotation == 0 }?.let { java.io.File(it) }?.takeIf { it.exists() }

/** The saved crop (corners and, for curled pages, curved sides), or null when never cropped from a larger photo. */
val PageEntity.outline: com.paperlens.app.imaging.PageOutline?
    get() = com.paperlens.app.imaging.PageOutline.decode(corners)

val PageEntity.cornerPoints: List<android.graphics.PointF>? get() = outline?.corners

fun encodeCorners(points: List<android.graphics.PointF>): String = points.joinToString(",") { "${it.x},${it.y}" }

data class DocumentSummary(
    @Embedded val document: DocumentEntity,
    val pageCount: Int,
    val coverPath: String?,
)

@Entity(tableName = "qr_history")
data class QrHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val value: String,
    val format: String,
    val scannedAt: Long,
)
