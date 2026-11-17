package com.paperlens.app.ui.excel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paperlens.app.excel.Sheet
import com.paperlens.app.excel.Workbook
import com.paperlens.app.excel.XCell
import com.paperlens.app.excel.XlsxEditor
import com.paperlens.app.excel.cellKey

/**
 * What the Excel screen shows and changes: the workbook as last saved, the cell edits made since (kept
 * apart, so saving writes only them into the file), the open sheet and the selected cell.
 */
class SheetEditor(initial: Workbook) {
    var saved by mutableStateOf(initial)
        private set

    /** The saved workbook with the pending edits applied: what is drawn. */
    var book by mutableStateOf(initial)
        private set

    /** Sheet index → cell key → new cell (null = cleared). */
    var edits by mutableStateOf<Map<Int, Map<Long, XCell?>>>(emptyMap())
        private set

    var sheetIndex by mutableIntStateOf(0)
    var selected by mutableStateOf<Pair<Int, Int>?>(null)

    val dirty: Boolean get() = edits.isNotEmpty()
    val sheet: Sheet get() = book.sheets[sheetIndex.coerceIn(0, book.sheets.size - 1)]

    /** Sets the cell from what was typed; returns the new cell (null when cleared). */
    fun set(row: Int, col: Int, text: String): XCell? {
        val key = cellKey(row, col)
        val index = sheetIndex
        val cell = XlsxEditor.parseInput(text, sheet.cells[key])
        val sheetEdits = (edits[index] ?: emptyMap()) + (key to cell)
        edits = edits + (index to sheetEdits)
        val cells = HashMap(sheet.cells)
        if (cell == null) cells.remove(key) else cells[key] = cell
        book = book.copy(sheets = book.sheets.mapIndexed { i, s -> if (i == index) s.copy(cells = cells) else s })
        return cell
    }

    /** After a save (with the workbook read back from the file) or to drop the edits. */
    fun reset(to: Workbook) {
        saved = to
        book = to
        edits = emptyMap()
        sheetIndex = sheetIndex.coerceIn(0, to.sheets.size - 1)
    }
}
