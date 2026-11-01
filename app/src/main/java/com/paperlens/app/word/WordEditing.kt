package com.paperlens.app.word

import com.paperlens.app.S
import com.paperlens.app.R

import kotlin.math.max
import kotlin.math.min

/**
 * Editing operations on the Word model: typing into a paragraph (keeping each character's style and
 * the pictures in it), styling a selection, paragraph styles, lists, and table rows / columns.
 */
object WordEditing {

    /** A paragraph as characters with one style each, plus pictures anchored at character offsets. */
    private class Chars(p: WParagraph) {
        val text = StringBuilder()
        val styles = ArrayList<RunStyle>()
        val images = ArrayList<Pair<Int, WRun>>()
        val mark: RunStyle = p.firstStyle

        init {
            for (r in p.runs) {
                if (r.image != null) images += text.length to r
                else for (c in r.text) { text.append(c); styles += r.style }
            }
        }

        fun runs(): List<WRun> {
            val out = ArrayList<WRun>()
            var imgIndex = 0
            val sortedImages = images.sortedBy { it.first }
            var i = 0
            while (i < text.length || imgIndex < sortedImages.size) {
                while (imgIndex < sortedImages.size && sortedImages[imgIndex].first <= i) {
                    out += sortedImages[imgIndex].second; imgIndex++
                }
                if (i >= text.length) break
                val style = styles[i]
                val start = i
                while (i < text.length && styles[i] == style &&
                    (imgIndex >= sortedImages.size || sortedImages[imgIndex].first > i || i == start)
                ) i++
                out += WRun(text.substring(start, i), style)
            }
            if (out.none { it.image == null }) out.add(0, WRun("", mark))
            return out
        }
    }

    /**
     * The paragraph after its text changed from [oldText] to [newText] (one edit: the common start and
     * end are kept). New characters take [typing] or the style of the character before them.
     */
    fun edit(p: WParagraph, oldText: String, newText: String, typing: RunStyle? = null): WParagraph {
        val c = Chars(p)
        if (c.text.toString() != oldText) {
            // Out of sync (should not happen): replace everything, keeping the first style.
            return p.copy(runs = listOf(WRun(newText, typing ?: p.firstStyle)) + p.runs.filter { it.image != null })
        }
        var start = 0
        val maxPrefix = min(oldText.length, newText.length)
        while (start < maxPrefix && oldText[start] == newText[start]) start++
        var endOld = oldText.length
        var endNew = newText.length
        while (endOld > start && endNew > start && oldText[endOld - 1] == newText[endNew - 1]) { endOld--; endNew-- }
        val style = typing ?: when {
            start > 0 -> c.styles[start - 1]
            c.styles.isNotEmpty() -> c.styles[0]
            else -> c.mark
        }
        val delta = (endNew - start) - (endOld - start)
        c.text.replace(start, endOld, newText.substring(start, endNew))
        repeat(endOld - start) { c.styles.removeAt(start) }
        repeat(endNew - start) { c.styles.add(start, style) }
        for (k in c.images.indices) {
            val (off, run) = c.images[k]
            c.images[k] = when {
                off <= start -> off
                off >= endOld -> off + delta
                else -> start
            } to run
        }
        return p.copy(runs = c.runs())
    }

    /** Applies [change] to the characters in [start, end) (all of them when the range is empty). */
    fun style(p: WParagraph, start: Int, end: Int, change: (RunStyle) -> RunStyle): WParagraph {
        val c = Chars(p)
        if (c.text.isEmpty()) return p.copy(runs = listOf(WRun("", change(c.mark))) + p.runs.filter { it.image != null })
        val (s, e) = if (start >= end) 0 to c.text.length else max(0, start) to min(c.text.length, end)
        for (i in s until e) c.styles[i] = change(c.styles[i])
        return p.copy(runs = c.runs())
    }

    /** True when every character in the range (or the paragraph) matches. */
    fun all(p: WParagraph, start: Int, end: Int, test: (RunStyle) -> Boolean): Boolean {
        val c = Chars(p)
        if (c.text.isEmpty()) return test(c.mark)
        val (s, e) = if (start >= end) 0 to c.text.length else max(0, start) to min(c.text.length, end)
        return (s until e).all { test(c.styles[it]) }
    }

