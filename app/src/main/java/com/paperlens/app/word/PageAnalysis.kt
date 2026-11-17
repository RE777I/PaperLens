package com.paperlens.app.word

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pixel-level layout analysis of one page: paper brightness, ink, ruled lines, tables, shaded bands,
 * pictures, and the size / weight / colour of each word.
 */
internal class PageAnalysis(val bitmap: Bitmap) {
    val w = bitmap.width
    val h = bitmap.height
    internal val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
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

    /**
     * Long thin dark lines, horizontal and vertical ([textHeight]: typical word box height). Lines up to
     * [maxThick] pixels thick count (To Excel allows thicker ones: scanned forms often have heavy rules).
     */
    fun rules(textHeight: Float, maxThick: Int = max(8, min(w, h) / 200)): Pair<List<Rule>, List<Rule>> {
        val dark = BooleanArray(w * h) { i ->
            val b = bg[i].toInt() and 0xFF
            (gray[i].toInt() and 0xFF) < b - max(9f, b * 0.04f)
        }
        // A line is thin across: keep dark pixels whose dark run across the line direction is short
        // (letter stems, QR modules and photos are thicker), then let the line step one pixel sideways
        // so slanted or gently curved lines still give long runs.
        val vRun = runLengths(dark, vertical = true)
        val hRun = runLengths(dark, vertical = false)
        val hThin = BooleanArray(w * h) { dark[it] && vRun[it] <= maxThick }
        val vThin = BooleanArray(w * h) { dark[it] && hRun[it] <= maxThick }
        val hMask = BooleanArray(w * h) { i -> hThin[i] || (i >= w && hThin[i - w]) || (i + w < w * h && hThin[i + w]) }
        val vMask = BooleanArray(w * h) { i ->
            val x = i % w
            vThin[i] || (x > 0 && vThin[i - 1]) || (x < w - 1 && vThin[i + 1])
        }
        val minH = max(40, min((w * 0.05f).toInt(), (textHeight * 3.5f).toInt()))
        val minV = max(24, (textHeight * 1.5f).toInt())
        return lines(hMask, true, minH, maxThick) to lines(vMask, false, minV, maxThick)
    }

    /** Index of the pixel at position [pos] along scan line [line] (a column when [vertical], else a row). */
    private fun pixel(line: Int, pos: Int, vertical: Boolean) = if (vertical) pos * w + line else line * w + pos

    /** For every dark pixel, the length of the unbroken dark run through it along its column or its row. */
    private fun runLengths(dark: BooleanArray, vertical: Boolean): IntArray {
        val out = IntArray(w * h)
        val length = if (vertical) h else w
        for (line in 0 until (if (vertical) w else h)) {
            var pos = 0
            while (pos < length) {
                val end = darkRunEnd(dark, line, pos, length, vertical)
                writeRunLength(out, line, pos, end, vertical)
                pos = max(end, pos + 1)
            }
        }
        return out
    }

    /** Where the dark run starting at [pos] ends (exclusive); [pos] itself when that pixel is not dark. */
    private fun darkRunEnd(dark: BooleanArray, line: Int, pos: Int, length: Int, vertical: Boolean): Int {
        var end = pos
        while (end < length && dark[pixel(line, end, vertical)]) end++
        return end
    }

    private fun writeRunLength(out: IntArray, line: Int, start: Int, end: Int, vertical: Boolean) {
        for (pos in start until end) out[pixel(line, pos, vertical)] = end - start
    }

    /** A stretch of line pixels on one scan line, from [s] to [e] along the line. */
    private class Run(val s: Int, val e: Int) {
        val length: Double get() = (e - s + 1).toDouble()
    }

    /** A rule being followed from scan line to scan line: where it was last seen and its dark pixels so far. */
    private class Track(val rule: Rule, var lastLine: Int, var darkPixels: Double)

