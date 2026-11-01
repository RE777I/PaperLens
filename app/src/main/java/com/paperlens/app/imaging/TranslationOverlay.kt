package com.paperlens.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.paperlens.app.ml.TextBlock
import kotlin.math.max

/**
 * Puts translations on the page itself, like a camera translator: each paragraph's area is painted
 * over in its own background colour and the translation is written there in the original ink colour,
 * starting from the original line height and shrinking until it fits the paragraph's box.
 */
object TranslationOverlay {

    /** A copy of [page] with [translations] drawn over their [blocks] (same order; blank = untouched). */
    fun render(page: Bitmap, blocks: List<TextBlock>, translations: List<String>): Bitmap {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        blocks.zip(translations).forEach { (block, text) -> if (text.isNotBlank()) drawBlock(canvas, page, block, text.trim()) }
        return out
    }

    private fun drawBlock(canvas: Canvas, src: Bitmap, block: TextBlock, text: String) {
        val heights = block.lines.map { it.box.height() }.sorted()
        val lineHeight = heights[heights.size / 2].coerceAtLeast(6f)
        val box = block.box
        val tones = TextEdits.tones(src, box)

        // Cover the original text with its own background colour.
        val pad = lineHeight * 0.2f
        val cover = RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        canvas.drawRoundRect(cover, pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tones.background })

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tones.ink
            typeface = if (tones.inkShare > 0.22f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = max(1, box.width().toInt())
        var size = lineHeight * 0.8f
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
            if (layout.height <= cover.height() || size < 7f) break
            size *= 0.9f
        }
        canvas.save()
        canvas.clipRect(cover)
        // Centre vertically when the translation is shorter than the original paragraph.
        canvas.translate(box.left, box.top + max(0f, (box.height() - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
    }
}
