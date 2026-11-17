package com.paperlens.app.excel

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.get
import com.google.mlkit.vision.text.Text
import com.paperlens.app.word.BandTable
import com.paperlens.app.word.GridCell
import com.paperlens.app.word.GridTable
import com.paperlens.app.word.OcrWord
import com.paperlens.app.word.PageAnalysis
import com.paperlens.app.word.PageBuilder
import com.paperlens.app.word.Rule
import com.paperlens.app.word.cellShade
import com.paperlens.app.word.plausible
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Converts one page photo into a [Sheet]: ruled tables cell by cell (merged cells, borders, shading), tables
 * of shaded rows and borderless tables column by column, and the text around them line by line.
 */
internal class SheetBuilder(bitmap: Bitmap, text: Text, private val name: String) {
    /** Light text on a dark board (a green price board) is analysed as a negative: dark ink on light paper. */
    private val inverted = isDark(bitmap)
    private val page = PageBuilder(if (inverted) negative(bitmap) else bitmap, text, emptyList(), "", null)
    private val a: PageAnalysis get() = page.a

    private val cells = HashMap<Long, XCell>()
    private val merges = ArrayList<CellRange>()
    private val rowHeights = HashMap<Int, Float>()
    /** Widest content per column, in Excel character units (tables only; free text may run over). */
    private val widths = HashMap<Int, Float>()
    /** Next free sheet row. */
    private var row = 0
    private var frozenRows = 0
    private var tablesWritten = 0
    private var dec = CellValues.Decimal.POINT
    private var bodyEm = 0f
    private var lineHeight = 0f
    /** Vertical lines outside ruled tables (a divider between two price lists side by side). */
    private var dividers: List<Rule> = emptyList()
    /** What the column finder decided, for the debug probe. */
    private val notes = ArrayList<String>()

    /** A run of words read as one piece of text (split where the gap is wider than a space). */
    private class Piece(val words: List<OcrWord>) {
        val box = RectF(words[0].box).apply { words.forEach { union(it.box) } }
        val cy get() = box.centerY()
    }

    /** A line of pieces at one height. */
    private class Line(val pieces: List<Piece>) {
        val box = RectF(pieces[0].box).apply { pieces.forEach { union(it.box) } }
    }

    private sealed class Block(val box: RectF) {
        class Grid(val g: GridTable, val words: List<OcrWord>) : Block(g.box)
        class Columns(val words: List<OcrWord>, box: RectF, val band: Boolean) : Block(box)
        class Text(val line: Line) : Block(line.box)
    }

