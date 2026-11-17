package com.paperlens.app.word

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One recognised word on the page (bitmap pixels) plus what the pixels say about its look. */
internal class OcrWord(val text: String, val box: RectF, val confidence: Float, val line: Int) {
    /** Font size estimate in pixels (em height), shared by the words of a line. */
    var em = 0f
    /** Handwritten: shown as a small picture of itself, in its place in the line. */
    var handwritten = false

    /** Brightness of the ink as on white paper (pencil handwriting is light). */
    var inkLuma = 0

    /** Typical stroke thickness in pixels. */
    var stroke = 0f
    /** Stroke thickness relative to [em]. */
    var weight = 0f
    var bold = false
    /** 0xRRGGBB, or null for black / very dark grey. */
    var color: Int? = null
}

/** A straight ruled line: y = a + b·x for horizontal rules, x = a + b·y for vertical ones. */
internal class Rule(val horizontal: Boolean) {
    /** Covered stretches along the line, [start, end] pairs in the running coordinate. */
    val spans = ArrayList<FloatArray>()
    private var n = 0.0; private var sx = 0.0; private var sy = 0.0; private var sxx = 0.0; private var sxy = 0.0
    var a = 0f; var b = 0f
    var thickness = 1f
    /** Slope imposed for short pieces (the page's skew); kept through merges. */
    private var fixedSlope: Float? = null
    var start = Float.MAX_VALUE
    var end = -Float.MAX_VALUE

    /** Adds a sample: [along] = running coordinate, [across] = position across the line, weighted. */
    fun add(along: Float, across: Float, weight: Double = 1.0) {
        n += weight; sx += along * weight; sy += across * weight; sxx += along.toDouble() * along * weight; sxy += along.toDouble() * across * weight
    }

    fun fit() {
        fixedSlope?.let { slope ->
            b = slope
            if (n > 0) a = ((sy - slope * sx) / n).toFloat()
            return
        }
        val d = n * sxx - sx * sx
        if (n > 0 && abs(d) > 1e-6 && end - start > 1) {
            b = ((n * sxy - sx * sy) / d).toFloat().coerceIn(-0.05f, 0.05f)
            a = ((sy - b * sx) / n).toFloat()
        } else if (n > 0) {
            b = 0f
            a = (sy / n).toFloat()
        }
    }

    fun at(along: Float) = a + b * along

    /** Uses [slope] instead of the fitted one (short pieces give unreliable slopes). */
    fun fixSlope(slope: Float) {
        fixedSlope = slope
        fit()
    }

    fun merge(o: Rule) {
        n += o.n; sx += o.sx; sy += o.sy; sxx += o.sxx; sxy += o.sxy
        // A merged rule keeps an imposed slope only while it stays short.
        if (fixedSlope == null && n == o.n) fixedSlope = o.fixedSlope
        spans += o.spans
        start = min(start, o.start); end = max(end, o.end)
        thickness = max(thickness, o.thickness)
        fit()
    }

    /** Fraction of [from, to] covered by this rule's spans. */
    fun coverage(from: Float, to: Float): Float {
        if (to <= from) return 1f
        val parts = spans.mapNotNull { s -> if (s[1] < from || s[0] > to) null else floatArrayOf(max(s[0], from), min(s[1], to)) }
            .sortedBy { it[0] }
        var covered = 0f
        var curS = Float.NaN
        var curE = Float.NaN
        for (p in parts) {
            if (curS.isNaN()) { curS = p[0]; curE = p[1] } else if (p[0] <= curE + 2) curE = max(curE, p[1]) else {
                covered += curE - curS; curS = p[0]; curE = p[1]
            }
        }
        if (!curS.isNaN()) covered += curE - curS
        return covered / (to - from)
    }
}

/** A table drawn with lines: its row and column rules and the cells (merged ones as spans). */
internal class GridTable(val rows: List<Rule>, val cols: List<Rule>, val cells: List<GridCell>) {
    /** Centre of the table (rules are slightly slanted, so positions are read at the centre). */
    val cx: Float
    val cy: Float

    init {
        val x0 = (rows.first().start + rows.first().end) / 2
        cy = (rows.first().at(x0) + rows.last().at(x0)) / 2
        cx = (cols.first().at(cy) + cols.last().at(cy)) / 2
    }

    val box: RectF get() = RectF(
        min(cols.first().at(rows.first().at(cx)), cols.first().at(rows.last().at(cx))),
        min(rows.first().at(cols.first().at(cy)), rows.first().at(cols.last().at(cy))),
        max(cols.last().at(rows.first().at(cx)), cols.last().at(rows.last().at(cx))),
        max(rows.last().at(cols.first().at(cy)), rows.last().at(cols.last().at(cy))),
    )

    /** Column rule positions at the table's middle height, and row rule positions at its middle. */
    fun colX(c: Int) = cols[c].at(cy)
    fun rowY(r: Int) = rows[r].at(cx)

    /** Grid row and column containing the point, or null when outside. */
    fun locate(x: Float, y: Float): Pair<Int, Int>? {
        var row = -1
        for (r in 0 until rows.size - 1) if (y >= rows[r].at(x) && y < rows[r + 1].at(x)) { row = r; break }
        var col = -1
        for (c in 0 until cols.size - 1) if (x >= cols[c].at(y) && x < cols[c + 1].at(y)) { col = c; break }
        return if (row >= 0 && col >= 0) row to col else null
    }

    fun cellRect(cell: GridCell): RectF {
        val midX = (colX(cell.c0) + colX(cell.c1 + 1)) / 2
        val midY = (rowY(cell.r0) + rowY(cell.r1 + 1)) / 2
        return RectF(cols[cell.c0].at(midY), rows[cell.r0].at(midX), cols[cell.c1 + 1].at(midY), rows[cell.r1 + 1].at(midX))
    }
}

/** A cell region of a [GridTable] in grid units (inclusive). */
internal data class GridCell(val r0: Int, val c0: Int, val r1: Int, val c1: Int)

/** A borderless table whose rows are shaded bands (alternating grey / white). */
internal class BandTable(val box: RectF, val rowEdges: FloatArray, val rowShade: Array<Int?>)

internal fun Rect.toRectF() = RectF(this)

/** Disjoint sets of the items 0 until [size] (union-find): [union] joins two sets, [find] names an item's set. */
internal class DisjointSets(size: Int) {
    private val parent = IntArray(size) { it }

    fun find(item: Int): Int {
        var x = item
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }

    fun union(a: Int, b: Int) {
        parent[find(a)] = find(b)
    }
}
