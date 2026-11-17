package com.paperlens.app.word

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.ml.Ocr
import com.paperlens.app.ml.OcrScript
import com.paperlens.app.runLogged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Ink at least this light (on white paper, 0..255) is pencil rather than print. */
private const val LIGHT_INK = 150

/**
 * Turns scanned pages into an editable Word document that looks like the page: headings at their
 * size and weight, paragraphs at their place, coloured text, tables (ruled ones with merged cells, and
 * shaded-row ones), and photos, logos and QR codes cut out as pictures where they were.
 */
object PageToWord {

    /** Pages are analysed at this size (long side, px). */
    private const val WORK = 2400

    /** Pages as stored: the unfiltered scan and its rotation (filters whiten faint lines and tint shading). */
    suspend fun convertPages(context: Context, pages: List<com.paperlens.app.data.PageEntity>, script: OcrScript): WordDocument =
        convert(context, pages.map { File(it.originalPath) }, script, pages.map { it.rotation })

    suspend fun convert(
        context: Context,
        pages: List<File>,
        script: OcrScript,
        rotations: List<Int> = emptyList(),
        onPage: (index: Int, count: Int) -> Unit = { _, _ -> },
    ): WordDocument {
        val blocks = ArrayList<WBlock>()
        val media = LinkedHashMap<String, WMedia>()
        var setup: PageSetup? = null
        pages.forEachIndexed { index, file ->
            onPage(index, pages.size)
            val bitmap = withContext(Dispatchers.IO) {
                BitmapUtils.decode(file, WORK).let { b -> rotations.getOrNull(index)?.takeIf { it % 360 != 0 }?.let { BitmapUtils.rotate(b, it) } ?: b }
            }
            val text = Ocr.recognizeText(context, bitmap, script)
            val codes = runLogged("PageToWord", "Finding barcodes and QR codes") {
                val scanner = BarcodeScanning.getClient()
                try {
                    scanner.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { it.boundingBox?.let(::RectF) }
                } finally {
                    scanner.close()
                }
            }.getOrDefault(emptyList())
            val page = withContext(Dispatchers.Default) { PageBuilder(bitmap, text, codes, "p${index + 1}_", setup).build() }
            if (index > 0) blocks += WPageBreak
            blocks += page.blocks
            media += page.media
            if (setup == null) setup = page.setup
        }
        return WordDocument(blocks.ifEmpty { listOf(WParagraph()) }, setup ?: PageSetup(), media)
    }
}

/** The converted content of one page. */
internal class PageResult(val blocks: List<WBlock>, val media: Map<String, WMedia>, val setup: PageSetup, val debug: PageDebug)

/** What the analysis found, for drawing a debug overlay. */
internal class PageDebug(
    val words: List<OcrWord>,
    val gridTables: List<RectF>,
    val gridCells: List<RectF>,
    val bandTables: List<RectF>,
    val pictures: List<RectF>,
    val rules: List<Rule>,
    val gridRules: List<Rule>,
)

