package com.paperlens.app.excel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import kotlin.math.max
import kotlin.math.min

/** A small picture of the top-left of the first sheet, for library lists (like a Word document's first page). */
object SheetThumbnail {

    fun render(book: Workbook, width: Int): Bitmap {
        val height = width * 4 / 3
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val sheet = book.sheets.firstOrNull() ?: return bitmap
        // Show the used columns (up to 8) across the picture.
        val cols = sheet.colCount.coerceIn(3, 8)
        val usedWidth = (0 until cols).sumOf { colPx(sheet, it).toDouble() }.toFloat()
        val scale = width / max(usedWidth, 1f)
        val x = FloatArray(cols + 1).also { for (c in 0 until cols) it[c + 1] = it[c] + colPx(sheet, c) * scale }
        val grid = Paint().apply { color = 0xFFD9D9D9.toInt(); strokeWidth = 1f }
        val fill = Paint()
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint().apply { color = Color.BLACK; strokeWidth = max(1f, scale) }
        var y = 0f
        var r = 0
        while (y < height && r < 400) {
            val h = rowPx(sheet, r) * scale
            if (h > 0) {
                for (c in 0 until cols) {
                    val cell = sheet[r, c]
                    val merge = sheet.merges.firstOrNull { it.contains(r, c) }
                    if (merge != null && (merge.r0 != r || merge.c0 != c)) continue
                    val right = if (merge != null) x[min(merge.c1 + 1, cols)] else x[c + 1]
                    val bottom = if (merge != null) y + (r..merge.r1).sumOf { rowPx(sheet, it).toDouble() }.toFloat() * scale else y + h
                    val rect = RectF(x[c], y, right, bottom)
                    cell?.style?.fill?.let { fill.color = 0xFF000000.toInt() or it; canvas.drawRect(rect, fill) }
                    val s = cell?.style ?: CellStyle.DEFAULT
                    s.top?.let { canvas.drawLine(rect.left, rect.top, rect.right, rect.top, line) }
                    s.bottom?.let { canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, line) }
                    s.left?.let { canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, line) }
                    s.right?.let { canvas.drawLine(rect.right, rect.top, rect.right, rect.bottom, line) }
                    val shown = book.display(cell)
                    if (shown.isNotEmpty()) {
                        text.textSize = (s.fontSize ?: 11f) * 4f / 3f * scale
                        text.color = 0xFF000000.toInt() or (s.fontColor ?: 0)
                        text.typeface = if (s.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        val numeric = cell?.value is CellValue.Number
                        val tw = text.measureText(shown)
                        val tx = when {
                            s.hAlign == HAlign.CENTER -> rect.centerX() - tw / 2
                            s.hAlign == HAlign.RIGHT || (s.hAlign == HAlign.GENERAL && numeric) -> rect.right - tw - 2 * scale
                            else -> rect.left + 2 * scale
                        }
                        canvas.withClip(rect) { drawText(shown.lineSequence().first(), tx, rect.bottom - 3 * scale, text) }
                    }
                }
                canvas.drawLine(0f, y + h, width.toFloat(), y + h, grid)
            }
            y += h
            r++
        }
        for (c in 1..cols) canvas.drawLine(x[c], 0f, x[c], height.toFloat(), grid)
        return bitmap
    }

    /** Excel's on-screen sizes at 100 %: about 7 px per character of width, 4/3 px per point of height. */
    fun colPx(sheet: Sheet, c: Int): Float = sheet.colWidth(c).let { if (it <= 0f) 0f else it * 7f + 5f }
    fun rowPx(sheet: Sheet, r: Int): Float = sheet.rowHeight(r) * 4f / 3f
}
