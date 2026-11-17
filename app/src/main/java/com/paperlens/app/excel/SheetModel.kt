package com.paperlens.app.excel

/**
 * An Excel workbook as the app shows and writes it: sheets of cells with their values, the formatting the
 * viewer draws (fonts, fills, borders, alignment, number formats), merged areas, column widths and row heights.
 */
data class Workbook(val sheets: List<Sheet>, val date1904: Boolean = false)

/** Cell address packed into a Long: row (0-based) in the high bits, column (0-based) in the low 16. */
fun cellKey(row: Int, col: Int): Long = (row.toLong() shl 16) or col.toLong()
fun keyRow(key: Long): Int = (key shr 16).toInt()
fun keyCol(key: Long): Int = (key and 0xFFFF).toInt()

class Sheet(
    val name: String,
    val cells: Map<Long, XCell>,
    val merges: List<CellRange> = emptyList(),
    /** Column widths in Excel character units, by 0-based column. */
    val colWidths: Map<Int, Float> = emptyMap(),
    /** Row heights in points, by 0-based row. */
    val rowHeights: Map<Int, Float> = emptyMap(),
    val defaultColWidth: Float = 8.43f,
    val defaultRowHeight: Float = 15f,
    /** Rows (and columns) kept on screen while scrolling ("freeze panes"). */
    val frozenRows: Int = 0,
    val frozenCols: Int = 0,
    val hiddenCols: Set<Int> = emptySet(),
    val hiddenRows: Set<Int> = emptySet(),
) {
    /** One past the last used row / column. */
    val rowCount: Int = maxOf(cells.keys.maxOfOrNull { keyRow(it) + 1 } ?: 0, merges.maxOfOrNull { it.r1 + 1 } ?: 0)
    val colCount: Int = maxOf(cells.keys.maxOfOrNull { keyCol(it) + 1 } ?: 0, merges.maxOfOrNull { it.c1 + 1 } ?: 0)

    operator fun get(row: Int, col: Int): XCell? = cells[cellKey(row, col)]

    /** Rows without a set height grow to fit a larger font, as in Excel. */
    private val autoHeights: Map<Int, Float> = HashMap<Int, Float>().also { map ->
        for ((k, cell) in cells) {
            val size = cell.style.fontSize ?: continue
            if (size <= 11.5f || keyRow(k) in rowHeights) continue
            map[keyRow(k)] = maxOf(map[keyRow(k)] ?: 0f, size * 1.32f)
        }
    }

    fun colWidth(c: Int): Float = if (c in hiddenCols) 0f else colWidths[c] ?: defaultColWidth
    fun rowHeight(r: Int): Float = if (r in hiddenRows) 0f else rowHeights[r] ?: maxOf(defaultRowHeight, autoHeights[r] ?: 0f)

    fun copy(cells: Map<Long, XCell> = this.cells) = Sheet(
        name, cells, merges, colWidths, rowHeights, defaultColWidth, defaultRowHeight, frozenRows, frozenCols, hiddenCols, hiddenRows,
    )
}

/** A rectangle of cells, inclusive, 0-based. */
data class CellRange(val r0: Int, val c0: Int, val r1: Int, val c1: Int) {
    fun contains(r: Int, c: Int) = r in r0..r1 && c in c0..c1
}

data class XCell(val value: CellValue, val style: CellStyle = CellStyle.DEFAULT, val formula: String? = null)

sealed interface CellValue {
    data class Text(val text: String) : CellValue
    data class Number(val value: Double) : CellValue
    data class Bool(val value: Boolean) : CellValue
    data class Error(val code: String) : CellValue
    data object Empty : CellValue
}

enum class HAlign { GENERAL, LEFT, CENTER, RIGHT }
enum class VAlign { TOP, CENTER, BOTTOM }

/** A cell edge line; [color] 0xRRGGBB or null for automatic (black). */
data class Edge(val width: Float = 1f, val color: Int? = null)

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Points; null = the workbook default (11). */
    val fontSize: Float? = null,
    val fontColor: Int? = null,
    val fill: Int? = null,
    val hAlign: HAlign = HAlign.GENERAL,
    val vAlign: VAlign = VAlign.BOTTOM,
    val wrap: Boolean = false,
    /** Excel number format code ("General", "#,##0.00", "0%", "yyyy-mm-dd"…). */
    val numFmt: String = "General",
    val left: Edge? = null,
    val top: Edge? = null,
    val right: Edge? = null,
    val bottom: Edge? = null,
) {
    companion object {
        val DEFAULT = CellStyle()
    }
}

/** 0 → A, 25 → Z, 26 → AA … */
fun columnName(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        sb.insert(0, 'A' + rem)
        n = (n - 1) / 26
    }
    return sb.toString()
}

fun cellName(row: Int, col: Int) = columnName(col) + (row + 1)

/** "B12" → (11, 1); null when it isn't a cell reference. */
fun parseCellRef(ref: String): Pair<Int, Int>? {
    var i = 0
    var col = 0
    val s = ref.replace("$", "")
    while (i < s.length && s[i].isLetter()) { col = col * 26 + (s[i].uppercaseChar() - 'A' + 1); i++ }
    if (i == 0 || i == s.length) return null
    val row = s.substring(i).toIntOrNull() ?: return null
    return (row - 1) to (col - 1)
}

/** What the cell shows: its value in its number format. */
fun Workbook.display(cell: XCell?): String = when (val v = cell?.value) {
    null -> ""
    // A formula without a stored result (written by a program that doesn't calculate) shows itself.
    CellValue.Empty -> cell.formula?.let { "=$it" } ?: ""
    is CellValue.Text -> v.text
    is CellValue.Number -> NumberFormat.format(v.value, cell.style.numFmt, date1904)
    is CellValue.Bool -> if (v.value) "TRUE" else "FALSE"
    is CellValue.Error -> v.code
}

/** What editing the cell starts from: the formula, or the value without number formatting. */
fun XCell.editText(): String = formula?.let { "=$it" } ?: when (val v = value) {
    is CellValue.Text -> v.text
    is CellValue.Number -> NumberFormat.plain(v.value)
    is CellValue.Bool -> if (v.value) "TRUE" else "FALSE"
    is CellValue.Error -> v.code
    CellValue.Empty -> ""
}

/** Every sheet as tab-separated text (for "Share as text"). */
fun Workbook.plainText(): String = sheets.joinToString("\n\n") { s ->
    (0 until s.rowCount).joinToString("\n") { r -> (0 until s.colCount).joinToString("\t") { c -> display(s[r, c]) }.trimEnd('\t') }
        .let { if (sheets.size > 1) s.name + "\n" + it else it }
}
