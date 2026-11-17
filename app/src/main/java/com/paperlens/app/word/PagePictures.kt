package com.paperlens.app.word

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Picture finding for [PageAnalysis]: photos, logos, QR codes and drawings among the text.

/**
 * Areas that are neither text nor table lines nor plain paper: photos, logos, QR codes, drawings,
 * and text the OCR could not read. [known] (e.g. barcodes) are always pictures.
 */
internal fun PageAnalysis.pictures(words: List<OcrWord>, rules: List<Rule>, exclude: List<RectF>, known: List<RectF>, textHeight: Float): List<RectF> {
    val (loose, strict) = contentMasks()
    for (mask in arrayOf(loose, strict)) {
        for (wd in words) if (wd.confidence >= 0.5f) clear(mask, wd.box, wd.box.height() * 0.15f)
        for (rule in rules) clearRule(mask, rule)
        for (e in exclude) clear(mask, e, 0f)
    }
    val out = ArrayList<RectF>()
    for (rect in components(loose, null, 2, textHeight)) {
        if (textShare(words, rect) > 0.45 || isGlyph(words, rect) || isFlat(rect, words)) continue
        if (wordsIn(words, rect) <= 4) {
            out += rect
            continue
        }
        // Many words inside: the loose mask joined text and background. Look again, strictly.
        out += components(strict, rect, 1, textHeight).filter { wordsIn(words, it) <= 3 && textShare(words, it) < 0.3 }
    }
    out += known
    return mergeRects(out, w * 0.01f)
}

/**
 * Which pixels are content rather than paper. Loose: dark, textured or coloured. Strict (for areas full of
 * text, e.g. a stained sign with photos on it): only clearly dark or clearly coloured.
 */