internal class PageBuilder(
    internal val bitmap: Bitmap,
    private val text: Text,
    internal val codes: List<RectF>,
    internal val mediaPrefix: String,
    /** Page setup of the first page, reused for the following pages. */
    private val fixedSetup: PageSetup?,
) {
    internal val a = PageAnalysis(bitmap)
    internal val w = bitmap.width
    internal val h = bitmap.height
    internal val media = LinkedHashMap<String, WMedia>()

    // Page geometry: the photo of the page maps onto an A4 sheet (landscape for wide pages).
    private val landscape = w > h * 1.08f
    private val pageW = if (landscape) PageSetup.A4_HEIGHT else PageSetup.A4_WIDTH
    private val pageH = if (landscape) PageSetup.A4_WIDTH else PageSetup.A4_HEIGHT
    /** Twips per pixel: the page's width maps onto the sheet (scaled down for close-up photos, see build). */
    internal var tw = pageW.toFloat() / w
    internal val emu: Float get() = tw * 635f // EMU per pixel
    internal lateinit var setup: PageSetup
    /** Typical text size on the page (px), to tell headings from body text. */
    private var bodyEm = 0f

    internal class Seg(val words: List<OcrWord>) {
        val box = RectF(words[0].box).apply { words.forEach { union(it.box) } }
        val em = PageAnalysis.median(words.map { it.em })
    }

    internal class Row(val segs: List<Seg>) {
        val box = RectF(segs[0].box).apply { segs.forEach { union(it.box) } }
    }

    /** Something placed on the page from top to bottom. */
    internal sealed class Item(val top: Float, val bottom: Float) {
        class Lines(val rows: List<Row>) : Item(rows.first().box.top, rows.last().box.bottom)
        class Table(val table: WTable, val box: RectF) : Item(box.top, box.bottom)
        class Picture(val rect: RectF) : Item(rect.top, rect.bottom)
        class HLine(val x0: Float, val x1: Float, val y: Float, val thickness: Float) : Item(y, y + thickness)
    }

    /**
     * The OCR sometimes reads print and handwriting next to it as one unsure word ("(6)我给妈妈打了电话(after":
     * printed "(6)我", pencil "给妈妈打了电话", printed "(after"). Its characters tell them apart: the print
     * reads surely (0.6–0.9 on the sample page), the handwriting doesn't (0.03–0.45). Split the word into
     * runs of sure and unsure characters (a lone character goes with its neighbours), so only the
     * handwriting can become a picture and the print stays text. Each run needs two letters, otherwise
     * the word is kept whole. Ink colour can't do this: on a photo the print is nearly as grey as pencil.
     */
    private fun splitMixed(el: Text.Element, t: String, box: RectF, line: Int): List<OcrWord> {
        val whole = listOf(OcrWord(t, box, el.confidence, line))
        val symbols = el.symbols
        if (el.confidence >= 0.5f || symbols.size < 4 || symbols.joinToString("") { it.text } != t) return whole
        val sure = symbols.map { it.confidence >= 0.6f }
        // Smooth over single characters that disagree with both neighbours (a misread letter in print).
        val cls = sure.indices.map { i ->
            val l = sure.getOrNull(i - 1)
            val r = sure.getOrNull(i + 1)
            if (l != null && r != null && l == r) l else sure[i]
        }
        val runs = ArrayList<IntRange>()
        var start = 0
        for (i in 1..cls.size) if (i == cls.size || cls[i] != cls[start]) { runs += start until i; start = i }
        if (runs.size < 2 || runs.any { r -> r.count { symbols[it].text.firstOrNull()?.isLetterOrDigit() == true } < 2 }) return whole
        // Only worth it when an unsure run is clearly handwriting; a misread printed word ("de|gree.") stays whole.
        if (runs.none { r -> !cls[r.first] && r.map { symbols[it].confidence }.average() < 0.35 }) return whole
        // Symbol boxes sit up to half a character right of the ink: measure that drift on the first one.
        val drift = ((symbols[0].boundingBox?.left ?: box.left.toInt()) - box.left).coerceAtLeast(0f)
        fun leftOf(i: Int) = if (i >= symbols.size) box.right else ((symbols[i].boundingBox?.left?.toFloat() ?: box.left) - drift).coerceIn(box.left, box.right)
        return runs.map { r ->
            val text = r.joinToString("") { symbols[it].text }
            val conf = r.map { symbols[it].confidence }.average().toFloat()
            // Print reads as text: keep its confidence above the handwriting test; handwriting keeps the doubt.
            OcrWord(text, RectF(leftOf(r.first), box.top, leftOf(r.last + 1), box.bottom), if (cls[r.first]) max(conf, 0.6f) else conf, line)
        }
    }

    /** The page's words with their size, weight, colour and boldness measured (also used by To Excel). */
    internal fun readWords(): List<OcrWord> {
        val words = ArrayList<OcrWord>()
        text.textBlocks.forEachIndexed { bi, block ->
            block.lines.forEachIndexed { li, line ->
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    // "|" is what the OCR makes of page edges and bullet bars.
                    val t = el.text.trim().trimStart('|', '¦').trim()
                    if (t.isEmpty()) continue
                    words += splitMixed(el, t, RectF(b), bi * 1000 + li)
                }
            }
        }
        words.forEach { a.styleWord(it) }
        // Font size per OCR line, from the height of its ink and which letters reach up or down.
        for ((_, line) in words.groupBy { it.line }) {
            val box = RectF(line[0].box).apply { line.forEach { union(it.box) } }
            val lineText = line.joinToString("") { it.text }
            val em = a.emFromCore(box, lineText)
                ?: a.inkSpan(box)?.let { PageAnalysis.emFromBox(lineText, it.second - it.first) }
                ?: (PageAnalysis.emFromBox(lineText, box.height()) * 0.88f)
            for (wd in line) {
                wd.em = em
                wd.weight = if (em > 0) wd.stroke / em else 0f
            }
        }
        snapSizes(words)
        bodyEm = PageAnalysis.median(words.flatMap { wd -> List(wd.text.length.coerceAtLeast(1)) { wd.em } })
        // A close-up of part of a page would come out with huge text on a full sheet; scale it so the
        // usual text is a normal size (the layout keeps its proportions).
        val body = words.flatMap { wd -> List(wd.text.length.coerceAtLeast(1)) { wd.em } }.sorted()
        if (body.isNotEmpty()) {
            val bodyPt = body[body.size / 2] * tw / 20f
            if (bodyPt > 14f) tw *= 12f / bodyPt
        }
        decideBold(words)
        return words
    }

    fun build(): PageResult {
        val words = readWords()
        val textHeight = PageAnalysis.median(words.map { it.box.height() }).takeIf { it > 0 } ?: (h / 80f)

        val (hRules, vRules) = a.rules(textHeight)
        val grids = a.gridTables(hRules, vRules, textHeight).filter { g -> g.plausible(words, textHeight) }.map { a.splitByText(it, words) }
        val gridBoxes = grids.map { it.box }
        val bands = a.bandTables(gridBoxes, textHeight).filter { it.plausible(words, gridBoxes) }
        val bandBoxes = bands.map { it.box }
        // Handwriting (notes in the margin, answers filled in) reads badly: the OCR is unsure of it and the
        // pencil is light. Keep it as pictures at its place, like the printed page around it.
        val handwriting = words.filter { wd ->
            wd.text.any { it.isLetterOrDigit() } && (bodyEm <= 0f || wd.em <= bodyEm * 1.6f) && (wd.confidence < 0.35f || (wd.confidence < 0.5f && wd.inkLuma >= LIGHT_INK))
        }
        // They stay in the text flow (inline pictures in their line), so answers written on a blank line
        // sit in the sentence like on the page; the picture finder must not grab them.
        handwriting.forEach { it.handwritten = true }
        val pictures = a.pictures(words - handwriting.toSet(), hRules + vRules, bandBoxes + handwriting.map { RectF(it.box).apply { inset(-it.box.height() * 0.2f, -it.box.height() * 0.5f) } }, codes, textHeight)
            .filter { p -> bandBoxes.none { RectF.intersects(it, p) } }

        // Words inside a picture belong to the picture (labels on a product photo, QR noise).
        val inPicture = { wd: OcrWord -> pictures.any { it.contains(wd.box.centerX(), wd.box.centerY()) } }
        val free = words.filterNot(inPicture).toMutableList()

        setup = fixedSetup ?: pageSetup(words, grids, bands, pictures)

        val items = ArrayList<Item>()
        // Tables drawn with lines.
        for (g in grids) {
            val box = g.box
            val inside = free.filter { box.contains(it.box.centerX(), it.box.centerY()) }
            free.removeAll(inside.toSet())
            val pics = pictures.filter { box.contains(it.centerX(), it.centerY()) }
            items += Item.Table(gridTable(g, inside, pics), box)
        }
        // Tables of shaded rows.
        for (b in bands) {
            val inside = free.filter { b.box.contains(it.box.centerX(), it.box.centerY()) }
            free.removeAll(inside.toSet())
            items += Item.Table(bandTable(b, inside), b.box)
        }
        // Pictures outside tables.
        val tableBoxes = gridBoxes + bandBoxes
        for (p in pictures) if (tableBoxes.none { it.contains(p.centerX(), p.centerY()) }) items += Item.Picture(p)
        // Lines drawn across the page outside tables (rules under headings…).
        for (r in hRules) {
            if (r.end - r.start < w * 0.25f) continue
            val y = r.at((r.start + r.end) / 2)
            if (tableBoxes.any { y >= it.top - textHeight && y <= it.bottom + textHeight && r.start < it.right && r.end > it.left }) continue
            if (pictures.any { it.contains((r.start + r.end) / 2, y) }) continue
            items += Item.HLine(r.start, r.end, y, r.thickness)
        }
        // Text.
        val rows = rows(free)
        items += paragraphsOf(rows, items.filter { it !is Item.Lines })

        val blocks = layout(items.sortedBy { it.top })
        return PageResult(
            blocks, media, setup,
            PageDebug(
                words, gridBoxes, grids.flatMap { g -> g.cells.map { g.cellRect(it) } }, bandBoxes, pictures,
                hRules + vRules,
                grids.flatMap { it.rows + it.cols },
            ),
        )
    }

    // ---- page setup ------------------------------------------------------------------------------

    private fun pageSetup(words: List<OcrWord>, grids: List<GridTable>, bands: List<BandTable>, pictures: List<RectF>): PageSetup {
        val boxes = words.map { it.box } + grids.map { it.box } + bands.map { it.box } + pictures
        if (boxes.isEmpty()) return PageSetup(pageW, pageH)
        val left = boxes.minOf { it.left }
        val right = boxes.maxOf { it.right }
        val top = boxes.minOf { it.top }
        return PageSetup(
            width = pageW,
            height = pageH,
            marginLeft = (left * tw).roundToInt().coerceIn(0, 1440),
            marginRight = (pageW - right * tw).roundToInt().coerceIn(0, pageW / 2),
            marginTop = (top * tw * 0.6f).roundToInt().coerceIn(0, 1000),
            marginBottom = 400,
        )
    }

    // ---- words -> rows -> paragraphs ---------------------------------------------------------------

    /**
     * Documents use a few text sizes; measured sizes scatter around them. Lines whose sizes are within
     * ~18 % of each other are grouped and take the group's (text-weighted) median, so a table's text
     * comes out at one size.
     */
    private fun snapSizes(words: List<OcrWord>) {
        val lines = words.groupBy { it.line }.values.filter { it.first().em > 0 }.sortedBy { it.first().em }
        var group = ArrayList<List<OcrWord>>()
        fun flush() {
            if (group.isEmpty()) return
            val weighted = group.flatMap { line -> List(line.sumOf { it.text.length }.coerceAtLeast(1)) { line.first().em } }.sorted()
            val em = weighted[weighted.size / 2]
            for (line in group) for (wd in line) {
                wd.weight = if (em > 0) wd.stroke / em else 0f
                wd.em = em
            }
            group = ArrayList()
        }
        for (line in lines) {
            val first = group.firstOrNull()?.first()?.em
            if (first != null && line.first().em > first * 1.18f) flush()
            group += line
        }
        flush()
    }

    /** Bold from stroke weight: clearly heavier than the page's typical text, or heavy in absolute terms. */
    private fun decideBold(words: List<OcrWord>) {
        val weights = words.filter { it.text.length >= 2 && it.weight > 0 }.map { it.weight }
        val typical = PageAnalysis.median(weights).takeIf { it > 0 } ?: 0.11f
        for (wd in words) {
            wd.bold = wd.weight > 0 && (wd.weight > typical * 1.32f || wd.weight > 0.17f) && wd.em > 6
        }
        // Words of one line usually share a weight: follow the line's majority unless clearly different.
        for ((_, line) in words.groupBy { it.line }) {
            // Words of similar weight in one line are all bold or all regular (no half-bold titles).
            val ws = line.filter { it.text.length >= 2 && it.weight > 0 }.map { it.weight }
            if (ws.size >= 2 && ws.max() <= ws.min() * 1.3f) {
                val m = PageAnalysis.median(ws)
                val bold = (m > typical * 1.32f || m > 0.17f) && line.first().em > 6
                line.forEach { it.bold = bold }
                continue
            }
            if (line.size < 3) continue
            val boldShare = line.count { it.bold }.toFloat() / line.size
            val lineWeight = PageAnalysis.median(line.map { it.weight })
            for (wd in line) {
                if (boldShare <= 0.3f) wd.bold = wd.bold && wd.weight > lineWeight * 1.25f
            }
        }
    }

    /** Words grouped into segments (split at wide gaps) and segments sharing a height into rows. */
    internal fun rows(words: List<OcrWord>): List<Row> {
        val segs = ArrayList<Seg>()
        for ((_, line) in words.groupBy { it.line }) {
            val sorted = line.sortedBy { it.box.left }
            var cur = ArrayList<OcrWord>()
            for (wd in sorted) {
                val prev = cur.lastOrNull()
                if (prev != null && wd.box.left - prev.box.right > max(prev.em, wd.em) * 2.2f) {
                    segs += Seg(cur); cur = ArrayList()
                }
                cur += wd
            }
            if (cur.isNotEmpty()) segs += Seg(cur)
        }
        val rows = ArrayList<MutableList<Seg>>()
        for (s in segs.sortedBy { it.box.centerY() }) {
            val row = rows.lastOrNull { r ->
                val rb = RectF(r[0].box).apply { r.forEach { union(it.box) } }
                val overlap = min(rb.bottom, s.box.bottom) - max(rb.top, s.box.top)
                overlap > 0.5f * min(rb.height(), s.box.height()) && r.none { RectF.intersects(it.box, s.box) }
            }
            if (row != null) row += s else rows += mutableListOf(s)
        }
        return rows.map { Row(it.sortedBy { s -> s.box.left }) }.sortedBy { it.box.top }
    }

    /** Consecutive single-segment rows that read as one paragraph are grouped. */
    private fun paragraphsOf(rows: List<Row>, others: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        var cur = ArrayList<Row>()
        fun flush() { if (cur.isNotEmpty()) { out += Item.Lines(cur); cur = ArrayList() } }
        for (row in rows) {
            val prev = cur.lastOrNull()
            if (prev == null) { cur += row; continue }
            val ps = prev.segs.singleOrNull()
            val rs = row.segs.singleOrNull()
            val joinable = ps != null && rs != null && run {
                val gap = row.box.top - prev.box.bottom
                val sameSize = abs(ps.em - rs.em) <= 0.15f * max(ps.em, rs.em)
                val firstIndent = cur.size == 1 && ps.box.left > rs.box.left && ps.box.left - rs.box.left <= ps.em * 4.5f &&
                    abs(ps.box.right - rs.box.right) <= w * 0.05f
                val sameLeft = abs(ps.box.left - rs.box.left) <= w * 0.02f || firstIndent
                val sameCenter = abs(ps.box.centerX() - rs.box.centerX()) <= w * 0.02f
                val between = others.any { it.top > prev.box.bottom - 2 && it.top < row.box.top }
                gap < ps.box.height() * 0.9f && sameSize && (sameLeft || sameCenter) && !between &&
                    boldShare(ps) > 0.5f == boldShare(rs) > 0.5f
            }
            if (!joinable) flush()
            cur += row
        }
        flush()
        return out
    }

    private fun boldShare(s: Seg) = s.words.count { it.bold }.toFloat() / s.words.size

    // ---- vertical layout ---------------------------------------------------------------------------

    private fun layout(items: List<Item>): List<WBlock> {
        val out = ArrayList<WBlock>()
        var cursor = setup.marginTop.toFloat() // twips from the top of the page, where Word is now
        val left = setup.marginLeft
        val textW = pageW - setup.marginLeft - setup.marginRight
        // Pictures beside text float at their exact spot instead of taking a line of their own.
        val textRows = items.filterIsInstance<Item.Lines>().flatMap { it.rows }
        val floating = items.filterIsInstance<Item.Picture>().filter { p ->
            textRows.any { r ->
                val overlapY = min(r.box.bottom, p.rect.bottom) - max(r.box.top, p.rect.top)
                overlapY > r.box.height() * 0.3f && (r.box.right < p.rect.left || r.box.left > p.rect.right)
            } || items.filterIsInstance<Item.Table>().any { t ->
                min(t.box.bottom, p.rect.bottom) - max(t.box.top, p.rect.top) > 0 && (t.box.right < p.rect.left || t.box.left > p.rect.right)
            }
        }.toSet()
        val pendingFloats = floating.sortedBy { it.top }.toMutableList()

        fun anchorFloats(target: WParagraph, upTo: Float): WParagraph {
            var p = target
            while (pendingFloats.isNotEmpty() && pendingFloats.first().top <= upTo) {
                val f = pendingFloats.removeAt(0)
                val image = pictureImage(f.rect, float = true)
                p = p.copy(runs = listOf(WRun(image = image)) + p.runs)
            }
            return p
        }

        for (item in items) {
            if (item is Item.Picture && item in floating) continue
            val desired = item.top * tw
            when (item) {
                is Item.Lines -> {
                    // Lines beside a floating picture keep their breaks (the text must not run under it).
                    val beside = floating.any { p -> p.rect.top < item.bottom && p.rect.bottom > item.top && p.rect.left > item.rows.maxOf { it.box.right } }
                    val para = paragraph(item.rows, left, textW, keepBreaks = beside)
                    val size = para.firstStyle.size
                    val lineH = lineHeight(para)
                    val capOffset = when (para.lineRule) {
                        LineRule.EXACT -> (lineH - size * 20 * 1.12f) / 2 + size * 20 * 0.19f
                        else -> size * 20 * 0.19f
                    }
                    val before = (desired - cursor - capOffset).roundToInt().coerceAtLeast(0)
                    var p = para.copy(spaceBefore = before)
                    p = anchorFloats(p, item.bottom)
                    out += p
                    cursor += before + lineH * item.rows.size
                }
                is Item.Table -> {
                    val gap = (desired - cursor).roundToInt()
                    if (gap > 40) {
                        out += spacer(gap)
                        cursor += gap
                    }
                    // Floating pictures need a paragraph to hang from; put them before the table.
                    if (pendingFloats.isNotEmpty() && pendingFloats.first().top <= item.bottom) {
                        out += anchorFloats(spacer(20), item.bottom)
                        cursor += 20
                    }
                    out += item.table
                    cursor += item.table.rows.sumOf { it.height ?: 300 }
                }
                is Item.Picture -> {
                    val image = pictureImage(item.rect, float = false)
                    val heightTw = image.heightEmu / 635f
                    val before = (desired - cursor).roundToInt().coerceAtLeast(0)
                    val indent = ((item.rect.left * tw) - left).roundToInt().coerceIn(0, textW)
                    var p = WParagraph(runs = listOf(WRun(image = image)), spaceBefore = before, indentLeft = indent)
                    p = anchorFloats(p, item.bottom)
                    out += p
                    cursor += before + heightTw
                }
                is Item.HLine -> {
                    val before = (desired - cursor).roundToInt().coerceAtLeast(0)
                    val thicknessPt = item.thickness * tw / 20
                    out += WParagraph(
                        runs = listOf(WRun("", RunStyle(size = 1f))),
                        spaceBefore = before,
                        line = 20, lineRule = LineRule.EXACT,
                        indentLeft = ((item.x0 * tw) - left).roundToInt().coerceIn(0, textW),
                        indentRight = ((pageW - setup.marginRight) - item.x1 * tw).roundToInt().coerceIn(0, textW),
                        borderBottom = WBorder((thicknessPt * 8).roundToInt().coerceIn(4, 24), null),
                    )
                    cursor += before + 20 + thicknessPt * 20
                }
            }
        }
        if (pendingFloats.isNotEmpty()) out += anchorFloats(WParagraph(runs = listOf(WRun("", RunStyle(size = 1f)))), Float.MAX_VALUE)
        return out
    }

    private fun spacer(height: Int) = WParagraph(
        runs = listOf(WRun("", RunStyle(size = 1f))),
        line = height.coerceAtLeast(20), lineRule = LineRule.EXACT,
    )

    private fun lineHeight(p: WParagraph): Float = when (p.lineRule) {
        LineRule.EXACT -> p.line!!.toFloat()
        else -> p.runs.filter { it.image == null }.maxOfOrNull { it.style.size }?.let { it * 20 * 1.15f } ?: 276f
    }

    /** One paragraph from rows: a single column of lines, or one row with tab stops between its parts. */
    private fun paragraph(rows: List<Row>, left: Int, textW: Int, keepBreaks: Boolean = false): WParagraph {
        if (rows.size == 1 && rows[0].segs.size > 1) return tabbedRow(rows[0], left, textW)
        val segs = rows.map { it.segs[0] }
        val blockLeft = segs.minOf { it.box.left }
        val blockRight = segs.maxOf { it.box.right }
        val runs = ArrayList<WRun>()
        segs.forEachIndexed { i, s ->
            if (i > 0) {
                val prev = segs[i - 1]
                // Headings keep their line breaks; body text that ran to the edge is re-wrapped by Word.
                val heading = bodyEm > 0 && prev.em >= bodyEm * 1.4f
                val wrapped = !keepBreaks && !heading && prev.box.right >= blockRight - (blockRight - blockLeft) * 0.08f
                val last = prev.words.last().text.lastOrNull() ?: ' '
                val first = s.words.first().text.firstOrNull() ?: ' '
                val joiner = when {
                    !wrapped -> "\n"
                    isCjk(last) || isCjk(first) || last == '-' -> ""
                    else -> " "
                }
                if (joiner.isNotEmpty()) runs += WRun(joiner, styleOf(prev.words.last(), prev))
            }
            runs += runsOf(s)
        }
        val contentL = setup.marginLeft / tw
        val contentR = (pageW - setup.marginRight) / tw
        val center = (contentL + contentR) / 2
        val centered = segs.all { abs(it.box.centerX() - center) < w * 0.025f } &&
            (segs.size > 1 && segs.map { it.box.left }.let { it.max() - it.min() } > w * 0.02f || segs[0].box.left - contentL > w * 0.12f)
        val rightAligned = !centered && segs.all { contentR - it.box.right < w * 0.02f } && blockLeft - contentL > w * 0.25f
        val align = when {
            centered -> WAlign.CENTER
            rightAligned -> WAlign.RIGHT
            else -> WAlign.LEFT
        }
        var line: Int? = null
        var rule = LineRule.AUTO
        if (segs.size > 1) {
            val pitch = (segs.last().box.top - segs.first().box.top) / (segs.size - 1) * tw
            val size = runs.maxOf { it.style.size }
            line = max(pitch, size * 20 * 1.05f).roundToInt()
            rule = LineRule.EXACT
        }
        val restLeft = if (segs.size > 1) segs.drop(1).minOf { it.box.left } else blockLeft
        return WParagraph(
            runs = merge(runs),
            align = align,
            indentLeft = if (align == WAlign.LEFT) ((restLeft * tw) - left).roundToInt().coerceIn(0, textW) else 0,
            indentFirst = if (align == WAlign.LEFT && segs.size > 1) ((segs[0].box.left - restLeft) * tw).roundToInt().coerceIn(0, textW) else 0,
            line = line,
            lineRule = rule,
        )
    }

    /** A row of separate parts ("SPECIFICATION ……… MODEL: V2"): one line with tab stops. */
    private fun tabbedRow(row: Row, left: Int, textW: Int): WParagraph {
        val segs = row.segs
        val contentR = (pageW - setup.marginRight) / tw
        val runs = ArrayList<WRun>()
        val tabs = ArrayList<WTab>()
        segs.forEachIndexed { i, s ->
            if (i > 0) {
                val rightAligned = i == segs.lastIndex && contentR - s.box.right < w * 0.03f
                val pos = if (rightAligned) textW else ((s.box.left * tw) - left).roundToInt().coerceIn(0, textW)
                tabs += WTab(pos, if (rightAligned) TabKind.RIGHT else TabKind.LEFT)
                runs += WRun("\t", styleOf(s.words[0], s))
            }
            runs += runsOf(s)
        }
        return WParagraph(
            runs = merge(runs),
            indentLeft = ((segs[0].box.left * tw) - left).roundToInt().coerceIn(0, textW),
            // Word measures tab stops from the margin, like the indent.
            tabs = tabs,
        )
    }

    /** Words of a segment as runs, with spaces where the words have a gap between them. */
    internal fun runsOf(s: Seg): List<WRun> {
        val out = ArrayList<WRun>()
        s.words.forEachIndexed { i, wd ->
            val style = styleOf(wd, s)
            if (i > 0) {
                val prev = s.words[i - 1]
                val gap = wd.box.left - prev.box.right
                val bothCjk = isCjk(prev.text.last()) && isCjk(wd.text.first())
                if (!bothCjk || gap > s.em * 0.3f) out += WRun(" ", if (prev.bold == wd.bold) style else styleOf(prev, s))
            }
            out += if (wd.handwritten) WRun(image = inlinePicture(wd.box)) else WRun(wd.text, style)
        }
        return out
    }

    /** A handwritten word as a picture the height of its line. */
    private fun inlinePicture(box: RectF): WImage = pictureImage(RectF(box.left, box.top, box.right, box.bottom), float = false)

    private fun isCjk(c: Char) = c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0x3040..0x30FF || c.code in 0xFF00..0xFFEF

    internal fun styleOf(wd: OcrWord, s: Seg): RunStyle = RunStyle(bold = wd.bold, size = ptOf(s.em), color = wd.color)

    internal fun ptOf(emPx: Float): Float = ((emPx * tw / 20f) * 2).roundToInt().coerceIn(8, 400) / 2f

    internal fun merge(runs: List<WRun>): List<WRun> {
        val out = ArrayList<WRun>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && last.image == null && r.image == null && last.style == r.style) {
                out[out.size - 1] = last.copy(text = last.text + r.text)
            } else {
                out += r
            }
        }
        return out
    }
}
