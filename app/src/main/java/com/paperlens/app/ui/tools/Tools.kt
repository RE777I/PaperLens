package com.paperlens.app.ui.tools

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolSection(private val titleRes: Int) {
    SCAN(R.string.scan),
    IMPORT(R.string.import_text),
    CONVERT(R.string.convert),
    EDIT(R.string.edit),
    UTILITIES(R.string.utilities),
;
    val title: String get() = S.str(titleRes)
}

private val Teal = Color(0xFF14A38B)
private val Blue = Color(0xFF2F6FED)
private val Red = Color(0xFFE5533D)
private val Green = Color(0xFF1E9E4A)
private val Orange = Color(0xFFEF8A17)
private val Indigo = Color(0xFF5B5FD6)

/** Every tool the app offers; [section] null = only shown on the Home quick grid. */
enum class Tool(private val labelRes: Int, val icon: ImageVector, val tint: Color, val section: ToolSection?) {
    SMART_SCAN(R.string.smart_scan, Icons.Filled.DocumentScanner, Teal, null),
    PDF_TOOLS(R.string.pdf_tools, Icons.Filled.PictureAsPdf, Red, null),
    ALL_TOOLS(R.string.all, Icons.Filled.Apps, Blue, null),

    ID_CARDS(R.string.id_cards, Icons.Filled.Badge, Teal, ToolSection.SCAN),
    EXTRACT_TEXT(R.string.extract_text, Icons.Filled.TextFields, Teal, ToolSection.SCAN),
    PHOTO_TRANSLATION(R.string.photo_translation, Icons.Filled.Translate, Indigo, ToolSection.SCAN),
    BOOK(R.string.book, Icons.Filled.AutoStories, Blue, ToolSection.SCAN),
    WHITEBOARD(R.string.whiteboard, Icons.Filled.Dashboard, Teal, ToolSection.SCAN),
    TIMESTAMP(R.string.timestamp, Icons.Filled.Schedule, Blue, ToolSection.SCAN),

    IMPORT_IMAGES(R.string.import_images, Icons.Filled.Image, Teal, ToolSection.IMPORT),
    IMPORT_FILES(R.string.import_files, Icons.Filled.DriveFolderUpload, Blue, ToolSection.IMPORT),

    TO_WORD(R.string.to_word, Icons.Filled.Description, Blue, ToolSection.CONVERT),
    TO_EXCEL(R.string.to_excel, Icons.Filled.TableChart, Green, ToolSection.CONVERT),
    PDF_TO_IMAGES(R.string.pdf_to_images, Icons.Filled.Collections, Teal, ToolSection.CONVERT),
    PDF_TO_LONG_IMAGE(R.string.pdf_to_long_image, Icons.Filled.ViewDay, Teal, ToolSection.CONVERT),

    SIGN(R.string.sign, Icons.Filled.Draw, Blue, ToolSection.EDIT),
    ADD_WATERMARK(R.string.add_watermark, Icons.AutoMirrored.Filled.BrandingWatermark, Teal, ToolSection.EDIT),
    SMART_ERASE(R.string.smart_erase, Icons.Filled.AutoFixHigh, Indigo, ToolSection.EDIT),
    MERGE_FILES(R.string.merge_files, Icons.AutoMirrored.Filled.MergeType, Blue, ToolSection.EDIT),
    EXTRACT_PAGES(R.string.extract_pages, Icons.Filled.ContentCut, Orange, ToolSection.EDIT),
    REORDER_PAGES(R.string.reorder_pages, Icons.Filled.SwapVert, Blue, ToolSection.EDIT),
    LOCK(R.string.lock, Icons.Filled.Lock, Green, ToolSection.EDIT),
    COMPRESS(R.string.compress, Icons.Filled.Compress, Blue, ToolSection.EDIT),

    PRINT(R.string.print, Icons.Filled.Print, Teal, ToolSection.UTILITIES),
    SCAN_CODE(R.string.scan_code, Icons.Filled.QrCodeScanner, Teal, ToolSection.UTILITIES),
    CODE_HISTORY(R.string.code_history_2, Icons.Filled.History, Blue, ToolSection.UTILITIES),
    ;
    val label: String get() = S.str(labelRes)


    companion object {
        /** The 4×2 grid at the top of Home. */
        val quick = listOf(SMART_SCAN, PDF_TOOLS, IMPORT_IMAGES, IMPORT_FILES, ID_CARDS, EXTRACT_TEXT, PHOTO_TRANSLATION, ALL_TOOLS)

        /** Shown in the PDF Tools sheet. */
        val pdf = listOf(IMPORT_FILES, PDF_TO_IMAGES, PDF_TO_LONG_IMAGE, MERGE_FILES, EXTRACT_PAGES, COMPRESS, LOCK, SIGN, PRINT)
    }
}