private fun PageAnalysis.contentMasks(): Pair<BooleanArray, BooleanArray> {
    val loose = BooleanArray(w * h)
    val strict = BooleanArray(w * h)
    for (i in loose.indices) {
        val gv = gray[i].toInt() and 0xFF
        val bv = bg[i].toInt() and 0xFF
        val c = px[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val sat = max(r, max(g, b)) - min(r, min(g, b))
        val x = i % w
        val y = i / w
        val grad = if (x in 1 until w - 1 && y in 1 until h - 1) {
            abs((gray[i + 1].toInt() and 0xFF) - (gray[i - 1].toInt() and 0xFF)) +
                abs((gray[i + w].toInt() and 0xFF) - (gray[i - w].toInt() and 0xFF))
        } else 0
        loose[i] = gv < bv - 60 || (grad > 24 && gv < bv - 18) || (sat > 60 && gv < bv - 8)
        strict[i] = gv < bv - 70 || (sat > 70 && gv < bv - 12)
    }
    return loose to strict
}

/** Removes [rect], grown by [pad], from [mask]. */
private fun PageAnalysis.clear(mask: BooleanArray, rect: RectF, pad: Float) {
    val x0 = (rect.left - pad).toInt().coerceIn(0, w)
    val x1 = (rect.right + pad).toInt().coerceIn(0, w)
    val y0 = (rect.top - pad).toInt().coerceIn(0, h)
    val y1 = (rect.bottom + pad).toInt().coerceIn(0, h)
    for (y in y0 until y1) for (x in x0 until x1) mask[y * w + x] = false
}

/** Removes a ruled line from [mask], following its slope one pixel at a time. */
private fun PageAnalysis.clearRule(mask: BooleanArray, rule: Rule) {
    val half = rule.thickness / 2 + 2
    var t = rule.start
    while (t <= rule.end) {
        val across = rule.at(t)
        val slice = if (rule.horizontal) RectF(t, across - half, t + 1, across + half) else RectF(across - half, t, across + half, t + 1)
        clear(mask, slice, 0f)
        t += 1f
    }
}

/** Confidently read words whose centre lies in [rect]. */
private fun wordsIn(words: List<OcrWord>, rect: RectF): Int =
    words.count { it.confidence >= 0.6f && rect.contains(it.box.centerX(), it.box.centerY()) }

/** The share of [rect] covered by word boxes. */
private fun textShare(words: List<OcrWord>, rect: RectF): Double {
    val covered = words.sumOf { wd ->
        val overlap = RectF(wd.box)
        if (!overlap.intersect(rect)) 0.0 else (overlap.width() * overlap.height()).toDouble()
    }
    return covered / (rect.width() * rect.height()).coerceAtLeast(1f)
}

/** A component that is mostly one recognised word (a large capital, a logo letter). */
private fun isGlyph(words: List<OcrWord>, rect: RectF): Boolean = words.any { wd ->
    val overlap = RectF(wd.box)
    if (overlap.intersect(rect) && overlap.width() * overlap.height() >= 0.5f * rect.width() * rect.height()) return@any true
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
private fun PageAnalysis.isFlat(rect: RectF, words: List<OcrWord>): Boolean {
    val x0 = rect.left.toInt().coerceIn(0, w - 1)
    val x1 = rect.right.toInt().coerceIn(x0 + 1, w)
    val y0 = rect.top.toInt().coerceIn(0, h - 1)
    val y1 = rect.bottom.toInt().coerceIn(y0 + 1, h)
    val wordAreas = words.filter { RectF.intersects(it.box, rect) }
        .map { RectF(it.box).apply { inset(-it.box.height() * 0.2f, -it.box.height() * 0.2f) } }
    val step = max(1, min(x1 - x0, y1 - y0) / 60)
    var n = 0
    var sum = 0.0
    var squares = 0.0
    for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
        if (wordAreas.any { it.contains(x.toFloat(), y.toFloat()) }) continue
        val v = (gray[y * w + x].toInt() and 0xFF) - (bg[y * w + x].toInt() and 0xFF)
        n++
        sum += v
        squares += v.toDouble() * v
    }
    if (n < 30) return false
    val mean = sum / n
    return sqrt(max(0.0, squares / n - mean * mean)) < 12.0
}

/** The page (or a part of it) divided into square blocks of [size] pixels: [bw] × [bh] blocks from block ([ox], [oy]). */
private class BlockGrid(val size: Int, val ox: Int, val oy: Int, val bw: Int, val bh: Int)

/** A connected group of blocks: its bounds in block coordinates and how many blocks it has. */
private class BlockRegion(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val area: Int)

/**
 * Connected areas of [mask] (inside [within], or the whole page), in blocks of ~0.4 % of the short
 * side after closing gaps of [close] blocks; only areas big and solid enough to be a picture.
 */
private fun PageAnalysis.components(mask: BooleanArray, within: RectF?, close: Int, textHeight: Float): List<RectF> {
    val bs = max(4, min(w, h) / 250)
    val ox = ((within?.left ?: 0f) / bs).toInt()
    val oy = ((within?.top ?: 0f) / bs).toInt()
    val bw = ((within?.right ?: w.toFloat()) / bs).toInt().coerceAtMost((w + bs - 1) / bs) - ox
    val bh = ((within?.bottom ?: h.toFloat()) / bs).toInt().coerceAtMost((h + bs - 1) / bs) - oy
    if (bw <= 0 || bh <= 0) return emptyList()
    val grid = BlockGrid(bs, ox, oy, bw, bh)

    var blocks = BooleanArray(bw * bh) { setPixels(mask, grid, it % bw, it / bw) >= bs * bs / 5 }
    // Close small gaps (a photo with white patches, a QR code's quiet modules).
    if (close > 0) blocks = erode(dilate(blocks, bw, bh, close), bw, bh, close)

    val seen = BooleanArray(bw * bh)
    val queue = IntArray(bw * bh)
    val out = ArrayList<RectF>()
    for (start in blocks.indices) {
        if (!blocks[start] || seen[start]) continue
        val region = connectedRegion(blocks, seen, queue, start, grid)
        val rect = RectF(
            ((ox + region.minX) * bs).toFloat(),
            ((oy + region.minY) * bs).toFloat(),
            ((ox + region.maxX + 1) * bs).toFloat().coerceAtMost(w.toFloat()),
            ((oy + region.maxY + 1) * bs).toFloat().coerceAtMost(h.toFloat()),
        )
        val boxArea = (region.maxX - region.minX + 1) * (region.maxY - region.minY + 1)
        val bigEnough = rect.width() >= w * 0.035f && rect.height() >= max(textHeight * 1.6f, w * 0.025f)
        if (bigEnough && region.area >= boxArea * 0.18f) out += rect
    }
    return out
}

/** How many pixels of [mask] are set in block ([bx], [by]) of [grid]. */
private fun PageAnalysis.setPixels(mask: BooleanArray, grid: BlockGrid, bx: Int, by: Int): Int {
    val x0 = (grid.ox + bx) * grid.size
    val y0 = (grid.oy + by) * grid.size
    var n = 0
    for (y in y0 until min(h, y0 + grid.size)) for (x in x0 until min(w, x0 + grid.size)) {
        if (mask[y * w + x]) n++
    }
    return n
}

/** The blocks connected to [start] (also diagonally), marking them in [seen]; [queue] is scratch space. */
private fun connectedRegion(blocks: BooleanArray, seen: BooleanArray, queue: IntArray, start: Int, grid: BlockGrid): BlockRegion {
    var head = 0
    var tail = 0
    queue[tail++] = start
    seen[start] = true
    var minX = grid.bw
    var minY = grid.bh
    var maxX = 0
    var maxY = 0
    while (head < tail) {
        val i = queue[head++]
        val bx = i % grid.bw
        val by = i / grid.bw
        minX = min(minX, bx); maxX = max(maxX, bx)
        minY = min(minY, by); maxY = max(maxY, by)
        for (n in NEIGHBOUR_DX.indices) {
            val nx = bx + NEIGHBOUR_DX[n]
            val ny = by + NEIGHBOUR_DY[n]
            if (nx !in 0 until grid.bw || ny !in 0 until grid.bh) continue
            val j = ny * grid.bw + nx
            if (blocks[j] && !seen[j]) {
                seen[j] = true
                queue[tail++] = j
            }
        }
    }
    // Every queued block was taken out once, so the queue's length is the region's size.
    return BlockRegion(minX, minY, maxX, maxY, area = tail)
}

/** The eight blocks around a block. */
private val NEIGHBOUR_DX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
private val NEIGHBOUR_DY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

/** Grows the set blocks of [m] by [r] blocks in every direction. */
private fun dilate(m: BooleanArray, bw: Int, bh: Int, r: Int): BooleanArray {
    val out = BooleanArray(m.size)
    for (y in 0 until bh) for (x in 0 until bw) {
        if (m[y * bw + x]) setAround(out, x, y, r, bw, bh)
    }
    return out
}

private fun setAround(out: BooleanArray, x: Int, y: Int, r: Int, bw: Int, bh: Int) {
    for (ny in max(0, y - r)..min(bh - 1, y + r)) for (nx in max(0, x - r)..min(bw - 1, x + r)) {
        out[ny * bw + nx] = true
    }
}

/** Keeps a block only when every block within [r] of it is set (the grid's edge blocks repeat outside it). */
private fun erode(m: BooleanArray, bw: Int, bh: Int, r: Int): BooleanArray {
    val out = BooleanArray(m.size)
    for (y in 0 until bh) for (x in 0 until bw) out[y * bw + x] = allSetAround(m, x, y, r, bw, bh)
    return out
}

private fun allSetAround(m: BooleanArray, x: Int, y: Int, r: Int, bw: Int, bh: Int): Boolean {
    for (dy in -r..r) for (dx in -r..r) {
        val nx = (x + dx).coerceIn(0, bw - 1)
        val ny = (y + dy).coerceIn(0, bh - 1)
        if (!m[ny * bw + nx]) return false
    }
    return true
}

/** Joins rectangles that touch or lie within [gap] of each other, until none do. */
internal fun PageAnalysis.mergeRects(rects: List<RectF>, gap: Float): List<RectF> {
    val list = rects.map { RectF(it) }.toMutableList()
    while (mergeFirstClosePair(list, gap)) {
        // Each merge can bring the joined rectangle close to others, so look again from the start.
    }
    return list
}

/** Joins the first two rectangles of [list] within [gap] of each other; false when there are none. */
private fun mergeFirstClosePair(list: MutableList<RectF>, gap: Float): Boolean {
    for (i in list.indices) for (j in i + 1 until list.size) {
        val a = list[i]
        val b = list[j]
        if (a.left - gap <= b.right && b.left - gap <= a.right && a.top - gap <= b.bottom && b.top - gap <= a.bottom) {
            a.union(b)
            list.removeAt(j)
            return true
        }
    }
    return false
}