    /** Style of the character before [offset] (what typing there would use). */
    fun styleAt(p: WParagraph, offset: Int): RunStyle {
        val c = Chars(p)
        return when {
            c.styles.isEmpty() -> c.mark
            offset <= 0 -> c.styles[0]
            else -> c.styles[min(offset, c.styles.size) - 1]
        }
    }

    /** Plain text of the editable part of a paragraph (pictures excluded). */
    fun text(p: WParagraph): String = p.runs.filter { it.image == null }.joinToString("") { it.text }

    // ---- paragraph styles ----------------------------------------------------------------------

    enum class ParagraphKind(private val labelRes: Int, val styleId: String?, val size: Float?, val bold: Boolean?) {
        NORMAL(R.string.normal, null, null, null),
        TITLE(R.string.title, "Title", 24f, true),
        HEADING1(R.string.heading_1, "Heading1", 16f, true),
        HEADING2(R.string.heading_2, "Heading2", 14f, true),
        HEADING3(R.string.heading_3, "Heading3", 12f, true),
    ;
    val label: String get() = S.str(labelRes)
}

    fun kindOf(p: WParagraph): ParagraphKind = ParagraphKind.entries.firstOrNull { it.styleId != null && it.styleId == p.styleId } ?: ParagraphKind.NORMAL

    fun withKind(p: WParagraph, kind: ParagraphKind): WParagraph {
        val styled = style(p, 0, 0) { s ->
            when (kind) {
                ParagraphKind.NORMAL -> if (kindOf(p) != ParagraphKind.NORMAL) s.copy(size = RunStyle.DEFAULT_SIZE, bold = false) else s
                else -> s.copy(size = kind.size!!, bold = kind.bold!!)
            }
        }
        return styled.copy(styleId = kind.styleId)
    }

    /** Cycles no list → bullets → numbers → no list. */
    fun cycleList(p: WParagraph): WParagraph = when {
        p.list == null -> p.copy(list = WList(numbered = false), indentLeft = max(p.indentLeft, 360), indentFirst = -360)
        !p.list.numbered -> p.copy(list = WList(numbered = true))
        else -> p.copy(list = null, indentFirst = 0, indentLeft = max(0, p.indentLeft - 360))
    }

    /**
     * Labels for list items: our own numbered lists are counted in document order (like Word, which
     * keeps counting within one list definition); imported lists keep the label they were read with.
     */
    fun listLabels(doc: WordDocument): Map<BlockPath, String> {
        val out = HashMap<BlockPath, String>()
        val counters = HashMap<Int, IntArray>()
        for ((path, p) in doc.paragraphs()) {
            val l = p.list ?: continue
            if (!l.numbered) { out[path] = l.label; continue }
            if (l.numId != null && l.numId != DocxWriter.NUM_ID_DECIMAL) { out[path] = l.label; continue }
            val c = counters.getOrPut(DocxWriter.NUM_ID_DECIMAL) { IntArray(9) }
            val lvl = l.level.coerceIn(0, 8)
            c[lvl]++
            for (i in lvl + 1 until 9) c[i] = 0
            out[path] = when (lvl % 3) {
                1 -> ('a' + (c[lvl] - 1) % 26) + "."
                2 -> roman(c[lvl]).lowercase() + "."
                else -> "${c[lvl]}."
            }
        }
        return out
    }

    private fun roman(n: Int): String {
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return sb.toString()
    }

    // ---- tables ----------------------------------------------------------------------------------

    fun newTable(columns: Int, rows: Int, width: Int, style: RunStyle): WTable {
        val w = width / columns
        return WTable(
            grid = List(columns) { w },
            rows = List(rows) { WRow(List(columns) { WCell(listOf(WParagraph(runs = listOf(WRun("", style))))) }) },
        )
    }

