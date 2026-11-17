package com.paperlens.app.word

import android.graphics.RectF
import com.paperlens.app.word.PageAnalysis.Companion.median
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Word appearance for [PageAnalysis]: stroke weight, size from the ink core, and ink colour.

/** Stroke thickness and colour of a word. */
internal fun PageAnalysis.styleWord(word: OcrWord) {
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
internal fun PageAnalysis.inkSpan(box: RectF): Pair<Float, Float>? {
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
internal fun PageAnalysis.emFromCore(box: RectF, text: String): Float? {
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
private fun PageAnalysis.onWhite(c: Int, paperLevel: Int): Int {
    val f = 255f / paperLevel.coerceAtLeast(60)
    val r = (((c shr 16) and 0xFF) * f).roundToInt().coerceAtMost(255)
    val g = (((c shr 8) and 0xFF) * f).roundToInt().coerceAtMost(255)
    val b = ((c and 0xFF) * f).roundToInt().coerceAtMost(255)
    return (r shl 16) or (g shl 8) or b
}

/** Dark, low-saturation ink is "automatic" (black); clear colours are kept. */
internal fun PageAnalysis.inkColor(samples: List<Int>): Int? {
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