    fun build(): SheetResult {
        var words = page.readWords()
        lineHeight = PageAnalysis.median(words.map { it.box.height() }).takeIf { it > 0 } ?: (a.h / 80f)
        bodyEm = PageAnalysis.median(words.flatMap { wd -> List(wd.text.length.coerceAtLeast(1)) { wd.em } }).takeIf { it > 0 } ?: lineHeight
        dec = CellValues.convention(words.groupBy { it.line }.values.map { l -> l.sortedBy { it.box.left }.joinToString(" ") { it.text } })

        // Forms and scanned tables often have heavy rules; allow thicker lines than To Word does.
        val (hRules, vRules) = a.rules(lineHeight, max(8, min(a.w, a.h) / 100))
        // A frame around the page or a box around a label is a single cell, not a table; a box with only
        // bars across (a nutrition label) has its columns in the text, so it is read as a borderless table.
        val grids = a.gridTables(hRules, vRules, lineHeight)
            .filter { it.cells.size > 1 && it.cols.size > 2 && it.plausible(words, lineHeight) }
            .map { g ->
                words = splitAtRules(words, g)
                a.splitByText(g, words)
            }
        val gridBoxes = grids.map { it.box }
        // A band table needs at least 3 shaded rows (fewer is just a highlighted line).
        val bands = a.bandTables(gridBoxes, lineHeight).filter { b -> b.plausible(words, gridBoxes) && b.rowShade.count { it != null } >= 3 }

        val blocks = ArrayList<Block>()
        val free = words.toMutableList()
        for (g in grids) {
            val inside = free.filter { g.box.contains(it.box.centerX(), it.box.centerY()) }
            free.removeAll(inside.toSet())
            blocks += Block.Grid(g, inside)
        }
        for (b in bands) {
            val inside = free.filter { b.box.contains(it.box.centerX(), it.box.centerY()) }
            free.removeAll(inside.toSet())
            if (inside.isNotEmpty()) blocks += Block.Columns(inside, b.box, band = true)
        }
        // Dividers may be heavy bars (a banner's printed line); look for them with a thicker allowance.
        dividers = a.rules(lineHeight, max(8, min(a.w, a.h) / 50)).second.filter { r ->
            r.end - r.start > max(lineHeight * 4, a.h * 0.15f) && gridBoxes.none { it.contains(r.at((r.start + r.end) / 2), (r.start + r.end) / 2) } &&
                // Letter stems stacked line under line can look like a line; a real divider passes between words.
                r.coverage(r.start, r.end) > 0.85f &&
                words.none { wd -> wd.box.centerY() in r.start..r.end && r.at(wd.box.centerY()) in (wd.box.left + 3)..(wd.box.right - 3) }
        }
        blocks += looseBlocks(lines(free))

        val loose = ArrayList<Pair<RectF, List<Float>>>()
        var prevBottom: Float? = null
        for (block in blocks.sortedBy { it.box.top }) {
            // A blank row where the page leaves a clear gap (between a title and a table, two tables…).
            prevBottom?.let { if (block.box.top - it > lineHeight * 1.8f && row > 0) row++ }
            when (block) {
                is Block.Grid -> emitGrid(block.g, block.words)
                is Block.Columns -> loose += block.box to emitColumns(block.words, block.band)
                is Block.Text -> emitLine(block.line)
            }
            prevBottom = max(prevBottom ?: 0f, block.box.bottom)
        }

        val colWidths = widths.mapValues { (_, w) -> w.coerceIn(4f, 60f) }
        val sheet = Sheet(name, cells, merges, colWidths, rowHeights, frozenRows = frozenRows)
        return SheetResult(sheet, SheetDebug(words, grids, bands, loose, hRules + vRules + dividers, inverted, notes))
    }

    // ---- reading the page -----------------------------------------------------------------------------