    /** A row copied from [r]'s layout, empty; vertical merges running through it continue. */
    fun insertRow(t: WTable, r: Int, below: Boolean): WTable {
        val src = t.rows[r]
        val next = if (below) t.rows.getOrNull(r + 1) else src
        val starts = src.gridStarts()
        val cells = src.cells.mapIndexed { i, cell ->
            val col = starts[i]
            // Inside a merge (the row below continues it): keep merging.
            val continues = next?.let { n -> n.cellAtGrid(col).let { j -> j >= 0 && n.cells[j].vMerge == VMerge.CONTINUE } } == true
            val inside = if (below) continues && cell.vMerge != VMerge.NONE else cell.vMerge == VMerge.CONTINUE
            WCell(
                blocks = listOf(WParagraph(runs = listOf(WRun("", firstStyle(cell))))),
                span = cell.span,
                vMerge = if (inside) VMerge.CONTINUE else VMerge.NONE,
                shading = cell.shading,
                vAlign = cell.vAlign,
                borders = cell.borders,
            )
        }
        val rows = t.rows.toMutableList()
        rows.add(if (below) r + 1 else r, WRow(cells, height = null))
        return t.copy(rows = rows)
    }

    /** Removes row [r]; a merge that starts there moves its start (and content) to the next row. */
    fun deleteRow(t: WTable, r: Int): WTable? {
        if (t.rows.size <= 1) return null
        val rows = t.rows.toMutableList()
        val row = rows[r]
        val next = rows.getOrNull(r + 1)
        if (next != null) {
            val starts = row.gridStarts()
            val nextCells = next.cells.toMutableList()
            row.cells.forEachIndexed { i, cell ->
                if (cell.vMerge != VMerge.RESTART) return@forEachIndexed
                val j = next.cellAtGrid(starts[i])
                if (j >= 0 && nextCells[j].vMerge == VMerge.CONTINUE) {
                    val following = rows.getOrNull(r + 2)?.let { f -> f.cellAtGrid(starts[i]).let { k -> k >= 0 && f.cells[k].vMerge == VMerge.CONTINUE } } == true
                    nextCells[j] = nextCells[j].copy(blocks = cell.blocks, vMerge = if (following) VMerge.RESTART else VMerge.NONE)
                }
            }
            rows[r + 1] = next.copy(cells = nextCells)
        }
        rows.removeAt(r)
        return t.copy(rows = rows)
    }

    /** Adds a grid column next to [col] (halving its width); cells spanning across it widen. */
    fun insertColumn(t: WTable, col: Int, after: Boolean): WTable {
        val grid = t.grid.toMutableList()
        val half = max(240, grid[col] / 2)
        grid[col] = max(240, grid[col] - half)
        val at = if (after) col + 1 else col
        grid.add(at, half)
        val rows = t.rows.map { row ->
            val cells = row.cells.toMutableList()
            val i = row.cellAtGrid(col)
            if (i < 0) return@map row
            val start = row.gridStarts()[i]
            val cell = cells[i]
            val inside = if (after) col < start + cell.span - 1 else col > start
            if (inside) {
                cells[i] = cell.copy(span = cell.span + 1)
            } else {
                val fresh = WCell(listOf(WParagraph(runs = listOf(WRun("", firstStyle(cell))))), shading = cell.shading, borders = cell.borders)
                cells.add(if (after) i + 1 else i, fresh)
            }
            row.copy(cells = cells)
        }
        return t.copy(grid = grid, rows = rows)
    }

    /** Removes grid column [col]; cells spanning it get narrower, others are removed. */
    fun deleteColumn(t: WTable, col: Int): WTable? {
        if (t.grid.size <= 1) return null
        val grid = t.grid.toMutableList().also { it.removeAt(col) }
        val rows = t.rows.map { row ->
            val cells = row.cells.toMutableList()
            val i = row.cellAtGrid(col)
            if (i < 0) return@map row
            if (cells[i].span > 1) cells[i] = cells[i].copy(span = cells[i].span - 1) else cells.removeAt(i)
            row.copy(cells = cells.ifEmpty { listOf(WCell()) })
        }
        return t.copy(grid = grid, rows = rows)
    }

    private fun firstStyle(cell: WCell): RunStyle =
        (cell.blocks.firstOrNull { it is WParagraph } as? WParagraph)?.firstStyle ?: RunStyle()
}
