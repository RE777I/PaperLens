package com.paperlens.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A change to the text printed on a page image. Coordinates are page-image pixels. */
sealed interface TextEdit {
    val box: RectF
    val background: Int
}

/** Paints the line out with the surrounding paper colour. */
data class EraseEdit(override val box: RectF, override val background: Int) : TextEdit

/** Paints the line out and writes [text] in its place, matching size, width, weight and ink colour. */
data class ReplaceEdit(
    override val box: RectF,
    override val background: Int,
    val text: String,
    val color: Int,
    val textSize: Float,
    val scaleX: Float,
    val baseline: Float,
    val bold: Boolean,
) : TextEdit

object TextEdits {

    /** Samples [bitmap] around [box] and builds an erase. */
    fun erase(bitmap: Bitmap, box: RectF): EraseEdit = EraseEdit(box, tones(bitmap, box).background)

    /**
     * Builds a replacement styled after the original line: the size comes from measuring
     * [originalText] against the box, so capitals, descenders and font width line up. Longer new text is
     * narrowed (and if need be shrunk) so it stays inside the original box.
     */
    fun replace(bitmap: Bitmap, box: RectF, originalText: String, newText: String): ReplaceEdit {
        val tones = tones(bitmap, box)
        val bold = tones.inkShare > 0.22f
        val paint = paint(bold, 100f)
        val bounds = Rect()
        val sample = originalText.ifBlank { newText }.ifBlank { "Ag" }
        paint.getTextBounds(sample, 0, sample.length, bounds)
        var textSize = if (bounds.height() > 0) 100f * box.height() / bounds.height() else box.height()
        paint.textSize = textSize
        var baseline = box.bottom - bounds.bottom * textSize / 100f
        val naturalWidth = paint.measureText(sample)
        var scaleX = if (naturalWidth > 0) (box.width() / naturalWidth).coerceIn(0.6f, 1.6f) else 1f
        val newWidth = paint.measureText(newText) * scaleX
        if (newWidth > box.width() * 1.02f && newWidth > 0f) {
            scaleX *= box.width() / newWidth
            if (scaleX < MIN_SCALE_X) {
                val shrink = scaleX / MIN_SCALE_X
                // keep the smaller text centred on the original line instead of sitting on its baseline
                baseline -= (box.bottom - baseline + box.height()) * (1 - shrink) / 2
                textSize *= shrink
                scaleX = MIN_SCALE_X
            }
        }
        return ReplaceEdit(box, tones.background, newText, tones.ink, textSize, scaleX, baseline, bold)
    }

    private const val MIN_SCALE_X = 0.7f

    /** Draws [edit] on [canvas], whose coordinate system is page-image pixels. */
    fun draw(canvas: Canvas, edit: TextEdit) {
        val pad = max(2f, edit.box.height() * 0.08f)
        val fill = Paint().apply { color = edit.background }
        canvas.drawRect(edit.box.left - pad, edit.box.top - pad, edit.box.right + pad, edit.box.bottom + pad, fill)
        if (edit is ReplaceEdit) {
            val paint = paint(edit.bold, edit.textSize).apply {
                color = edit.color
                textScaleX = edit.scaleX
            }
            canvas.drawText(edit.text, edit.box.left, edit.baseline, paint)
        }
    }

    /** Applies [edits] in order to a copy of [page] and returns it. */
    fun render(page: Bitmap, edits: List<TextEdit>, scale: Float = 1f): Bitmap {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        canvas.scale(scale, scale)
        edits.forEach { draw(canvas, it) }
        return out
    }

    private fun paint(bold: Boolean, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textSize = size
    }

    /** Background and ink colours of the text in a box, and how much of the box the ink covers. */
    class Tones(val background: Int, val ink: Int, val inkShare: Float)

    /**
     * Splits the pixels inside [box] into two tones with Otsu's threshold. Text covers less of its box
     * than the paper behind it, so the larger group is the background and the smaller one the ink. This
     * works for light text on dark bands as well as dark text on paper.
     */
    fun tones(bitmap: Bitmap, box: RectF): Tones {
        val left = box.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.roundToInt().coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, left, top, w, h)
        val lums = IntArray(px.size) { lum(px[it]) }
        val hist = IntArray(256)
        lums.forEach { hist[it]++ }
        val threshold = otsu(hist, px.size)
        val dark = px.indices.filter { lums[it] <= threshold }
        val light = px.indices.filter { lums[it] > threshold }
        if (dark.isEmpty() || light.isEmpty()) {
            val bg = median(px, px.indices.toList())
            return Tones(bg, if (lum(bg) > 128) Color.BLACK else Color.WHITE, 0f)
        }
        val (bgIdx, inkIdx) = if (light.size >= dark.size) light to dark else dark to light
        val bg = median(px, bgIdx)
        val bgLum = lum(bg)
        // the most contrasting half of the ink: stroke centres rather than anti-aliased edges
        val strongest = inkIdx.sortedByDescending { abs(lums[it] - bgLum) }.take(max(1, inkIdx.size / 2))
        if (abs(lums[strongest.first()] - bgLum) < 40) {
            return Tones(bg, if (bgLum > 128) Color.BLACK else Color.WHITE, 0f)
        }
        return Tones(bg, median(px, strongest), inkIdx.size.toFloat() / px.size)
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        val sumAll = hist.indices.sumOf { it.toLong() * hist[it] }.toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    private fun median(px: IntArray, idx: List<Int>): Int {
        fun channel(f: (Int) -> Int) = idx.map { f(px[it]) }.sorted()[idx.size / 2]
        return Color.rgb(channel(Color::red), channel(Color::green), channel(Color::blue))
    }

    private fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}
