package com.paperlens.app.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.paperlens.app.excel.CellStyle
import com.paperlens.app.excel.CellValue
import com.paperlens.app.excel.NumberFormat
import com.paperlens.app.excel.Sheet
import com.paperlens.app.excel.SheetBuilder
import com.paperlens.app.excel.Workbook
import com.paperlens.app.excel.XCell
import com.paperlens.app.excel.XlsxReader
import com.paperlens.app.excel.XlsxWriter
import com.paperlens.app.excel.cellKey
import com.paperlens.app.excel.cellName
import com.paperlens.app.excel.display
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.ml.Ocr
import com.paperlens.app.ml.OcrScript
import com.paperlens.app.ml.TextLayout
import com.paperlens.app.ml.TextLine
import java.io.File

/**
 * Debug builds only: converts one table photo to Excel and writes, for scoring off the device:
 * `<name>.xlsx`, `<name>.grid.tsv` (what each cell shows; ⏎ for line breaks; cells under a merge empty),
 * `<name>.types.tsv` (N number, T text, per cell), `<name>.txt` (merges, formats, number cells) and
 * `<name>_debug.jpg` (ruled tables green, shaded-row tables orange, borderless tables and their column
 * breaks blue). The same files with `_legacy` come from the old converter (OCR lines grouped into rows
 * by height only), as the baseline.
 */
internal object ExcelProbe {

    suspend fun convert(context: Context, file: File, outDir: File, script: OcrScript) {
        val name = file.nameWithoutExtension
        val bitmap = BitmapUtils.decode(file, 2400)
        val text = Ocr.recognizeText(context, bitmap, script)
        val result = SheetBuilder(bitmap, text, "Sheet1").build()
        val book = Workbook(listOf(result.sheet))
        val xlsx = XlsxWriter.write(book, File(outDir, "$name.xlsx"))
        // Read it back, so the dump shows what the viewer and Excel get.
        val back = XlsxReader.read(xlsx)
        dumpGrid(back, back.sheets[0], outDir, name)
        val sb = StringBuilder()
        val s = back.sheets[0]
        sb.append("rows=${s.rowCount} cols=${s.colCount} frozen=${s.frozenRows} merges=${s.merges.size} inverted=${result.debug.inverted}\n")
        result.debug.notes.forEach { sb.append(it).append('\n') }
        s.merges.forEach { sb.append("merge ${cellName(it.r0, it.c0)}:${cellName(it.r1, it.c1)}\n") }
        sb.append("widths ${s.colWidths.toSortedMap()}\n")
        for ((k, cell) in s.cells.entries.sortedBy { it.key }) {
            if (cell.value !is CellValue.Number && cell.style == CellStyle.DEFAULT) continue
            val st = cell.style
            sb.append("${cellName((k shr 16).toInt(), (k and 0xFFFF).toInt())} ${cell.value} fmt=${st.numFmt} shows=\"${back.display(cell)}\"")
            if (st.bold) sb.append(" bold")
            st.fill?.let { sb.append(" fill=%06X".format(it)) }
            if (st.top != null) sb.append(" border")
            sb.append('\n')
        }
        File(outDir, "$name.txt").writeText(sb.toString())

        val overlay = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(overlay)
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        val d = result.debug
        stroke.color = Color.rgb(0, 170, 0)
        for (g in d.grids) for (cell in g.cells) c.drawRect(g.cellRect(cell), stroke)
        stroke.color = Color.rgb(255, 140, 0)
        for (b in d.bands) {
            c.drawRect(b.box, stroke)
            for (y in b.rowEdges) c.drawLine(b.box.left, y, b.box.right, y, stroke)
        }
        stroke.color = Color.BLUE
        for ((box, breaks) in d.loose) {
            c.drawRect(box, stroke)
            for (x in breaks) c.drawLine(x, box.top, x, box.bottom, stroke)
        }
        stroke.color = Color.CYAN
        stroke.strokeWidth = 3f
        for (r in d.rules) for (sp in r.spans) {
            if (r.horizontal) c.drawLine(sp[0], r.at(sp[0]) + 6, sp[1], r.at(sp[1]) + 6, stroke)
            else c.drawLine(r.at(sp[0]) + 6, sp[0], r.at(sp[1]) + 6, sp[1], stroke)
        }
        stroke.strokeWidth = 2f
        stroke.color = Color.RED
        for (wd in d.words) c.drawRect(wd.box, stroke)
        BitmapUtils.saveJpeg(BitmapUtils.scaleDown(overlay, 1400), File(outDir, "${name}_debug.jpg"), 85)

        // Baseline: the converter before tables were rebuilt (OCR lines grouped into rows by their height).
        val rows = legacyRows(TextLayout.lines(context, file, script))
        val legacyCells = HashMap<Long, XCell>()
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { col, raw ->
                val number = raw.trim().replace(",", "").toDoubleOrNull()
                legacyCells[cellKey(r, col)] = if (number != null && raw.trim().matches(LEGACY_NUMERIC)) XCell(CellValue.Number(number)) else XCell(CellValue.Text(raw))
            }
        }
        val legacy = Workbook(listOf(Sheet("Sheet1", legacyCells)))
        XlsxWriter.write(legacy, File(outDir, "${name}_legacy.xlsx"))
        dumpGrid(legacy, legacy.sheets[0], outDir, "${name}_legacy")
    }

    /** `<name>.grid.tsv` (shown text), `.types.tsv` (N / T) and `.values.tsv` (numbers unformatted). */
    private fun dumpGrid(book: Workbook, s: Sheet, outDir: File, name: String) {
        val g = StringBuilder()
        val t = StringBuilder()
        val v = StringBuilder()
        fun clean(x: String) = x.replace("\n", "⏎").replace("\t", " ")
        for (r in 0 until s.rowCount) {
            g.append((0 until s.colCount).joinToString("\t") { c -> clean(book.display(s[r, c])) }).append('\n')
            t.append((0 until s.colCount).joinToString("\t") { c ->
                when (s[r, c]?.value) { is CellValue.Number -> "N"; is CellValue.Text -> "T"; else -> "" }
            }).append('\n')
            v.append((0 until s.colCount).joinToString("\t") { c ->
                when (val x = s[r, c]?.value) { is CellValue.Number -> NumberFormat.plain(x.value); is CellValue.Text -> clean(x.text); else -> "" }
            }).append('\n')
        }
        File(outDir, "$name.grid.tsv").writeText(g.toString())
        File(outDir, "$name.types.tsv").writeText(t.toString())
        File(outDir, "$name.values.tsv").writeText(v.toString())
    }

    /** The old To Excel: every OCR line is a cell, lines whose centres line up form a row. */
    private fun legacyRows(cells: List<TextLine>): List<List<String>> {
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

    private val LEGACY_NUMERIC = Regex("^-?[0-9][0-9,]*(\\.[0-9]+)?$")
}
