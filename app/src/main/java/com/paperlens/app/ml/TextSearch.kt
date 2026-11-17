package com.paperlens.app.ml

import android.content.Context
import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.runLogged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A page's recognised text prepared for Find Text: lower-cased, whitespace collapsed to single
 * spaces, and no spaces next to Chinese/Japanese characters (OCR puts them there at random, and those
 * scripts have no word spaces), so a query matches however the OCR split the words.
 *
 * Built from the stored plain `ocrText` it can only count matches; built from an ML Kit result it
 * also knows where each character sits, as fractions of the page image, for highlighting.
 */
class SearchableText private constructor(
    private val text: String,
    private val boxes: List<RectF?>,
    private val lines: IntArray,
    /** Width / height of the image the boxes refer to; 0 when there are no boxes. */
    val aspect: Float,
) {
    val hasBoxes get() = aspect > 0f

    /** Non-overlapping occurrences of [query], case-insensitive. */
    fun find(query: String): List<IntRange> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val found = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(q, from)
            if (at < 0) break
            found += at until at + q.length
            from = at + q.length
        }
        return if (hasBoxes) readingOrder(found) else found
    }

    /** OCR blocks are not always in reading order: sort matches into rows top to bottom, then left to right. */
    private fun readingOrder(found: List<IntRange>): List<IntRange> {
        val placed = found.map { it to rects(it).firstOrNull() }.sortedBy { it.second?.top ?: 0f }
        val rows = mutableListOf<MutableList<Pair<IntRange, RectF?>>>()
        var rowBottom = -1f
        for (p in placed) {
            val r = p.second
            if (rows.isEmpty() || r == null || r.top > rowBottom) {
                rows += mutableListOf(p)
                rowBottom = r?.let { it.top + it.height() / 2 } ?: -1f
            } else {
                rows.last() += p
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.second?.left ?: 0f }.map { it.first } }
    }

    /** The highlight for a match from [find]: one rectangle per text line it covers (image fractions). */
    fun rects(match: IntRange): List<RectF> {
        if (!hasBoxes) return emptyList()
        val byLine = LinkedHashMap<Int, RectF>()
        for (i in match) {
            val box = boxes[i] ?: continue
            byLine.getOrPut(lines[i]) { RectF(box) }.union(box)
        }
        return byLine.values.toList()
    }

    private class Builder {
        val text = StringBuilder()
        val boxes = mutableListOf<RectF?>()
        val lines = mutableListOf<Int>()
        private var space = false

        fun add(c: Char, box: RectF?, line: Int) {
            if (c.isWhitespace()) {
                space = true
                return
            }
            if (space && text.isNotEmpty() && !isCjk(text.last()) && !isCjk(c)) {
                text.append(' '); boxes += null; lines += -1
            }
            space = false
            text.append(c.lowercaseChar()); boxes += box; lines += line
        }

        fun gap() {
            space = true
        }

        fun build(aspect: Float) = SearchableText(text.toString(), boxes, lines.toIntArray(), aspect)
    }

    companion object {
        /** From stored text: counts matches only. */
        fun fromText(text: String): SearchableText {
            val b = Builder()
            text.forEach { b.add(it, null, -1) }
            return b.build(0f)
        }

        /**
         * From characters that already have boxes (image fractions; null for spaces and breaks) and line
         * numbers, e.g. the text inside a PDF page. [aspect] is the page's width / height.
         */
        fun fromChars(chars: List<Char>, boxes: List<RectF?>, lines: List<Int>, aspect: Float): SearchableText {
            val b = Builder()
            chars.forEachIndexed { i, c -> if (c.isWhitespace()) b.gap() else b.add(c, boxes[i], lines[i]) }
            return b.build(aspect)
        }

        /** One recognised word: its line number, box (image pixels; null if unknown) and text. */
        private class Word(val line: Int, val box: android.graphics.Rect?, val text: String)

        private fun words(result: Text): List<Word> {
            val out = ArrayList<Word>()
            var lineNo = 0
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    line.elements.forEach { out += Word(lineNo, it.boundingBox, it.text) }
                    lineNo++
                }
            }
            return out
        }

        private fun fromWords(words: List<Word>, width: Int, height: Int): SearchableText {
            val b = Builder()
            for (word in words) {
                // ML Kit's per-character (symbol) boxes sit up to half a character to the right,
                // so the element's box is split by character width instead.
                val box = word.box
                if (box == null) word.text.forEach { b.add(it, null, word.line) }
                else spread(word.text, box, width, height) { c, r -> b.add(c, r, word.line) }
                b.gap()
            }
            return b.build(width.toFloat() / height)
        }

        /** From an OCR result of a [width] × [height] image, keeping a box for every character. */
        fun fromOcr(result: Text, width: Int, height: Int): SearchableText = fromWords(words(result), width, height)

        /**
         * The words of [result] with their boxes as compact text, stored with a page (`ocrLayout`) so
         * Find Text can highlight without reading the page again. Stamped with [imageName]: an edited
         * page gets a new image file, which makes the stored layout stale.
         */
        fun encodeLayout(result: Text, width: Int, height: Int, imageName: String): String = buildString {
            append(LAYOUT_V1).append('\t').append(imageName).append('\t').append(width).append('\t').append(height).append('\n')
            for (w in words(result)) {
                val b = w.box ?: continue
                append(w.line).append('\t').append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom)
                    .append('\t').append(w.text.replace('\t', ' ').replace('\n', ' ')).append('\n')
            }
        }

        /** A stored layout, or null when there is none, it is unreadable or it belongs to another image. */
        fun fromLayout(layout: String?, imageName: String): SearchableText? = runLogged("TextSearch", "Reading stored word positions") {
            if (layout == null) return null
            val lines = layout.split('\n')
            val head = lines.first().split('\t')
            if (head.size < 4 || head[0] != LAYOUT_V1 || head[1] != imageName) return null
            val words = lines.drop(1).filter { it.isNotEmpty() }.map { row ->
                val (line, box, text) = row.split('\t', limit = 3)
                val (l, t, r, bt) = box.split(',').map(String::toInt)
                Word(line.toInt(), android.graphics.Rect(l, t, r, bt), text)
            }
            fromWords(words, head[2].toInt(), head[3].toInt())
        }.getOrNull()

        private const val LAYOUT_V1 = "L1"

        /**
         * Splits [box] across the characters of [text] (left to right), as image fractions. Chinese and
         * Japanese characters and symbols are full width; Latin characters count as a bit over half of that.
         */
        private inline fun spread(text: String, box: android.graphics.Rect, w: Int, h: Int, emit: (Char, RectF) -> Unit) {
            // From U+2460 (circled numbers) up: CJK punctuation, symbols and full-width forms.
            val weights = text.map { if (it.code >= 0x2460) 1f else 0.55f }
            val unit = box.width() / weights.sum()
            var left = box.left.toFloat()
            text.forEachIndexed { i, c ->
                val right = left + weights[i] * unit
                emit(c, RectF(left / w, box.top.toFloat() / h, right / w, box.bottom.toFloat() / h))
                left = right
            }
        }

        fun normalize(query: String): String = Builder().apply { query.forEach { add(it, null, -1) } }.text.toString()

        private fun isCjk(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
            else -> false
        }

        /** A page read with positions: searchable text, plain text (as stored in `ocrText`) and the layout to store. */
        class Reading(val searchable: SearchableText, val plain: String, val layout: String)

        /** Reads [image] with positions. */
        suspend fun read(context: Context, image: File, script: OcrScript): Reading {
            val bitmap = withContext(Dispatchers.IO) { BitmapUtils.decode(image) }
            return try {
                val result = Ocr.recognizeText(context, bitmap, script)
                Reading(
                    fromOcr(result, bitmap.width, bitmap.height),
                    result.textBlocks.joinToString("\n\n") { it.text },
                    encodeLayout(result, bitmap.width, bitmap.height, image.name),
                )
            } finally {
                bitmap.recycle()
            }
        }
    }
}