    private fun lines(dark: BooleanArray, horizontal: Boolean, minLen: Int, maxThick: Int): List<Rule> {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val active = ArrayList<Track>()
        val done = ArrayList<Track>()
        for (line in 0 until outer) {
            for (run in longRuns(dark, line, inner, horizontal, minLen)) {
                val track = active.firstOrNull { continues(it, run, line) }
                if (track != null) extend(track, run, line) else active += startTrack(run, line, horizontal)
            }
            // A rule not seen for more than two scan lines has ended.
            val ended = active.filter { line - it.lastLine > 2 }
            done += ended
            active -= ended.toSet()
        }
        done += active
        // Thickness = dark rows per position along the line (a slanted line crosses many rows but is thin).
        val rules = done.map { track ->
            track.rule.apply {
                thickness = (track.darkPixels / (end - start + 1).coerceAtLeast(1f)).toFloat()
                fit()
            }
        }.filter { it.thickness <= maxThick * 2 }.toMutableList()
        // Short pieces take the slant of the long lines (the page's skew), not their own noisy fit.
        val long = rules.filter { it.end - it.start > inner * 0.25f }
        val skew = if (long.isEmpty()) 0f else long.map { it.b }.sorted()[long.size / 2]
        for (r in rules) if (r.end - r.start < inner * 0.25f) r.fixSlope(skew)
        // Join pieces of the same slightly slanted line.
        while (joinFirstContinuation(rules)) {
            // A joined rule may now reach a further piece, so look again from the start.
        }
        return rules.filter { it.end - it.start >= minLen }
    }

    /** Runs of line pixels on scan line [line] at least [minLen] long; gaps of up to 5 pixels do not break a run. */
    private fun longRuns(dark: BooleanArray, line: Int, length: Int, horizontal: Boolean, minLen: Int): List<Run> {
        val runs = ArrayList<Run>()
        var pos = 0
        while (pos < length) {
            if (!dark[pixel(line, pos, !horizontal)]) {
                pos++
                continue
            }
            var last = pos
            var next = pos + 1
            while (next < length) {
                if (dark[pixel(line, next, !horizontal)]) last = next else if (next - last > 5) break
                next++
            }
            if (last - pos + 1 >= minLen) runs += Run(pos, last)
            pos = last + 1
        }
        return runs
    }

    /** Whether [run] on scan line [line] belongs to the rule of [track]: seen just before, and mostly overlapping. */
    private fun continues(track: Track, run: Run, line: Int): Boolean {
        val rule = track.rule
        return line - track.lastLine <= 2 &&
            overlap(rule.start, rule.end, run.s.toFloat(), run.e.toFloat()) >= 0.5f * min(rule.end - rule.start, (run.e - run.s).toFloat())
    }

    private fun extend(track: Track, run: Run, line: Int) {
        val rule = track.rule
        rule.add((run.s + run.e) / 2f, line.toFloat(), run.length)
        rule.spans += floatArrayOf(run.s.toFloat(), run.e.toFloat())
        rule.start = min(rule.start, run.s.toFloat())
        rule.end = max(rule.end, run.e.toFloat())
        track.lastLine = line
        track.darkPixels += run.length
    }

    private fun startTrack(run: Run, line: Int, horizontal: Boolean): Track {
        val rule = Rule(horizontal)
        rule.add(run.s.toFloat(), line.toFloat(), run.length / 2)
        rule.add(run.e.toFloat(), line.toFloat(), run.length / 2)
        rule.spans += floatArrayOf(run.s.toFloat(), run.e.toFloat())
        rule.start = run.s.toFloat()
        rule.end = run.e.toFloat()
        return Track(rule, line, run.length)
    }

