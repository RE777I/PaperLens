package com.paperlens.app.ml

import android.content.Context
import java.io.File

/**
 * OCR that keeps the page layout as a grid: every recognised line becomes a cell, lines whose
 * vertical centres line up form a row, and cells within a row are ordered left to right.
 * Good enough for receipts, invoices and simple printed tables ("To Excel").
 */
object TableOcr {

    suspend fun recognizeRows(context: Context, image: File, script: OcrScript): List<List<String>> =
        groupRows(TextLayout.lines(context, image, script))

    private fun groupRows(cells: List<TextLine>): List<List<String>> {
        if (cells.isEmpty()) return emptyList()
        val lineHeight = cells.map { it.box.height() }.sorted()[cells.size / 2].coerceAtLeast(1f)
        val rows = mutableListOf<MutableList<TextLine>>()
        for (cell in cells.sortedBy { it.box.centerY() }) {
            val row = rows.lastOrNull()
            val rowCenter = row?.map { it.box.centerY() }?.average()?.toFloat()
            if (row != null && rowCenter != null && cell.box.centerY() - rowCenter < lineHeight * 0.6f) row += cell else rows += mutableListOf(cell)
        }
        return rows.map { row -> row.sortedBy { it.box.left }.map { it.text } }
    }
}
