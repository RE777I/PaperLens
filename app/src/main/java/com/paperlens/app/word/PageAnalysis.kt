package com.paperlens.app.word

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

/**
 * Pixel-level layout analysis of one page: paper brightness, ink, ruled lines, tables, shaded bands,
 * pictures, and the size / weight / colour of each word.
 */
internal class PageAnalysis(val bitmap: Bitmap) {
    val w = bitmap.width
    val h = bitmap.height
    private val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    val gray = ByteArray(w * h)
    /** Local paper brightness (text removed), per pixel. */
    val bg = ByteArray(w * h)
    /** Clearly darker than the paper around it. */
    val ink = BooleanArray(w * h)
    val paper: Int

    init {
        for (i in px.indices) {
            val c = px[i]
            gray[i] = ((((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000).toByte()
        }
        // Paper: cell maxima (text removed), spread one cell, bilinear.
        val cell = max(8, min(w, h) / 60)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val grid = IntArray(gw * gh)
        for (y in 0 until h) for (x in 0 until w) {
            val g = gray[y * w + x].toInt() and 0xFF
            val gi = (y / cell) * gw + x / cell
            if (g > grid[gi]) grid[gi] = g
        }
        val spread = IntArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = gy + dy; val xx = gx + dx
                if (yy in 0 until gh && xx in 0 until gw) m = max(m, grid[yy * gw + xx])
            }
            spread[gy * gw + gx] = m
        }
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(gh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(gw - 1, x0 + 1); val tx = fx - x0
                val top = spread[y0 * gw + x0] * (1 - tx) + spread[y0 * gw + x1] * tx
                val bot = spread[y1 * gw + x0] * (1 - tx) + spread[y1 * gw + x1] * tx
                val b = (top * (1 - ty) + bot * ty).roundToInt()
                val i = y * w + x
                bg[i] = b.toByte()
                ink[i] = (gray[i].toInt() and 0xFF) < b * 0.78f && b > 60
            }
        }
        val hist = IntArray(256)
        for (v in spread) hist[v]++
        var acc = 0
        var p = 255
        while (p > 0 && acc + hist[p] < spread.size / 10) { acc += hist[p]; p-- }
        paper = p.coerceAtLeast(150)
    }

    fun g(x: Int, y: Int) = gray[y * w + x].toInt() and 0xFF
    fun b(x: Int, y: Int) = bg[y * w + x].toInt() and 0xFF
    fun rgb(x: Int, y: Int) = px[y * w + x] and 0xFFFFFF

    // ---- ruled lines ---------------------------------------------------------------------------