    /** Joins the first rule that carries on where another ends into that one; false when there is none. */
    private fun joinFirstContinuation(rules: MutableList<Rule>): Boolean {
        for (i in rules.indices) for (j in rules.indices) {
            if (i == j) continue
            val a = rules[i]
            val b = rules[j]
            if (b.start < a.end - 4 || b.start - a.end > 24) continue
            if (abs(a.at(a.end) - b.at(b.start)) > 3) continue
            a.merge(b)
            rules.removeAt(j)
            return true
        }
        return false
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = max(0f, min(a1, b1) - max(a0, b0))

    // ---- tables from lines ---------------------------------------------------------------------

    /** Groups crossing horizontal and vertical rules into grid tables (merged cells found from missing lines). */
    fun gridTables(hRules: List<Rule>, vRules: List<Rule>, textHeight: Float): List<GridTable> {
        val tol = max(6f, min(w, h) / 250f)
        val all = hRules + vRules
        // Rules that cross belong to the same table.
        val crossing = DisjointSets(all.size)
        for ((i, hr) in hRules.withIndex()) for ((j, vr) in vRules.withIndex()) {
            val x = vr.at(hr.at(vr.a))
            val y = hr.at(x)
            if (x >= hr.start - tol && x <= hr.end + tol && y >= vr.start - tol && y <= vr.end + tol) {
                crossing.union(i, hRules.size + j)
            }
        }
        val groups = all.indices.groupBy { crossing.find(it) }
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

    /** The table's cells: grid squares with no line between them are one (merged) cell. */
    private fun cells(t: GridTable): List<GridCell> {
        val nr = t.rows.size - 1
        val nc = t.cols.size - 1
        val joined = DisjointSets(nr * nc)
        for (r in 0 until nr) for (c in 0 until nc) {
            if (c + 1 < nc && !hasLineRightOf(t, r, c)) joined.union(r * nc + c, r * nc + c + 1)
            if (r + 1 < nr && !hasLineBelow(t, r, c)) joined.union(r * nc + c, (r + 1) * nc + c)
        }
        val out = ArrayList<GridCell>()
        for (members in (0 until nr * nc).groupBy { joined.find(it) }.values) {
            val r0 = members.minOf { it / nc }
            val r1 = members.maxOf { it / nc }
            val c0 = members.minOf { it % nc }
            val c1 = members.maxOf { it % nc }
            val rectangle = members.size == (r1 - r0 + 1) * (c1 - c0 + 1)
            if (rectangle) out += GridCell(r0, c0, r1, c1) else out += cutIntoRectangles(members, nr, nc)
        }
        return out.sortedWith(compareBy({ it.r0 }, { it.c0 }))
    }

    /** Whether the vertical line between grid squares (r, c) and (r, c + 1) is drawn. */
    private fun hasLineRightOf(t: GridTable, r: Int, c: Int): Boolean {
        val line = t.cols[c + 1]
        val x = line.at((t.rowY(r) + t.rowY(r + 1)) / 2)
        val top = t.rows[r].at(x)
        val bottom = t.rows[r + 1].at(x)
        val pad = (bottom - top) * 0.12f
        return line.coverage(top + pad, bottom - pad) >= 0.4f
    }

    /** Whether the horizontal line between grid squares (r, c) and (r + 1, c) is drawn. */
    private fun hasLineBelow(t: GridTable, r: Int, c: Int): Boolean {
        val line = t.rows[r + 1]
        val y = line.at((t.colX(c) + t.colX(c + 1)) / 2)
        val left = t.cols[c].at(y)
        val right = t.cols[c + 1].at(y)
        val pad = (right - left) * 0.08f
        return line.coverage(left + pad, right - pad) >= 0.4f
    }

    /**
     * Grid squares [members] (row × [nc] + column) that do not form a rectangle (a broken line joined two
     * areas), cut into rectangles: each as wide and then as tall as the area allows.
     */
    private fun cutIntoRectangles(members: List<Int>, nr: Int, nc: Int): List<GridCell> {
        val free = members.toHashSet()
        val out = ArrayList<GridCell>()
        for (m in members.sorted()) {
            if (m !in free) continue
            val r = m / nc
            val c = m % nc
            var cEnd = c
            while (cEnd + 1 < nc && (r * nc + cEnd + 1) in free) cEnd++
            var rEnd = r
            while (rEnd + 1 < nr && (c..cEnd).all { ((rEnd + 1) * nc + it) in free }) rEnd++
            for (rr in r..rEnd) free -= (c..cEnd).map { rr * nc + it }.toSet()
            out += GridCell(r, c, rEnd, cEnd)
        }
        return out
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

    companion object {
        const val ASCENDERS = "bdfhijklt"
        const val DESCENDERS = "gjpqy"

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