    private fun isDark(b: Bitmap): Boolean {
        val step = max(1, max(b.width, b.height) / 200)
        var dark = 0
        var light = 0
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b[x, y]
            val l = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            if (l < 110) dark++ else if (l > 170) light++
        }
        return dark > (dark + light) * 0.65f && light > 0
    }

    /**
     * The board as black on white: pixels clearly lighter than the board (text, lines) become ink. A plain
     * colour inversion keeps the board's texture, which the line finder would take for ink everywhere.
     */
    private fun negative(b: Bitmap): Bitmap {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val hist = IntArray(256)
        val luma = IntArray(px.size) { i ->
            val c = px[i]
            ((((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000).also { hist[it]++ }
        }
        fun percentile(q: Float): Int {
            var acc = 0
            for (v in 0..255) { acc += hist[v]; if (acc >= px.size * q) return v }
            return 255
        }
        val board = percentile(0.5f)
        val threshold = board + max(30, ((percentile(0.97f) - board) * 0.4f).toInt())
        for (i in px.indices) px[i] = if (luma[i] > threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * The OCR sometimes reads across a table's column lines ("Dinsd|Woen Donde" over three header cells).
     * Such a word is cut where a line crosses it, in proportion to its characters (wide CJK ones count double).
     */
    private fun splitAtRules(words: List<OcrWord>, g: GridTable): List<OcrWord> {
        val box = g.box
        val out = ArrayList<OcrWord>(words.size)
        for (wd in words) {
            if (wd.text.length < 2 || !box.contains(wd.box.centerX(), wd.box.centerY())) { out += wd; continue }
            val weights = wd.text.map { if (isWide(it)) 2f else 1f }
            val total = weights.sum()
            val charW = wd.box.width() / total
            val cuts = g.cols.map { it.at(wd.box.centerY()) }.filter { it > wd.box.left + charW * 0.6f && it < wd.box.right - charW * 0.6f }
            if (cuts.isEmpty()) { out += wd; continue }
            var start = 0
            var x0 = wd.box.left
            for (x in cuts + wd.box.right) {
                // Characters whose middle lies left of the cut.
                var acc = 0f
                var end = 0
                while (end < wd.text.length && wd.box.left + (acc + weights[end] / 2) * charW < x) { acc += weights[end]; end++ }
                if (x == wd.box.right) end = wd.text.length
                val part = wd.text.substring(start, max(start, end)).trim('|', ' ', '¦')
                if (part.isNotEmpty()) {
                    out += OcrWord(part, RectF(x0, wd.box.top, min(x, wd.box.right), wd.box.bottom), wd.confidence, wd.line).also {
                        it.em = wd.em; it.bold = wd.bold; it.weight = wd.weight; it.stroke = wd.stroke; it.color = wd.color; it.inkLuma = wd.inkLuma
                    }
                }
                start = max(start, end)
                x0 = x
            }
        }
        return out
    }

    /**
     * Lines of text, each split into pieces wherever a gap is clearly wider than a space or a divider line
     * runs between two words. A currency sign standing apart ("Rp.   3000") stays with its amount.
     */
    private fun lines(words: List<OcrWord>): List<Line> = page.rows(words).map { r ->
        val pieces = ArrayList<MutableList<OcrWord>>()
        for (seg in r.segs) {
            var cur = ArrayList<OcrWord>()
            for (wd in seg.words.sortedBy { it.box.left }) {
                val prev = cur.lastOrNull()
                if (prev != null && (wd.box.left - prev.box.right > max(max(prev.em, wd.em), bodyEm * 0.6f) * 1.4f || dividerBetween(prev, wd))) {
                    pieces += cur; cur = ArrayList()
                }
                cur += wd
            }
            if (cur.isNotEmpty()) pieces += cur
        }
        pieces.sortBy { p -> p.minOf { it.box.left } }
        var i = 0
        while (i + 1 < pieces.size) {
            val sign = pieces[i].joinToString("") { it.text }
            val next = pieces[i + 1]
            if (CellValues.isCurrencySign(sign) && next.first().text.firstOrNull()?.isDigit() == true && !dividerBetween(pieces[i].last(), next.first())) {
                pieces[i].addAll(next)
                pieces.removeAt(i + 1)
            } else i++
        }
        Line(pieces.map { Piece(it) })
    }

    private fun dividerBetween(a: OcrWord, b: OcrWord): Boolean {
        val y = (a.box.centerY() + b.box.centerY()) / 2
        return dividers.any { d -> y in d.start..d.end && d.at(y) > a.box.right - 2 && d.at(y) < b.box.left + 2 }
    }

    /**
     * Lines whose parts line up in columns form a table: runs of lines with two or more parts (a one-part
     * line between them is kept, e.g. a category); every other line is text.
     */
    private fun looseBlocks(lines: List<Line>): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].pieces.size < 2) { out += Block.Text(lines[i]); i++; continue }
            var j = i
            while (j + 1 < lines.size) {
                val next = lines[j + 1]
                if (next.box.top - lines[j].box.bottom > lineHeight * 2.5f) break
                if (next.pieces.size >= 2) { j++; continue }
                val after = lines.getOrNull(j + 2)
                if (after != null && after.pieces.size >= 2 && after.box.top - next.box.bottom <= lineHeight * 2.5f) { j++; continue }
                break
            }
            val run = lines.subList(i, j + 1)
            if (run.count { it.pieces.size >= 2 } >= 2) {
                val box = RectF(run[0].box).apply { run.forEach { union(it.box) } }
                out += Block.Columns(run.flatMap { l -> l.pieces.flatMap { it.words } }, box, band = false)
            } else {
                run.forEach { out += Block.Text(it) }
            }
            i = j + 1
        }
        return out
    }

    /**
     * Column breaks: x positions that (almost) no word covers, at least about a character wide. Lines with
     * a single part (a title over the table) are left out; they may cross the breaks.
     */
    private fun columnBreaks(lines: List<Line>): List<Float> {
        val counted = lines.filter { it.pieces.size >= 2 }.ifEmpty { lines }
        val boxes = counted.flatMap { l -> l.pieces.flatMap { p -> p.words.map { it.box } } }
        if (boxes.isEmpty()) return emptyList()
        val x0 = boxes.minOf { it.left }
        val x1 = boxes.maxOf { it.right }
        val step = 2f
        val n = ((x1 - x0) / step).toInt() + 1
        val cover = IntArray(n)
        for (b in boxes) {
            val i0 = ((b.left - x0) / step).toInt().coerceIn(0, n - 1)
            val i1 = ((b.right - x0) / step).toInt().coerceIn(0, n - 1)
            for (i in i0..i1) cover[i]++
        }
        // Headings and notes over a column gap ("2/3 cup (55g)" over a label and its %) may cross it.
        val tol = if (counted.size >= 5) (counted.size * 0.2f).toInt() else 0
        val minGap = max(bodyEm * 0.9f, 6f) / step
        val out = ArrayList<Float>()
        var i = 0
        while (i < n) {
            if (cover[i] > tol) { i++; continue }
            var j = i
            while (j + 1 < n && cover[j + 1] <= tol) j++
            if (i > 0 && j < n - 1 && j - i + 1 >= minGap) {
                // The break goes through the emptiest stretch of the gap.
                val least = (i..j).minOf { cover[it] }
                val first = (i..j).first { cover[it] == least }
                val last = (i..j).last { cover[it] == least }
                out += x0 + (first + last) / 2f * step
            }
            i = j + 1
        }
        return out
    }

    // ---- writing cells ---------------------------------------------------------------------------------

    private fun emitGrid(g: GridTable, words: List<OcrWord>) {
        val nr = g.rows.size - 1
        val nc = g.cols.size - 1
        val regionAt = Array(nr) { arrayOfNulls<GridCell>(nc) }
        for (c in g.cells) for (r in c.r0..c.r1) for (cc in c.c0..c.c1) regionAt[r][cc] = c
        val cellWords = HashMap<GridCell, MutableList<OcrWord>>()
        for (wd in words) {
            val (r, c) = g.locate(wd.box.centerX(), wd.box.centerY()) ?: continue
            val cell = regionAt[r][c] ?: continue
            cellWords.getOrPut(cell) { ArrayList() } += wd
        }
        val top = row
        val edge = Edge(1f, null)
        val texts = HashMap<GridCell, String>()
        for (region in g.cells) texts[region] = cellText(cellWords[region].orEmpty(), g.cellRect(region))
        val header = headerRows(nr) { r -> g.cells.filter { it.r0 == r }.map { texts[it].orEmpty() to cellWords[it].orEmpty() } }
        for (region in g.cells) {
            val rect = g.cellRect(region)
            val ws = cellWords[region].orEmpty()
            val text = texts[region].orEmpty()
            val style = CellStyle(
                bold = region.r0 < header || boldShare(ws) > 0.5f,
                fill = if (inverted) null else a.cellShade(rect),
                hAlign = alignOf(ws, rect),
                vAlign = VAlign.CENTER,
                wrap = text.contains('\n'),
                left = edge, top = edge, right = edge, bottom = edge,
            )
            put(top + region.r0, region.c0, text, style)
            if (region.r1 > region.r0 || region.c1 > region.c0) {
                merges += CellRange(top + region.r0, region.c0, top + region.r1, region.c1)
                // Every cell of a merged area carries the borders, or Excel leaves gaps in them.
                for (r in region.r0..region.r1) for (c in region.c0..region.c1) {
                    if (r != region.r0 || c != region.c0) cells[cellKey(top + r, c)] = XCell(CellValue.Empty, style.copy(numFmt = "General"))
                }
            } else {
                noteWidth(region.c0, text)
            }
            noteHeight(top + region.r0, text, region.r1 - region.r0 + 1)
        }
        finishTable(top, nr, header)
    }

    /** One cell of a column-built table: its lines of text, top to bottom. */
    private class ColCell(val col: Int, var span: Int, val pieces: ArrayList<Piece>) {
        val box get() = RectF(pieces[0].box).apply { pieces.forEach { union(it.box) } }
        val cy get() = box.centerY()
    }

    /**
     * A table without ruled lines (shaded rows or none): columns first, from the gaps between words; then
     * each column's cells top to bottom, lines close together joined into one cell; then the columns are
     * lined up into rows. Rows are matched column by column (each column may sit a little higher or lower,
     * as on a curved book page), against the column with the most cells. Returns the column breaks.
     */
    private fun emitColumns(words: List<OcrWord>, band: Boolean): List<Float> {
        val lines = lines(words)
        val box = RectF(lines[0].box).apply { lines.forEach { union(it.box) } }
        // Divider lines down most of the table are column breaks too.
        val ruled = dividers.filter { d -> d.end - d.start > box.height() * 0.5f && d.at(box.centerY()) in box.left..box.right }.map { it.at(box.centerY()) }
        val allBreaks = (columnBreaks(lines).filter { b -> ruled.none { abs(it - b) < bodyEm * 2 } } + ruled).sorted()
        // Pieces into columns. A piece across a break stays whole when nothing else on its line is in the
        // columns it covers: a heading over several columns (merged), or text running over (in its first column).
        val placedPieces = ArrayList<Triple<Int, Int, Piece>>() // first column, last column, piece
        for (l in lines) {
            val cols = l.pieces.map { p -> p.words.map { columnOf(it.box.centerX(), allBreaks) }.distinct().sorted() }
            l.pieces.forEachIndexed { i, p ->
                val own = cols[i]
                val alone = own.size > 1 && cols.indices.none { j -> j != i && cols[j].any { it in own.first()..own.last() } }
                // A visible gap at the break (wider than a space) splits the piece after all ("7:00 AM  Lv. Tampa").
                val gapAtBreak = own.size > 1 && p.words.sortedBy { it.box.left }.zipWithNext().any { (u, v) ->
                    columnOf(u.box.centerX(), allBreaks) != columnOf(v.box.centerX(), allBreaks) && v.box.left - u.box.right > max(u.em, v.em) * 0.7f
                }
                if (own.size == 1 || (alone && !gapAtBreak)) {
                    placedPieces += Triple(own.first(), if (l.pieces.size == 1) own.last() else own.first(), p)
                } else {
                    for ((c, ws) in p.words.groupBy { columnOf(it.box.centerX(), allBreaks) }) placedPieces += Triple(c, c, Piece(ws))
                }
            }
        }
        // Columns where nothing starts are dropped (a break inside long text of one column), and so is one
        // where hardly anything starts in a long table: its few pieces join the column on their left.
        var used = placedPieces.map { it.first }.distinct().sorted()
        if (lines.size >= 5) {
            // Weak: few pieces start there, and each follows text on its left at about a word's distance.
            val lineOf = HashMap<Piece, Line>().apply { for (l in lines) for (p in l.pieces) put(p, l) }
            fun follows(p: Piece): Boolean {
                val line = lines.firstOrNull { l -> l.pieces.any { q -> q.words.containsAll(p.words) } } ?: lineOf[p] ?: return false
                val left = line.pieces.flatMap { it.words }.filter { it.box.right <= p.box.left + 2 }.maxByOrNull { it.box.right } ?: return false
                return p.box.left - left.box.right < max(left.em, bodyEm) * 2.5f
            }
            val weak = used.drop(1).filter { c ->
                val starting = placedPieces.filter { it.first == c }
                starting.size <= max(2, lines.size / 10) && allBreaks[c - 1] !in ruled && starting.all { follows(it.third) }
            }
            notes += "columns used=$used weak=$weak ruled=$ruled starts=${used.map { c -> placedPieces.count { it.first == c } }}"
            if (weak.isNotEmpty() && weak.size < used.size - 1) {
                for (i in placedPieces.indices) {
                    val (c0, c1, p) = placedPieces[i]
                    if (c0 in weak) placedPieces[i] = Triple(used.last { it < c0 && it !in weak }, c1, p)
                }
                used = used - weak.toSet()
            }
            // A sparse column whose few pieces end where the next column's do (right-aligned headings such
            // as "% Daily Value" over the amounts) belongs to that column.
            val rightOf = { c: Int -> PageAnalysis.median(placedPieces.filter { it.first == c }.map { it.third.box.right }) }
            val toNext = used.dropLast(1).drop(1).filter { c ->
                val starting = placedPieces.filter { it.first == c }
                val next = used[used.indexOf(c) + 1]
                starting.size <= max(2, lines.size / 10) && allBreaks[next - 1] !in ruled &&
                    starting.all { abs(it.third.box.right - rightOf(next)) < bodyEm * 1.5f }
            }
            if (toNext.isNotEmpty()) {
                for (i in placedPieces.indices) {
                    val (c0, c1, p) = placedPieces[i]
                    if (c0 !in toNext) continue
                    val target = used.drop(used.indexOf(c0) + 1).first { it !in toNext }
                    placedPieces[i] = Triple(target, max(c1, target), p)
                }
                used = used - toNext.toSet()
            }
        }
        val breaks = used.drop(1).map { allBreaks[it - 1] }
        val nc = used.size
        val columns = Array(nc) { ArrayList<ColCell>() }
        for ((c0, c1, p) in placedPieces) {
            val first = used.indexOf(c0)
            val last = used.indexOfLast { it <= c1 }
            columns[first] += ColCell(first, last - first + 1, arrayListOf(p))
        }
        // Pieces of one column on the same line belong together.
        for (c in 0 until nc) {
            val merged = ArrayList<ColCell>()
            for (cell in columns[c].sortedBy { it.cy }) {
                val prev = merged.lastOrNull()
                if (prev != null && prev.span == 1 && cell.span == 1 && abs(cell.cy - prev.cy) < lineHeight * 0.5f) {
                    prev.pieces += cell.pieces
                } else merged += cell
            }
            columns[c].clear()
            columns[c] += merged
        }
        val anchor = (0 until nc).maxBy { columns[it].count { cell -> cell.span == 1 } }
        val anchorYs = columns[anchor].filter { it.span == 1 }.map { it.cy }
        val pitch = if (anchorYs.size >= 2) PageAnalysis.median(anchorYs.zipWithNext { p, q -> q - p }) else lineHeight * 2
        // Lines of one cell (wrapped text) are closer together than the table's rows.
        for (c in 0 until nc) {
            val joined = ArrayList<ColCell>()
            for (cell in columns[c]) {
                val prev = joined.lastOrNull()
                val close = prev != null && cell.span == prev.span && cell.box.top - prev.box.bottom < lineHeight * 0.45f &&
                    cell.cy - prev.pieces.last().cy < pitch * 0.7f && pitch > lineHeight * 1.6f
                if (close) prev!!.pieces += cell.pieces else joined += cell
            }
            columns[c].clear()
            columns[c] += joined
        }
        // Rows: the anchor column's cells, then every other cell to the nearest row after its column's offset.
        val rowYs = ArrayList(columns[anchor].map { it.cy })
        val placed = ArrayList<Pair<Float, ColCell>>() // row y (anchor frame), cell
        columns[anchor].forEach { placed += it.cy to it }
        for (c in 0 until nc) {
            if (c == anchor) continue
            var offset = 0f
            repeat(2) {
                val diffs = columns[c].mapNotNull { cell -> rowYs.minByOrNull { abs(cell.cy - offset - it) }?.let { cell.cy - it } }
                    .filter { abs(it) < pitch * 0.6f }
                offset = if (diffs.isEmpty()) 0f else PageAnalysis.median(diffs)
            }
            for (cell in columns[c]) {
                val y = cell.cy - offset
                val nearest = rowYs.minByOrNull { abs(it - y) }
                if (nearest != null && abs(nearest - y) < max(pitch * 0.5f, lineHeight * 0.6f)) placed += nearest to cell
                else { rowYs += y; placed += y to cell }
            }
        }
        val ordered = rowYs.distinct().sorted()
        val table = Array(ordered.size) { arrayOfNulls<ColCell>(nc) }
        for ((y, cell) in placed) {
            val r = ordered.indexOf(y)
            val existing = table[r][cell.col]
            if (existing == null) table[r][cell.col] = cell else existing.pieces += cell.pieces
        }
        val top = row
        val texts = table.map { line -> line.map { cell -> cell?.let { pieceText(it.pieces) } } }
        val header = headerRows(table.size) { r -> table[r].mapIndexedNotNull { c, cell -> cell?.let { texts[r][c].orEmpty() to it.pieces.flatMap { p -> p.words } } } }
        table.forEachIndexed { r, line ->
            line.forEachIndexed { c, cell ->
                if (cell == null) return@forEachIndexed
                val text = texts[r][c].orEmpty()
                val ws = cell.pieces.flatMap { it.words }
                val fill = if (band && !inverted) a.cellShade(RectF(cell.box).apply { inset(-lineHeight * 0.3f, -lineHeight * 0.25f) }) else null
                put(
                    top + r, c, text,
                    CellStyle(
                        bold = r < header || boldShare(ws) > 0.5f, fill = fill,
                        hAlign = if (cell.span > 1) HAlign.CENTER else HAlign.GENERAL, vAlign = VAlign.CENTER, wrap = text.contains('\n'),
                    ),
                )
                if (cell.span > 1) merges += CellRange(top + r, c, top + r, c + cell.span - 1) else noteWidth(c, text)
                noteHeight(top + r, text, 1)
            }
        }
        finishTable(top, table.size, header)
        return breaks
    }

    /** A line of text outside tables: in column A, or its parts across columns when it has gaps. */
    private fun emitLine(l: Line) {
        val em = PageAnalysis.median(l.pieces.flatMap { p -> p.words.map { it.em } })
        val big = bodyEm > 0 && em > bodyEm * 1.35f
        val size = if (big) (11f * em / bodyEm).roundToInt().toFloat().coerceIn(12f, 24f) else null
        l.pieces.forEachIndexed { i, p ->
            put(row, i, joinWords(p.words), CellStyle(bold = boldShare(p.words) > 0.5f, fontSize = size))
        }
        if (size != null) rowHeights[row] = size * 1.4f
        row++
    }

    /** After a table: move below it, and freeze the header of a long first table. */
    private fun finishTable(top: Int, rows: Int, header: Int) {
        if (tablesWritten == 0 && header > 0 && rows >= 10 && top <= 4) frozenRows = top + header
        tablesWritten++
        row = top + rows
    }

    /**
     * How many rows at the top of a table are its header: the first row when it is bold while the next
     * isn't, or when it is all text over columns that hold numbers below.
     */
    private fun headerRows(rows: Int, row: (Int) -> List<Pair<String, List<OcrWord>>>): Int {
        if (rows < 3) return 0
        val first = row(0).filter { it.first.isNotEmpty() }
        if (first.size < 2) return 0
        val firstBold = first.all { boldShare(it.second) > 0.5f }
        val nextBold = row(1).filter { it.first.isNotEmpty() }.let { r -> r.isNotEmpty() && r.all { boldShare(it.second) > 0.5f } }
        if (firstBold && !nextBold) return 1
        val firstText = first.all { CellValues.parse(it.first, dec) == null }
        val below = (1 until rows).flatMap { r -> row(r).map { it.first } }.filter { it.isNotEmpty() }
        val numbers = below.count { CellValues.parse(it, dec) != null }
        return if (firstText && below.isNotEmpty() && numbers * 4 >= below.size) 1 else 0
    }

    private fun put(r: Int, c: Int, text: String, style: CellStyle) {
        if (text.isEmpty()) {
            if (style != CellStyle.DEFAULT) cells[cellKey(r, c)] = XCell(CellValue.Empty, style)
            return
        }
        val number = if (text.contains('\n')) null else CellValues.parse(text, dec)
        cells[cellKey(r, c)] = if (number != null) {
            number.copy(style = style.copy(numFmt = number.style.numFmt, wrap = false))
        } else {
            XCell(CellValue.Text(text), style)
        }
    }

    /** Column width from the longest line, counting wide (CJK) characters double. */
    private fun noteWidth(c: Int, text: String) {
        val longest = text.lineSequence().maxOfOrNull { line -> line.sumOf { (if (isWide(it)) 2.0 else 1.0) * if (it.isUpperCase()) 1.15 else 1.0 } }?.toFloat() ?: 0f
        if (longest > 0) widths[c] = max(widths[c] ?: 0f, longest * 1.05f + 2f)
    }

    private fun noteHeight(r: Int, text: String, rowsSpanned: Int) {
        val lines = text.count { it == '\n' } + 1
        if (lines > 1 && rowsSpanned == 1) rowHeights[r] = max(rowHeights[r] ?: 15f, lines * 15f)
    }

    // ---- text ------------------------------------------------------------------------------------------

    private fun columnOf(x: Float, breaks: List<Float>): Int {
        var c = 0
        while (c < breaks.size && x > breaks[c]) c++
        return c
    }

    /** Text of a column-built cell: its pieces line by line (pieces on one line joined with a space). */
    private fun pieceText(pieces: List<Piece>): String {
        val sorted = pieces.sortedBy { it.cy }
        val out = ArrayList<MutableList<Piece>>()
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && abs(last[0].cy - p.cy) < lineHeight * 0.5f) last += p else out += mutableListOf(p)
        }
        return out.joinToString("\n") { line -> line.sortedBy { it.box.left }.joinToString(" ") { joinWords(it.words) } }.trim()
    }

    /**
     * The text of a ruled cell: its lines top to bottom. A line that reaches the cell's right side was
     * wrapped and continues on the next line (joined with a space); one that ends early is a line break.
     */
    private fun cellText(words: List<OcrWord>, rect: RectF): String {
        if (words.isEmpty()) return ""
        val rows = page.rows(words)
        val sb = StringBuilder()
        rows.forEachIndexed { i, r ->
            val line = r.segs.joinToString(" ") { joinWords(it.words) }
            if (i > 0) {
                val prev = rows[i - 1]
                val wrapped = rect.right - prev.box.right < rect.width() * 0.22f
                sb.append(if (!wrapped) "\n" else if (isWide(sb.lastOrNull()) && isWide(line.firstOrNull())) "" else " ")
            }
            sb.append(line)
        }
        return sb.toString().trim()
    }

    /** Words of one line: a space between them, except inside Chinese / Japanese text. */
    private fun joinWords(words: List<OcrWord>): String {
        val sorted = words.sortedBy { it.box.left }
        val sb = StringBuilder()
        sorted.forEachIndexed { i, wd ->
            if (i > 0) {
                val prev = sorted[i - 1]
                val gap = wd.box.left - prev.box.right
                val wide = isWide(prev.text.lastOrNull()) && isWide(wd.text.firstOrNull())
                if (!(wide && gap < max(prev.em, wd.em) * 0.8f)) sb.append(' ')
            }
            sb.append(wd.text)
        }
        return sb.toString()
    }

    private fun isWide(c: Char?): Boolean {
        c ?: return false
        return c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0x3040..0x30FF || c.code in 0xFF00..0xFFEF
    }

    private fun boldShare(words: List<OcrWord>): Float = if (words.isEmpty()) 0f else words.count { it.bold }.toFloat() / words.size

    /** Centred or right-aligned text keeps its place; numbers keep Excel's own (right) alignment. */
    private fun alignOf(words: List<OcrWord>, rect: RectF): HAlign {
        if (words.isEmpty()) return HAlign.GENERAL
        val left = words.minOf { it.box.left } - rect.left
        val right = rect.right - words.maxOf { it.box.right }
        return when {
            abs(left - right) < rect.width() * 0.08f && left > rect.width() * 0.12f -> HAlign.CENTER
            right < rect.width() * 0.06f && left > rect.width() * 0.3f -> HAlign.RIGHT
            else -> HAlign.GENERAL
        }
    }
}