    /** Long thin dark lines, horizontal and vertical ([textHeight]: typical word box height). */
    fun rules(textHeight: Float): Pair<List<Rule>, List<Rule>> {
        val dark = BooleanArray(w * h) { i ->
            val b = bg[i].toInt() and 0xFF
            (gray[i].toInt() and 0xFF) < b - max(9f, b * 0.04f)
        }
        // A line is thin across: keep dark pixels whose dark run across the line direction is short
        // (letter stems, QR modules and photos are thicker), then let the line step one pixel sideways
        // so slanted or gently curved lines still give long runs.
        val maxThick = max(8, min(w, h) / 200)
        val vRun = IntArray(w * h)
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!dark[y * w + x]) { y++; continue }
                val s = y
                while (y < h && dark[y * w + x]) y++
                for (yy in s until y) vRun[yy * w + x] = y - s
            }
        }
        val hRun = IntArray(w * h)
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!dark[y * w + x]) { x++; continue }
                val s = x
                while (x < w && dark[y * w + x]) x++
                for (xx in s until x) hRun[y * w + xx] = x - s
            }
        }
        val hThin = BooleanArray(w * h) { dark[it] && vRun[it] <= maxThick }
        val vThin = BooleanArray(w * h) { dark[it] && hRun[it] <= maxThick }
        val hMask = BooleanArray(w * h) { i -> hThin[i] || (i >= w && hThin[i - w]) || (i + w < w * h && hThin[i + w]) }
        val vMask = BooleanArray(w * h) { i ->
            val x = i % w
            vThin[i] || (x > 0 && vThin[i - 1]) || (x < w - 1 && vThin[i + 1])
        }
        val minH = max(40, min((w * 0.05f).toInt(), (textHeight * 3.5f).toInt()))
        val minV = max(24, (textHeight * 1.5f).toInt())
        return lines(hMask, true, minH) to lines(vMask, false, minV)
    }

    private class Run(val across: Int, val s: Int, val e: Int)

    private fun lines(dark: BooleanArray, horizontal: Boolean, minLen: Int): List<Rule> {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val maxThick = max(8, min(w, h) / 200)
        val active = ArrayList<Pair<Rule, DoubleArray>>() // rule, [lastAcross, total run length]
        val done = ArrayList<Pair<Rule, Double>>()
        for (o in 0 until outer) {
            val runs = ArrayList<Run>()
            var i = 0
            while (i < inner) {
                val idx = if (horizontal) o * w + i else i * w + o
                if (!dark[idx]) { i++; continue }
                val s = i
                var last = i
                var j = i + 1
                while (j < inner) {
                    val jdx = if (horizontal) o * w + j else j * w + o
                    if (dark[jdx]) last = j else if (j - last > 5) break
                    j++
                }
                if (last - s + 1 >= minLen) runs += Run(o, s, last)
                i = last + 1
            }
            for (r in runs) {
                val hit = active.firstOrNull { (rule, st) ->
                    o - st[0] <= 2 && overlap(rule.start, rule.end, r.s.toFloat(), r.e.toFloat()) >= 0.5f * min(rule.end - rule.start, (r.e - r.s).toFloat())
                }
                val len = (r.e - r.s + 1).toDouble()
                if (hit != null) {
                    hit.first.add((r.s + r.e) / 2f, o.toFloat(), len)
                    hit.first.spans += floatArrayOf(r.s.toFloat(), r.e.toFloat())
                    hit.first.start = min(hit.first.start, r.s.toFloat()); hit.first.end = max(hit.first.end, r.e.toFloat())
                    hit.second[0] = o.toDouble(); hit.second[1] += len
                } else {
                    val rule = Rule(horizontal)
                    rule.add(r.s.toFloat(), o.toFloat(), len / 2)
                    rule.add(r.e.toFloat(), o.toFloat(), len / 2)
                    rule.spans += floatArrayOf(r.s.toFloat(), r.e.toFloat())
                    rule.start = r.s.toFloat(); rule.end = r.e.toFloat()
                    active += rule to doubleArrayOf(o.toDouble(), len)
                }
            }
            val it = active.iterator()
            while (it.hasNext()) {
                val (rule, st) = it.next()
                if (o - st[0] > 2) { done += rule to st[1]; it.remove() }
            }
        }
        active.forEach { done += it.first to it.second[1] }
        // Thickness = dark rows per position along the line (a slanted line crosses many rows but is thin).
        val rules = done.map { (rule, total) ->
            rule.thickness = (total / (rule.end - rule.start + 1).coerceAtLeast(1f)).toFloat(); rule.fit(); rule
        }.filter { it.thickness <= maxThick * 2 }.toMutableList()
        // Short pieces take the slant of the long lines (the page's skew), not their own noisy fit.
        val long = rules.filter { it.end - it.start > inner * 0.25f }
        val skew = if (long.isEmpty()) 0f else long.map { it.b }.sorted()[long.size / 2]
        for (r in rules) if (r.end - r.start < inner * 0.25f) r.fixSlope(skew)
        // Join pieces of the same slightly slanted line.
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in rules.indices) for (j in rules.indices) {
                if (i == j) continue
                val a = rules[i]; val b = rules[j]
                if (b.start < a.end - 4 || b.start - a.end > 24) continue
                if (abs(a.at(a.end) - b.at(b.start)) > 3) continue
                a.merge(b)
                rules.removeAt(j)
                changed = true
                break@loop
            }
        }
        return rules.filter { it.end - it.start >= minLen }
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = max(0f, min(a1, b1) - max(a0, b0))

    // ---- tables from lines ---------------------------------------------------------------------

    /** Groups crossing horizontal and vertical rules into grid tables (merged cells found from missing lines). */
    fun gridTables(hRules: List<Rule>, vRules: List<Rule>, textHeight: Float): List<GridTable> {
        val tol = max(6f, min(w, h) / 250f)
        val all = hRules + vRules
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for ((i, hr) in hRules.withIndex()) for ((j, vr) in vRules.withIndex()) {
            val x = vr.at(hr.at(vr.a))
            val y = hr.at(x)
            if (x >= hr.start - tol && x <= hr.end + tol && y >= vr.start - tol && y <= vr.end + tol) {
                parent[find(i)] = find(hRules.size + j)
            }
        }
        val groups = all.indices.groupBy { find(it) }
        val out = ArrayList<GridTable>()
        for ((_, members) in groups) {
            val hs = members.filter { it < hRules.size }.map { hRules[it] }
            val vs = members.filter { it >= hRules.size }.map { vRules[it - hRules.size] }
            if (hs.size < 2 || vs.size < 2) continue
            val rows = cluster(hs, max(tol, textHeight * 0.45f))
            val cols = cluster(vs, max(tol, textHeight * 0.45f))
            if (rows.size < 2 || cols.size < 2) continue
            val table = GridTable(rows, cols, emptyList())
            if (table.box.width() < w * 0.15f || table.box.height() < textHeight * 1.2f) continue
            out += GridTable(rows, cols, cells(table))
        }
        return out
    }

    /**
     * A faint line can go undetected and wrongly join cells vertically. Split a tall merged cell at a
     * row line where its text sits cleanly above and below (none crossing) and part of the line was seen.
     */
    fun splitByText(g: GridTable, words: List<OcrWord>): GridTable {
        val out = ArrayList<GridCell>()
        for (cell in g.cells) {
            if (cell.r1 == cell.r0) { out += cell; continue }
            val rect = g.cellRect(cell)
            val inside = words.filter { rect.contains(it.box.centerX(), it.box.centerY()) }
            var start = cell.r0
            for (b in cell.r0 until cell.r1) {
                val y = g.rows[b + 1].at(rect.centerX())
                val top = g.rows[start].at(rect.centerX())
                val above = inside.any { it.box.top >= top - 2 && it.box.bottom <= y + 2 }
                val below = inside.any { it.box.top >= y - 2 }
                val crossing = inside.any { it.box.top < y - 2 && it.box.bottom > y + 2 }
                val coverage = g.rows[b + 1].coverage(g.cols[cell.c0].at(y), g.cols[cell.c1 + 1].at(y))
                if (above && below && !crossing && coverage >= 0.1f) {
                    out += GridCell(start, cell.c0, b, cell.c1)
                    start = b + 1
                }
            }
            out += GridCell(start, cell.c0, cell.r1, cell.c1)
        }
        return GridTable(g.rows, g.cols, out.sortedWith(compareBy({ it.r0 }, { it.c0 })))
    }

    /** Merges rules lying at (about) the same position, e.g. double lines, into one. */
    private fun cluster(rules: List<Rule>, tol: Float): List<Rule> {
        val sorted = rules.sortedBy { it.at((it.start + it.end) / 2) }
        val out = ArrayList<Rule>()
        for (r in sorted) {
            val last = out.lastOrNull()
            val mid = (r.start + r.end) / 2
            if (last != null && abs(last.at(mid) - r.at(mid)) < tol) last.merge(r) else {
                val copy = Rule(r.horizontal); copy.merge(r); out += copy
            }
        }
        return out
    }

    private fun cells(t: GridTable): List<GridCell> {
        val rows = t.rows
        val cols = t.cols
        val nr = rows.size - 1
        val nc = cols.size - 1
        val parent = IntArray(nr * nc) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        for (r in 0 until nr) for (c in 0 until nc) {
            if (c + 1 < nc) {
                // Vertical line between (r, c) and (r, c+1)?
                val x = cols[c + 1].at((t.rowY(r) + t.rowY(r + 1)) / 2)
                val top = rows[r].at(x); val bot = rows[r + 1].at(x)
                val pad = (bot - top) * 0.12f
                if (cols[c + 1].coverage(top + pad, bot - pad) < 0.4f) union(r * nc + c, r * nc + c + 1)
            }
            if (r + 1 < nr) {
                val y = rows[r + 1].at((t.colX(c) + t.colX(c + 1)) / 2)
                val left = cols[c].at(y); val right = cols[c + 1].at(y)
                val pad = (right - left) * 0.08f
                if (rows[r + 1].coverage(left + pad, right - pad) < 0.4f) union(r * nc + c, (r + 1) * nc + c)
            }
        }
        val sets = (0 until nr * nc).groupBy { find(it) }
        val out = ArrayList<GridCell>()
        for ((_, members) in sets) {
            val r0 = members.minOf { it / nc }; val r1 = members.maxOf { it / nc }
            val c0 = members.minOf { it % nc }; val c1 = members.maxOf { it % nc }
            if (members.size == (r1 - r0 + 1) * (c1 - c0 + 1)) {
                out += GridCell(r0, c0, r1, c1)
            } else {
                // Not a rectangle (a broken line joined two areas): cut it into rectangles, each as wide
                // and then as tall as the area allows.
                val inSet = members.toHashSet()
                val used = HashSet<Int>()
                for (m in members.sorted()) {
                    if (m in used) continue
                    val r = m / nc
                    val c = m % nc
                    var cEnd = c
                    while (cEnd + 1 < nc && (r * nc + cEnd + 1) in inSet && (r * nc + cEnd + 1) !in used) cEnd++
                    var rEnd = r
                    while (rEnd + 1 < nr && (c..cEnd).all { cc -> ((rEnd + 1) * nc + cc) in inSet && ((rEnd + 1) * nc + cc) !in used }) rEnd++
                    for (rr in r..rEnd) for (cc in c..cEnd) used += rr * nc + cc
                    out += GridCell(r, c, rEnd, cEnd)
                }
            }
        }
        return out.sortedWith(compareBy({ it.r0 }, { it.c0 }))
    }

    // ---- shaded row bands ------------------------------------------------------------------------

    /**
     * Borderless tables drawn as alternating grey / white rows. Returns each table with its row edges
     * (top of each row, then the bottom of the last) and the shade of each row (null = white).
     */
    fun bandTables(exclude: List<RectF>, textHeight: Float): List<BandTable> {
        val minH = max(6, (textHeight * 0.9f).toInt())
        val shaded = BooleanArray(h)
        val left = IntArray(h) { -1 }
        val right = IntArray(h) { -1 }
        // Brightness of each row's paper (median of the non-ink pixels across the middle 80 %).
        val rowLevel = IntArray(h)
        val hist = IntArray(256)
        for (y in 0 until h) {
            java.util.Arrays.fill(hist, 0)
            var n = 0
            for (x in (w / 10) until (w * 9 / 10) step 2) {
                val g = g(x, y)
                if (g < 120) continue
                hist[g]++; n++
            }
            var acc = 0; var m = 0
            while (m < 255 && acc + hist[m] < n / 2) { acc += hist[m]; m++ }
            rowLevel[y] = if (n > w / 8) m else 0
        }
        // Local white: the brightest row level within a few lines (lighting changes slowly).
        val reach = (textHeight * 5).toInt().coerceAtLeast(10)
        val white = IntArray(h)
        for (y in 0 until h) {
            var m = 0
            for (yy in max(0, y - reach)..min(h - 1, y + reach) step 2) m = max(m, rowLevel[yy])
            white[y] = m
        }
        for (y in 0 until h) {
            if (exclude.any { y >= it.top && y <= it.bottom }) continue
            if (rowLevel[y] == 0 || rowLevel[y] > white[y] - 10) continue
            // The longest stretch of shaded (not white, not ink) pixels along the row.
            var bestS = -1; var bestE = -1; var s0 = -1; var lastShaded = -1
            for (x in 0 until w step 2) {
                val g = g(x, y)
                // Shading is flat; photos are not.
                val isShade = g in 120 until white[y] - 7 && x >= 4 && abs(g - g(x - 4, y)) <= 10
                val isInk = g < 120
                if (isShade) {
                    if (s0 < 0 || x - lastShaded > textHeight * 2.5f) s0 = x
                    lastShaded = x
                    if (lastShaded - s0 > bestE - bestS) { bestS = s0; bestE = lastShaded }
                } else if (!isInk && s0 >= 0 && x - lastShaded > textHeight * 2.5f) {
                    s0 = -1
                }
            }
            if (bestE - bestS > w * 0.45f) {
                shaded[y] = true; left[y] = bestS; right[y] = bestE
            }
        }
        // Runs of shaded rows = bands.
        val bands = ArrayList<IntArray>() // top, bottom, left, right
        var y = 0
        while (y < h) {
            if (!shaded[y]) { y++; continue }
            val s = y
            var l = Int.MAX_VALUE; var r = 0
            while (y < h && shaded[y]) { l = min(l, left[y]); r = max(r, right[y]); y++ }
            if (y - s >= minH && y - s <= textHeight * 8) {
                // Edges: the median over the band's rows (a few rows touch shadows at the page edge).
                val ls = (s until y).map { left[it] }.sorted()
                val rs = (s until y).map { right[it] }.sorted()
                bands += intArrayOf(s, y, ls[ls.size / 2], rs[rs.size / 2])
            }
        }
        // Bands one row apart, aligned left and right, form a table.
        val out = ArrayList<BandTable>()
        var i = 0
        while (i < bands.size) {
            var j = i
            while (j + 1 < bands.size) {
                val a = bands[j]; val b = bands[j + 1]
                val gap = b[0] - a[1]
                // Same table when the bands overlap almost fully (a shadow at the page edge may stretch one).
                val ov = min(a[3], b[3]) - max(a[2], b[2])
                val aligned = ov >= 0.85f * min(a[3] - a[2], b[3] - b[2])
                if (gap <= (a[1] - a[0]) * 2.5f + textHeight && aligned) j++ else break
            }
            if (j - i + 1 >= 3) {
                val edges = ArrayList<Float>()
                val shades = ArrayList<Int?>()
                for (k in i..j) {
                    val band = bands[k]
                    if (k > i) {
                        val prev = bands[k - 1]
                        if (band[0] - prev[1] > textHeight * 0.6f) { edges += prev[1].toFloat(); shades += null }
                    }
                    edges += band[0].toFloat()
                    shades += bandColor(band)
                }
                edges += bands[j][1].toFloat()
                val l = (i..j).map { bands[it][2] }.sorted()[(j - i + 1) / 2].toFloat()
                val r = (i..j).map { bands[it][3] }.sorted()[(j - i + 1) / 2].toFloat()
                out += BandTable(RectF(l, bands[i][0].toFloat(), r, bands[j][1].toFloat()), edges.toFloatArray(), shades.toTypedArray())
            }
            i = j + 1
        }
        return out
    }

    private fun bandColor(band: IntArray): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val y0 = band[0] + (band[1] - band[0]) / 4
        val y1 = band[1] - (band[1] - band[0]) / 4
        for (y in y0..y1) for (x in band[2]..band[3] step 3) {
            if (g(x, y) < 140) continue
            val c = rgb(x, y)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        if (n == 0) return 0xD9D9D9
        return lighten(((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt())
    }

    /** Photos of paper are dimmer than the real thing; scale so the paper would be white. */
    fun lighten(c: Int): Int {
        val f = 255f / paper.coerceAtLeast(1)
        val r = (((c shr 16) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * f).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    // ---- pictures --------------------------------------------------------------------------------

    /**
     * Areas that are neither text nor table lines nor plain paper: photos, logos, QR codes, drawings,
     * and text the OCR could not read. [known] (e.g. barcodes) are always pictures.
     */
    fun pictures(words: List<OcrWord>, rules: List<Rule>, exclude: List<RectF>, known: List<RectF>, textHeight: Float): List<RectF> {
        // Loose: dark, textured or coloured. Strict (for areas full of text, e.g. a stained sign with
        // photos on it): only clearly dark or clearly coloured.
        val loose = BooleanArray(w * h)
        val strict = BooleanArray(w * h)
        for (i in loose.indices) {
            val gv = gray[i].toInt() and 0xFF
            val bv = bg[i].toInt() and 0xFF
            val c = px[i]
            val r = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val b = c and 0xFF
            val sat = max(r, max(gg, b)) - min(r, min(gg, b))
            val x = i % w
            val y = i / w
            val grad = if (x in 1 until w - 1 && y in 1 until h - 1) {
                abs((gray[i + 1].toInt() and 0xFF) - (gray[i - 1].toInt() and 0xFF)) +
                    abs((gray[i + w].toInt() and 0xFF) - (gray[i - w].toInt() and 0xFF))
            } else 0
            loose[i] = gv < bv - 60 || (grad > 24 && gv < bv - 18) || (sat > 60 && gv < bv - 8)
            strict[i] = gv < bv - 70 || (sat > 70 && gv < bv - 12)
        }
        fun clear(mask: BooleanArray, rect: RectF, pad: Float) {
            val x0 = (rect.left - pad).toInt().coerceIn(0, w); val x1 = (rect.right + pad).toInt().coerceIn(0, w)
            val y0 = (rect.top - pad).toInt().coerceIn(0, h); val y1 = (rect.bottom + pad).toInt().coerceIn(0, h)
            for (y in y0 until y1) for (x in x0 until x1) mask[y * w + x] = false
        }
        for (mask in arrayOf(loose, strict)) {
            for (wd in words) if (wd.confidence >= 0.5f) clear(mask, wd.box, wd.box.height() * 0.15f)
            for (r in rules) {
                val half = r.thickness / 2 + 2
                var t = r.start
                while (t <= r.end) {
                    val across = r.at(t)
                    if (r.horizontal) clear(mask, RectF(t, across - half, t + 1, across + half), 0f)
                    else clear(mask, RectF(across - half, t, across + half, t + 1), 0f)
                    t += 1f
                }
            }
            for (e in exclude) clear(mask, e, 0f)
        }

        fun wordsIn(rect: RectF) = words.count { it.confidence >= 0.6f && rect.contains(it.box.centerX(), it.box.centerY()) }
        fun textShare(rect: RectF) = words.sumOf { wd ->
            val i = RectF(wd.box)
            if (!i.intersect(rect)) 0.0 else (i.width() * i.height()).toDouble()
        } / (rect.width() * rect.height()).coerceAtLeast(1f)

        /** A component that is mostly one recognised word (a large capital, a logo letter). */
        fun isGlyph(rect: RectF) = words.any { wd ->
            val i = RectF(wd.box)
            if (i.intersect(rect) && i.width() * i.height() >= 0.5f * rect.width() * rect.height()) return@any true
            // A letter-sized blob right beside a word the OCR was unsure of is that word's glyph (OCR boxes
            // for big, stylised letters are sometimes off by a letter's width).
            val overlapY = min(rect.bottom, wd.box.bottom) - max(rect.top, wd.box.top)
            val gapX = max(wd.box.left - rect.right, rect.left - wd.box.right)
            wd.confidence < 0.5f && wd.text.length <= 3 && overlapY >= 0.6f * rect.height() &&
                rect.height() <= wd.box.height() * 1.6f && gapX <= wd.box.height()
        }
        /**
         * Plain shading (a shadow over the paper, a tinted panel or a coloured label behind a heading):
         * the area around its words is flat. Photos, drawings and codes are not.
         */
        fun isFlat(rect: RectF): Boolean {
            val x0 = rect.left.toInt().coerceIn(0, w - 1); val x1 = rect.right.toInt().coerceIn(x0 + 1, w)
            val y0 = rect.top.toInt().coerceIn(0, h - 1); val y1 = rect.bottom.toInt().coerceIn(y0 + 1, h)
            val inside = words.filter { RectF.intersects(it.box, rect) }.map { RectF(it.box).apply { inset(-it.box.height() * 0.2f, -it.box.height() * 0.2f) } }
            val step = max(1, min(x1 - x0, y1 - y0) / 60)
            var n = 0; var sum = 0.0; var sq = 0.0
            for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
                if (inside.any { it.contains(x.toFloat(), y.toFloat()) }) continue
                val v = (gray[y * w + x].toInt() and 0xFF) - (bg[y * w + x].toInt() and 0xFF)
                n++; sum += v; sq += v.toDouble() * v
            }
            if (n < 30) return false
            val mean = sum / n
            return sqrt(max(0.0, sq / n - mean * mean)) < 12.0
        }
        val out = ArrayList<RectF>()
        for (rect in components(loose, null, 2, textHeight)) {
            if (textShare(rect) > 0.45 || isGlyph(rect) || isFlat(rect)) continue
            if (wordsIn(rect) <= 4) { out += rect; continue }
            // Many words inside: the loose mask joined text and background. Look again, strictly.
            for (sub in components(strict, rect, 1, textHeight)) {
                if (wordsIn(sub) <= 3 && textShare(sub) < 0.3) out += sub
            }
        }
        out += known
        return mergeRects(out, w * 0.01f)
    }

    /**
     * Connected areas of [mask] (inside [within], or the whole page), in blocks of ~0.4 % of the short
     * side after closing gaps of [close] blocks; only areas big and solid enough to be a picture.
     */
    private fun components(mask: BooleanArray, within: RectF?, close: Int, textHeight: Float): List<RectF> {
        val bs = max(4, min(w, h) / 250)
        val ox = ((within?.left ?: 0f) / bs).toInt()
        val oy = ((within?.top ?: 0f) / bs).toInt()
        val bw = ((within?.right ?: w.toFloat()) / bs).toInt().coerceAtMost((w + bs - 1) / bs) - ox
        val bh = ((within?.bottom ?: h.toFloat()) / bs).toInt().coerceAtMost((h + bs - 1) / bs) - oy
        if (bw <= 0 || bh <= 0) return emptyList()
        val counts = IntArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var n = 0
            for (y in (oy + by) * bs until min(h, (oy + by + 1) * bs)) for (x in (ox + bx) * bs until min(w, (ox + bx + 1) * bs)) {
                if (mask[y * w + x]) n++
            }
            counts[by * bw + bx] = n
        }
        var blocks = BooleanArray(bw * bh) { counts[it] >= bs * bs / 5 }
        // Close small gaps (a photo with white patches, a QR code's quiet modules).
        if (close > 0) blocks = erode(dilate(blocks, bw, bh, close), bw, bh, close)
        val label = BooleanArray(bw * bh)
        val queue = IntArray(bw * bh)
        val out = ArrayList<RectF>()
        for (start in blocks.indices) {
            if (!blocks[start] || label[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = true
            var minX = bw; var minY = bh; var maxX = 0; var maxY = 0; var area = 0
            while (head < tail) {
                val i = queue[head++]
                val bx = i % bw; val by = i / bw
                area++
                minX = min(minX, bx); maxX = max(maxX, bx); minY = min(minY, by); maxY = max(maxY, by)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = bx + dx; val ny = by + dy
                    if (nx !in 0 until bw || ny !in 0 until bh) continue
                    val j = ny * bw + nx
                    if (blocks[j] && !label[j]) { label[j] = true; queue[tail++] = j }
                }
            }
            val rect = RectF(
                ((ox + minX) * bs).toFloat(), ((oy + minY) * bs).toFloat(),
                ((ox + maxX + 1) * bs).toFloat().coerceAtMost(w.toFloat()), ((oy + maxY + 1) * bs).toFloat().coerceAtMost(h.toFloat()),
            )
            val boxArea = (maxX - minX + 1) * (maxY - minY + 1)
            val bigEnough = rect.width() >= w * 0.035f && rect.height() >= max(textHeight * 1.6f, w * 0.025f)
            if (bigEnough && area >= boxArea * 0.18f) out += rect
        }
        return out
    }

    private fun dilate(m: BooleanArray, bw: Int, bh: Int, r: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until bh) for (x in 0 until bw) {
            if (!m[y * bw + x]) continue
            for (dy in -r..r) for (dx in -r..r) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until bw && ny in 0 until bh) out[ny * bw + nx] = true
            }
        }
        return out
    }

    private fun erode(m: BooleanArray, bw: Int, bh: Int, r: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until bh) for (x in 0 until bw) {
            var all = true
            loop@ for (dy in -r..r) for (dx in -r..r) {
                val nx = (x + dx).coerceIn(0, bw - 1); val ny = (y + dy).coerceIn(0, bh - 1)
                if (!m[ny * bw + nx]) { all = false; break@loop }
            }
            out[y * bw + x] = all
        }
        return out
    }

    fun mergeRects(rects: List<RectF>, gap: Float): List<RectF> {
        val list = rects.map { RectF(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                if (a.left - gap <= b.right && b.left - gap <= a.right && a.top - gap <= b.bottom && b.top - gap <= a.bottom) {
                    a.union(b); list.removeAt(j); changed = true; break@loop
                }
            }
        }
        return list
    }

    // ---- word appearance -----------------------------------------------------------------------

    /** Stroke thickness and colour of a word. */
    fun styleWord(word: OcrWord) {
        val box = word.box
        val x0 = box.left.toInt().coerceIn(0, w - 1); val x1 = box.right.toInt().coerceIn(x0 + 1, w)
        val y0 = box.top.toInt().coerceIn(0, h - 1); val y1 = box.bottom.toInt().coerceIn(y0 + 1, h)
        var minG = 255
        for (y in y0 until y1) for (x in x0 until x1) minG = min(minG, gray[y * w + x].toInt() and 0xFF)
        // Stroke thickness: median length of the dark runs along each row (= width of vertical stems),
        // dark meaning below halfway between the word's darkest ink and the paper.
        val runs = ArrayList<Int>()
        for (y in y0 until y1) {
            var run = 0
            for (x in x0 until x1) {
                val i = y * w + x
                val dark = (gray[i].toInt() and 0xFF) < (minG + (bg[i].toInt() and 0xFF)) / 2
                if (dark) run++ else if (run > 0) { runs += run; run = 0 }
            }
            if (run > 0) runs += run
        }
        if (runs.isNotEmpty()) {
            runs.sort()
            word.stroke = runs[runs.size / 2].toFloat()
        }
        // Colour: the ink pixels (darker than halfway to the paper), white-balanced against the paper
        // right around the word (photos of pages often have a warm or cool cast).
        val ink = ArrayList<Int>()
        var pr = 0.0; var pg = 0.0; var pb = 0.0; var pn = 0
        for (y in y0 until y1 step max(1, (y1 - y0) / 24)) for (x in x0 until x1 step max(1, (x1 - x0) / 48)) {
            val i = y * w + x
            val gv = gray[i].toInt() and 0xFF
            val bv = bg[i].toInt() and 0xFF
            if (gv <= minG + (bv - minG) / 2) ink += px[i]
            else if (gv >= minG + (bv - minG) * 0.85f) {
                val c = px[i]
                pr += (c shr 16) and 0xFF; pg += (c shr 8) and 0xFF; pb += c and 0xFF; pn++
            }
        }
        val samples = if (pn >= 8) {
            val fr = 255.0 / (pr / pn).coerceAtLeast(60.0)
            val fg = 255.0 / (pg / pn).coerceAtLeast(60.0)
            val fb = 255.0 / (pb / pn).coerceAtLeast(60.0)
            ink.map { c ->
                val r = (((c shr 16) and 0xFF) * fr).roundToInt().coerceAtMost(255)
                val g = (((c shr 8) and 0xFF) * fg).roundToInt().coerceAtMost(255)
                val b = ((c and 0xFF) * fb).roundToInt().coerceAtMost(255)
                (r shl 16) or (g shl 8) or b
            }
        } else {
            ink.map { onWhite(it, paper) }
        }
        word.color = inkColor(samples)
        // Pencil (handwriting) is light grey even against its own paper.
        word.inkLuma = samples.map { ((((it shr 16) and 0xFF) * 299 + ((it shr 8) and 0xFF) * 587 + (it and 0xFF) * 114) / 1000) }
            .sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    }

    /**
     * Top and bottom of the ink inside [box] (OCR boxes are padded line boxes): rows where enough pixels
     * are darker than halfway between the darkest ink and the paper.
     */
    fun inkSpan(box: RectF): Pair<Float, Float>? {
        val x0 = box.left.toInt().coerceIn(0, w - 1); val x1 = box.right.toInt().coerceIn(x0 + 1, w)
        val y0 = box.top.toInt().coerceIn(0, h - 1); val y1 = box.bottom.toInt().coerceIn(y0 + 1, h)
        var minG = 255
        for (y in y0 until y1) for (x in x0 until x1 step 2) minG = min(minG, gray[y * w + x].toInt() and 0xFF)
        val need = max(1, (x1 - x0) / 200)
        val inkRow = BooleanArray(y1 - y0)
        for (y in y0 until y1) {
            var n = 0
            for (x in x0 until x1) {
                val i = y * w + x
                if ((gray[i].toInt() and 0xFF) < (minG + (bg[i].toInt() and 0xFF)) / 2) n++
            }
            inkRow[y - y0] = n >= need
        }
        // The line's own ink is the longest band of ink rows (small gaps for i-dots and accents allowed);
        // bits of the lines above and below sit apart from it at the box edges.
        val maxGap = max(2, ((y1 - y0) * 0.12f).toInt())
        var bestS = -1; var bestE = -1; var s = -1; var e = -1
        for (r in inkRow.indices) {
            if (!inkRow[r]) continue
            if (s < 0 || r - e > maxGap) { s = r }
            e = r
            if (e - s > bestE - bestS) { bestS = s; bestE = e }
        }
        if (bestS < 0 || bestE - bestS < 3) return null
        return (y0 + bestS).toFloat() to (y0 + bestE + 1).toFloat()
    }

    /**
     * Font size in pixels from the dense core of a text line: the rows where most strokes are is the
     * x-height for lowercase text (≈0.53 em), the cap height for capitals and digits (≈0.72 em), and
     * nearly the whole character for CJK (≈0.85 em). Ascenders, descenders and touching neighbour
     * lines do not change it.
     */
    fun emFromCore(box: RectF, text: String): Float? {
        val x0 = box.left.toInt().coerceIn(0, w - 1); val x1 = box.right.toInt().coerceIn(x0 + 1, w)
        val y0 = box.top.toInt().coerceIn(0, h - 1); val y1 = box.bottom.toInt().coerceIn(y0 + 1, h)
        var minG = 255
        for (y in y0 until y1) for (x in x0 until x1 step 2) minG = min(minG, gray[y * w + x].toInt() and 0xFF)
        val counts = IntArray(y1 - y0)
        for (y in y0 until y1) {
            var n = 0
            for (x in x0 until x1) {
                val i = y * w + x
                if ((gray[i].toInt() and 0xFF) < (minG + (bg[i].toInt() and 0xFF)) / 2) n++
            }
            counts[y - y0] = n
        }
        // Reference density: the 70th percentile of the inked rows (one dense crossbar row must not set it).
        val inked = counts.filter { it > 0 }.sorted()
        if (inked.size < 4) return null
        val ref = inked[(inked.size * 0.7f).toInt().coerceAtMost(inked.size - 1)]
        if (ref < 3) return null
        var lower = 0; var upper = 0; var cjk = 0
        for (c in text) {
            when {
                c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0x3040..0x30FF -> cjk++
                c.isLowerCase() -> lower++
                c.isUpperCase() || c.isDigit() -> upper++
            }
        }
        // Lowercase text: every letter has ink in the x-height band, only tall letters above it, so the
        // band is where the density stays near its high; capitals are evenly dense top to bottom.
        val lowercase = lower >= upper && cjk <= lower + upper
        val need = if (lowercase) inked[(inked.size * 0.85f).toInt().coerceAtMost(inked.size - 1)] * 0.6f else ref * 0.4f
        // The longest band of dense rows (a box covering two lines must not count both).
        var best = 0; var run = 0
        for (c in counts) {
            if (c >= need) { run++; best = max(best, run) } else run = 0
        }
        val core = best.toFloat()
        val frac = when {
            cjk > lower + upper -> 0.85f
            lower >= upper -> 0.53f
            else -> 0.72f
        }
        return core / frac
    }

    /** A pixel as it would look on white paper (divided by the local paper brightness). */
    private fun onWhite(c: Int, paperLevel: Int): Int {
        val f = 255f / paperLevel.coerceAtLeast(60)
        val r = (((c shr 16) and 0xFF) * f).roundToInt().coerceAtMost(255)
        val g = (((c shr 8) and 0xFF) * f).roundToInt().coerceAtMost(255)
        val b = ((c and 0xFF) * f).roundToInt().coerceAtMost(255)
        return (r shl 16) or (g shl 8) or b
    }

    /** Dark, low-saturation ink is "automatic" (black); clear colours are kept. */
    fun inkColor(samples: List<Int>): Int? {
        if (samples.isEmpty()) return null
        // Mean of the ink (thin strokes are partly paper-coloured, so medians per channel lose the hue).
        var r = 0.0; var g = 0.0; var b = 0.0
        for (c in samples) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
        r /= samples.size; g /= samples.size; b /= samples.size
        val sat = max(r, max(g, b)) - min(r, min(g, b))
        val luma = r * 0.299 + g * 0.587 + b * 0.114
        if (sat < 18) {
            // Neutral ink: black text (anti-aliasing makes thin strokes look grey), or a real grey caption.
            if (luma < 165) return null
            val v = (luma * 0.8).roundToInt().coerceIn(0, 255)
            return (v shl 16) or (v shl 8) or v
        }
        // Dull warm or very dark tints are shadows and lamp light on black print, not coloured ink.
        if ((sat < 40 && r >= g && g >= b) || (luma < 90 && sat < 35)) return null
        // Coloured ink photographs paler than printed: strengthen the hue and deepen it a little.
        val mean = (r + g + b) / 3
        fun ch(v: Double) = (mean + (v - mean) * 1.6).roundToInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    companion object {
        private const val ASCENDERS = "bdfhijklt"
        private const val DESCENDERS = "gjpqy"

        /**
         * Em size from a word box: the box covers cap/ascender height (~0.72 em) when the word has capitals,
         * digits or tall letters, x-height (~0.52 em) otherwise, plus ~0.21 em with descenders. CJK
         * characters fill ~0.88 em.
         */
        fun emFromBox(text: String, height: Float): Float {
            var tall = false; var low = false; var cjk = false; var x = false
            for (c in text) {
                when {
                    c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0x3040..0x30FF || c.code in 0xFF00..0xFFEF -> cjk = true
                    c.isUpperCase() || c.isDigit() || c in ASCENDERS || c in "()[]{}|/\\'\"!?#$%&@" -> tall = true
                    c.isLowerCase() -> x = true
                }
                if (c in DESCENDERS || c in "()[]{}|;,") low = true
            }
            val frac = when {
                cjk -> 0.88f
                tall && low -> 0.93f
                tall -> 0.72f
                x && low -> 0.73f
                x -> 0.53f
                else -> 0.72f
            }
            return height / frac
        }

        fun median(values: List<Float>): Float = if (values.isEmpty()) 0f else values.sorted()[values.size / 2]

        fun dist(a: Float, b: Float) = sqrt(a * a + b * b)
    }
}

internal fun Rect.toRectF() = RectF(this)
