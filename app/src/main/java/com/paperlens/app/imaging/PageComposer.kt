package com.paperlens.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Page-level compositions: ID card sheet, book split, watermark, long image, overlays. */
object PageComposer {

    // A4 at 300 dpi and an ISO/IEC 7810 ID-1 card (85.6 x 54 mm) at the same resolution.
    private const val A4_W = 2480
    private const val A4_H = 3508
    private const val CARD_W = 1011
    private const val CARD_H = 638

    /** Places the front (and optional back) of a card at real size on one A4 sheet, like a photocopy. */
    fun composeIdCard(front: Bitmap, back: Bitmap?): Bitmap {
        val sheet = Bitmap.createBitmap(A4_W, A4_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cards = listOfNotNull(front, back)
        cards.forEachIndexed { index, card ->
            // Cards are landscape; rotate portrait captures.
            val img = if (card.height > card.width) BitmapUtils.rotate(card, 90) else card
            val centerY = if (cards.size == 1) A4_H / 3f else A4_H * (index + 1) / 3.2f
            val dst = RectF(
                (A4_W - CARD_W) / 2f,
                centerY - CARD_H / 2f,
                (A4_W + CARD_W) / 2f,
                centerY + CARD_H / 2f,
            )
            canvas.drawBitmap(img, null, fitInside(img.width, img.height, dst), paint)
        }
        return sheet
    }

    /** Splits a two-page book spread down the middle into left and right pages. */
    fun splitBook(spread: Bitmap): List<Bitmap> {
        if (spread.width < spread.height) return listOf(spread) // already a single page
        val half = spread.width / 2
        return listOf(
            Bitmap.createBitmap(spread, 0, 0, half, spread.height),
            Bitmap.createBitmap(spread, half, 0, spread.width - half, spread.height),
        )
    }

    /** Tiles a semi-transparent diagonal text watermark across the page. */
    fun watermark(src: Bitmap, text: String, color: Int = Color.GRAY, opacity: Float = 0.3f): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
            textSize = max(out.width, out.height) / 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        val textWidth = paint.measureText(text)
        val stepX = textWidth + paint.textSize * 2
        val stepY = paint.textSize * 4
        val diagonal = sqrt((out.width * out.width + out.height * out.height).toFloat())
        canvas.save()
        canvas.rotate(-30f, out.width / 2f, out.height / 2f)
        var y = (out.height - diagonal) / 2
        var row = 0
        while (y < (out.height + diagonal) / 2) {
            var x = (out.width - diagonal) / 2 - (row % 2) * stepX / 2
            while (x < (out.width + diagonal) / 2) {
                canvas.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY
            row++
        }
        canvas.restore()
        return out
    }

    /** Smart Erase: paints each region with the paper colour sampled around it. */
    fun erase(src: Bitmap, regions: List<RectF>): Bitmap =
        TextEdits.render(src, regions.map { TextEdits.erase(src, it) })

    /** Mosaic: pixelates each region (e.g. to hide signatures, IDs, faces). */
    fun mosaic(src: Bitmap, regions: List<RectF>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val block = max(8, max(out.width, out.height) / 90)
        val paint = Paint()
        for (r in regions) {
            val left = r.left.roundToInt().coerceIn(0, out.width - 1)
            val top = r.top.roundToInt().coerceIn(0, out.height - 1)
            val right = r.right.roundToInt().coerceIn(left + 1, out.width)
            val bottom = r.bottom.roundToInt().coerceIn(top + 1, out.height)
            pixelate(src, canvas, paint, Rect(left, top, right, bottom), block)
        }
        return out
    }

    /** Fills [area] with squares of [block] pixels, each in the colour at its centre in [src]. */
    private fun pixelate(src: Bitmap, canvas: Canvas, paint: Paint, area: Rect, block: Int) {
        for (y in area.top until area.bottom step block) for (x in area.left until area.right step block) {
            val bw = min(block, area.right - x)
            val bh = min(block, area.bottom - y)
            paint.color = src.getPixel(x + bw / 2, y + bh / 2)
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + bw).toFloat(), (y + bh).toFloat(), paint)
        }
    }

    /** Add Text: writes [text] with its top-left at ([x], [y]) image pixels. */
    /** Bakes styled text into a copy of [src]; ([x], [y]) is the top-left of the text block. */
    fun addText(src: Bitmap, text: String, x: Float, y: Float, size: Float, look: TextLook): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        drawText(Canvas(out), text, x, y, size, look)
        return out
    }

    /**
     * Draws [text] as one block whose top-left is ([x], [y]): an optional rounded background behind
     * all lines, then the lines aligned within the block. Used for both the on-screen preview and the
     * saved page, so what you see is what you get.
     */
    fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, look: TextLook) {
        val bounds = textBounds(text, x, y, size, look)
        look.background?.let { bg ->
            val pad = size * 0.25f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bg
                alpha = (look.backgroundOpacity.coerceIn(0f, 1f) * 255).toInt()
            }
            canvas.drawRoundRect(RectF(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad), size * 0.2f, size * 0.2f, fill)
        }
        val paint = textPaint(size, look)
        val lines = text.lines()
        lines.forEachIndexed { i, line ->
            val lineX = when (look.align) {
                Paint.Align.CENTER -> (bounds.left + bounds.right) / 2
                Paint.Align.RIGHT -> bounds.right
                else -> bounds.left
            }
            canvas.drawText(line, lineX, y - paint.ascent() + i * paint.fontSpacing, paint)
        }
    }

    /** The area the text itself covers (without the background padding). */
    fun textBounds(text: String, x: Float, y: Float, size: Float, look: TextLook): RectF {
        val paint = textPaint(size, look)
        val lines = text.lines()
        val width = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val height = (lines.size - 1) * paint.fontSpacing + (paint.descent() - paint.ascent())
        return RectF(x, y, x + width, y + height)
    }

    private fun textPaint(size: Float, look: TextLook) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.color
        alpha = (look.opacity.coerceIn(0f, 1f) * 255).toInt()
        textSize = size
        textAlign = look.align
        typeface = Typeface.create(
            Typeface.DEFAULT,
            when {
                look.bold && look.italic -> Typeface.BOLD_ITALIC
                look.bold -> Typeface.BOLD
                look.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            },
        )
    }

    /** Draws [text] (date / time) in the bottom-right corner on a translucent white pill. */
    fun timestamp(src: Bitmap, text: String): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 40, 40)
            textSize = max(out.width, out.height) / 40f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val pad = paint.textSize * 0.5f
        val w = paint.measureText(text)
        val right = out.width - paint.textSize
        val bottom = out.height - paint.textSize
        val box = RectF(right - w - 2 * pad, bottom - paint.textSize - 2 * pad, right, bottom)
        canvas.drawRoundRect(box, pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) })
        canvas.drawText(text, box.left + pad, box.bottom - pad - paint.descent() / 2, paint)
        return out
    }

    /** Where a page number goes on the page. */
    enum class NumberPlace { BOTTOM_CENTER, BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT }

    /** Draws [text] (a page number) in the page margin at [place]: dark with a white edge, readable on any paper. */
    fun pageNumber(src: Bitmap, text: String, place: NumberPlace): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            textSize = max(out.width, out.height) / 45f
            typeface = Typeface.DEFAULT_BOLD
        }
        val margin = paint.textSize * 1.2f
        val w = paint.measureText(text)
        val x = when (place) {
            NumberPlace.BOTTOM_CENTER -> (out.width - w) / 2
            NumberPlace.BOTTOM_LEFT -> margin
            NumberPlace.BOTTOM_RIGHT, NumberPlace.TOP_RIGHT -> out.width - margin - w
        }
        val y = if (place == NumberPlace.TOP_RIGHT) margin + paint.textSize * 0.8f else out.height - margin
        val edge = Paint(paint).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = paint.textSize * 0.22f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawText(text, x, y, edge)
        canvas.drawText(text, x, y, paint)
        return out
    }

    /** Stitches pages vertically into one long image, scaled to a common width and a bounded height. */
    fun longImage(pages: List<Bitmap>, targetWidth: Int = 1240, maxHeight: Int = 24000): Bitmap {
        val gap = 16
        val heights = pages.map { it.height * targetWidth / it.width }
        val total = heights.sum() + gap * (pages.size - 1)
        val scale = min(1f, maxHeight.toFloat() / total)
        val width = (targetWidth * scale).roundToInt()
        val out = Bitmap.createBitmap(width, (total * scale).roundToInt(), Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(230, 230, 230))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var top = 0f
        pages.forEachIndexed { i, page ->
            val h = heights[i] * scale
            canvas.drawBitmap(page, null, RectF(0f, top, width.toFloat(), top + h), paint)
            top += h + gap * scale
        }
        return out
    }

    /**
     * Draws [overlay] (signature / annotation layer) onto [base].
     * [left], [top] and [width] are fractions of the base image width/height.
     */
    fun overlay(base: Bitmap, overlay: Bitmap, left: Float, top: Float, width: Float): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val dstW = width * out.width
        val dstH = dstW * overlay.height / overlay.width
        val dst = RectF(left * out.width, top * out.height, left * out.width + dstW, top * out.height + dstH)
        Canvas(out).drawBitmap(overlay, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun fitInside(srcW: Int, srcH: Int, box: RectF): RectF {
        val scale = min(box.width() / srcW, box.height() / srcH)
        val w = srcW * scale
        val h = srcH * scale
        return RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
    }
}

/** How added text looks: colours are ARGB, opacities 0..1, [background] null for none. */
data class TextLook(
    val color: Int = Color.BLACK,
    val opacity: Float = 1f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: Paint.Align = Paint.Align.LEFT,
    val background: Int? = null,
    val backgroundOpacity: Float = 0.85f,
)
