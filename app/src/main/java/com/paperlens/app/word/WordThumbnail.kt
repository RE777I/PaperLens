package com.paperlens.app.word

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import kotlin.math.max
import kotlin.math.roundToInt

/** A small picture of a Word document's first page, for document lists. */
object WordThumbnail {

    fun render(doc: WordDocument, width: Int): Bitmap {
        val page = doc.page
        val scale = width / page.width.toFloat() // px per twip
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val r = Renderer(doc, canvas, scale, height.toFloat())
        var y = page.marginTop * scale
        for (b in doc.blocks) {
            if (b == WPageBreak || y > height) break
            y = r.block(b, page.marginLeft * scale, y, page.textWidth * scale)
        }
        // Floating pictures at their place on the page.
        for ((_, p) in doc.paragraphs()) {
            for (img in p.images) {
                val f = img.float ?: continue
                val x = (f.x / 635f) * scale + if (f.relativeH == "page") 0f else page.marginLeft * scale
                val yy = (f.y / 635f) * scale + if (f.relativeV == "page") 0f else page.marginTop * scale
                r.picture(img, x, yy)
            }
        }
        return bitmap
    }

    private class Renderer(val doc: WordDocument, val canvas: Canvas, val scale: Float, val pageHeight: Float) {
        private val line = Paint().apply { color = Color.rgb(90, 90, 90); strokeWidth = 1f; style = Paint.Style.STROKE }

        fun block(b: WBlock, x: Float, y: Float, width: Float): Float = when (b) {
            is WParagraph -> paragraph(b, x, y, width)
            is WTable -> table(b, x, y, width)
            WPageBreak -> y
        }

        fun paragraph(p: WParagraph, x: Float, y0: Float, width: Float): Float {
            var y = y0 + p.spaceBefore * scale
            val left = x + max(0, p.indentLeft) * scale
            val w = (width - max(0, p.indentLeft) * scale - max(0, p.indentRight) * scale).coerceAtLeast(10f)
            p.shading?.let { canvas.drawRect(left, y, left + w, y + p.firstStyle.size * 20 * scale * 1.2f, Paint().apply { color = 0xFF000000.toInt() or it }) }
            // Inline pictures on their own line.
            for (img in p.images) {
                if (img.float != null) continue
                val h = picture(img, left + when (p.align) {
                    WAlign.CENTER -> (w - img.widthEmu / 635f * scale) / 2
                    WAlign.RIGHT -> w - img.widthEmu / 635f * scale
                    else -> 0f
                }, y)
                y += h
            }
            val text = SpannableStringBuilder()
            for (run in p.runs) {
                if (run.image != null || run.text.isEmpty()) continue
                val start = text.length
                text.append(run.text.replace('\t', ' '))
                val s = run.style
                text.setSpan(AbsoluteSizeSpan(max(1, (s.size * 20 * scale).roundToInt())), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val face = (if (s.bold) Typeface.BOLD else 0) or (if (s.italic) Typeface.ITALIC else 0)
                if (face != 0) text.setSpan(StyleSpan(face), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.color?.let { text.setSpan(ForegroundColorSpan(0xFF000000.toInt() or it), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            p.list?.let { text.insert(0, it.label + " ") }
            if (text.isEmpty()) {
                if (p.images.isEmpty()) y += p.firstStyle.size * 20 * scale * 1.15f
            } else {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = p.firstStyle.size * 20 * scale }
                val align = when (p.align) {
                    WAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                    WAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w.roundToInt().coerceAtLeast(1))
                    .setAlignment(align).build()
                canvas.save()
                canvas.translate(left, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height
            }
            p.borderBottom?.let { canvas.drawLine(left, y, left + w, y, line) }
            return y + p.spaceAfter * scale
        }

        fun table(t: WTable, x0: Float, y0: Float, width: Float): Float {
            val x = x0 + t.indent * scale
            val cols = FloatArray(t.grid.size + 1)
            for (i in t.grid.indices) cols[i + 1] = cols[i] + t.grid[i] * scale
            var y = y0
            for (row in t.rows) {
                if (y > pageHeight) break
                val starts = row.gridStarts()
                var rowH = (row.height ?: 0) * scale
                val heights = row.cells.mapIndexed { i, cell ->
                    if (cell.vMerge == VMerge.CONTINUE) 0f else {
                        val cw = cols[(starts[i] + cell.span).coerceAtMost(t.grid.size)] - cols[starts[i]]
                        measureCell(cell, cw)
                    }
                }
                rowH = max(rowH, heights.maxOrNull() ?: 0f)
                row.cells.forEachIndexed { i, cell ->
                    val cx = x + cols[starts[i]]
                    val cw = cols[(starts[i] + cell.span).coerceAtMost(t.grid.size)] - cols[starts[i]]
                    cell.shading?.let { canvas.drawRect(cx, y, cx + cw, y + rowH, Paint().apply { color = 0xFF000000.toInt() or it }) }
                    if (cell.vMerge != VMerge.CONTINUE) {
                        var cy = y
                        for (b in cell.blocks) cy = block(b, cx + t.cellMarginH * scale, cy, cw - 2 * t.cellMarginH * scale)
                    }
                    if (t.borders.insideV != null || t.borders.left != null) {
                        canvas.drawLine(cx, y, cx, y + rowH, line)
                        canvas.drawLine(cx + cw, y, cx + cw, y + rowH, line)
                    }
                    if (t.borders.insideH != null || t.borders.top != null) {
                        if (cell.vMerge != VMerge.CONTINUE) canvas.drawLine(cx, y, cx + cw, y, line)
                    }
                }
                y += rowH
            }
            if (t.borders.bottom != null) canvas.drawLine(x, y, x + cols.last(), y, line)
            return y
        }

        private fun measureCell(cell: WCell, width: Float): Float {
            var h = 0f
            for (b in cell.blocks) {
                h += when (b) {
                    is WParagraph -> {
                        val textLen = b.text.length
                        val size = b.firstStyle.size * 20 * scale
                        val imgH = b.images.filter { it.float == null }.sumOf { (it.heightEmu / 635f * scale).toDouble() }.toFloat()
                        val lines = if (textLen == 0) (if (imgH > 0) 0 else 1) else max(1, (textLen * size * 0.5f / width.coerceAtLeast(1f)).roundToInt())
                        imgH + lines * size * 1.2f
                    }
                    else -> 0f
                }
            }
            return h
        }

        fun picture(img: WImage, x: Float, y: Float): Float {
            val w = img.widthEmu / 635f * scale
            val h = img.heightEmu / 635f * scale
            val media = doc.media[img.mediaKey] ?: return h
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(media.bytes, 0, media.bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= w && opts.outHeight / (sample * 2) >= h) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(media.bytes, 0, media.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return h
            canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            bmp.recycle()
            return h
        }
    }
}
