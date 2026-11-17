package com.paperlens.app.word

import com.paperlens.app.word.DocxXml.A
import com.paperlens.app.word.DocxXml.HIGHLIGHTS
import com.paperlens.app.word.DocxXml.Numbering
import com.paperlens.app.word.DocxXml.PPr
import com.paperlens.app.word.DocxXml.R
import com.paperlens.app.word.DocxXml.RPr
import com.paperlens.app.word.DocxXml.StyleSheet
import com.paperlens.app.word.DocxXml.TblPr
import com.paperlens.app.word.DocxXml.W
import com.paperlens.app.word.DocxXml.WP
import com.paperlens.app.word.DocxXml.color
import com.paperlens.app.word.DocxXml.onOff
import com.paperlens.app.word.DocxXml.parser
import com.paperlens.app.word.DocxXml.readBorderSet
import com.paperlens.app.word.DocxXml.readPPr
import com.paperlens.app.word.DocxXml.readRPr
import com.paperlens.app.word.DocxXml.readTblPr
import com.paperlens.app.word.DocxXml.skip
import org.xmlpull.v1.XmlPullParser

/** Reads the main document part (body, tables, pictures, text boxes) into blocks. */
internal class DocxBodyParser(
    val rels: Map<String, Pair<String, String>>,
    val entries: Map<String, ByteArray>,
    val styles: StyleSheet,
    val numbering: Numbering,
) {
    var page = PageSetup()
    val media = HashMap<String, WMedia>()

    fun parse(bytes: ByteArray): List<WBlock> {
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "body") return blocks(p)
        }
        return emptyList()
    }

    /** Children of body / cell / sdtContent / txbxContent up to the end of the current element. */
    fun blocks(p: XmlPullParser): List<WBlock> {
        val out = ArrayList<WBlock>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "p" -> out += paragraph(p)
                "tbl" -> out += table(p)
                "sdt" -> out += sdt(p)
                "customXml" -> out += blocks(p)
                "sectPr" -> if (p.depth == 3) readSection(p) else skip(p)
                else -> skip(p)
            }
        }
        return out
    }

    private fun sdt(p: XmlPullParser): List<WBlock> {
        val out = ArrayList<WBlock>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            if (p.name == "sdtContent") out += blocks(p) else skip(p)
        }
        return out
    }

    private fun readSection(p: XmlPullParser) {
        var pg = page
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "pgSz" -> {
                    val w = p.getAttributeValue(W, "w")?.toIntOrNull()
                    val h = p.getAttributeValue(W, "h")?.toIntOrNull()
                    if (w != null && h != null && w > 1000 && h > 1000) pg = pg.copy(width = w, height = h)
                }
                "pgMar" -> {
                    fun a(n: String) = p.getAttributeValue(W, n)?.toIntOrNull()?.let { kotlin.math.abs(it) }
                    pg = pg.copy(
                        marginTop = a("top") ?: pg.marginTop,
                        marginBottom = a("bottom") ?: pg.marginBottom,
                        marginLeft = a("left") ?: pg.marginLeft,
                        marginRight = a("right") ?: pg.marginRight,
                    )
                }
            }
            skip(p)
        }
        page = pg
    }

    /** A paragraph; a page break inside it splits it into paragraph, [WPageBreak], paragraph. */
    private fun paragraph(p: XmlPullParser): List<WBlock> {
        var direct = PPr()
        var markRPr = RPr()
        val runs = ArrayList<Pair<WRun, Boolean>>() // run, followed by page break
        val extra = ArrayList<WBlock>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            if (p.name == "pPr") {
                // The paragraph mark's rPr sits inside pPr; read both.
                val d2 = p.depth
                var pp = PPr()
                while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "rPr" -> markRPr = readRPr(p)
                        "sectPr" -> skip(p)
                        else -> {
                            // Re-read single pPr children through readPPr by wrapping: simplest is to
                            // handle the common ones here.
                            pp = pPrChild(p, pp)
                        }
                    }
                }
                direct = pp
            } else {
                content(p, direct, runs, extra)
            }
        }
        return build(direct, markRPr, runs, extra)
    }

    private fun pPrChild(p: XmlPullParser, pp: PPr): PPr {
        // Reuse readPPr's logic for one child by reading it as a one-element pPr.
        val name = p.name
        return when (name) {
            "pStyle", "jc", "spacing", "ind", "tabs", "shd", "numPr", "pageBreakBefore", "pBdr" -> {
                val single = readPPrSingle(p)
                single.over(pp).let { merged ->
                    // tabs from one child replace, not add
                    if (name == "tabs") merged.copy(tabs = single.tabs) else merged
                }
            }
            else -> { skip(p); pp }
        }
    }

    /** Reads the current pPr child element into a PPr. */
    private fun readPPrSingle(p: XmlPullParser): PPr {
        // readPPr expects to be positioned on a container; emulate by parsing this element's
        // attributes / children directly.
        var r = PPr()
        when (p.name) {
            "pStyle" -> { r = r.copy(styleId = p.getAttributeValue(W, "val")); skip(p) }
            "jc" -> {
                r = r.copy(
                    align = when (p.getAttributeValue(W, "val")) {
                        "center" -> WAlign.CENTER
                        "right", "end" -> WAlign.RIGHT
                        "both", "distribute" -> WAlign.JUSTIFY
                        else -> WAlign.LEFT
                    },
                )
                skip(p)
            }
            "spacing" -> {
                p.getAttributeValue(W, "before")?.toIntOrNull()?.let { r = r.copy(before = it) }
                p.getAttributeValue(W, "after")?.toIntOrNull()?.let { r = r.copy(after = it) }
                p.getAttributeValue(W, "line")?.toIntOrNull()?.let { line ->
                    val rule = when (p.getAttributeValue(W, "lineRule")) {
                        "exact" -> LineRule.EXACT
                        "atLeast" -> LineRule.AT_LEAST
                        else -> LineRule.AUTO
                    }
                    r = r.copy(line = line, lineRule = rule)
                }
                skip(p)
            }
            "ind" -> {
                (p.getAttributeValue(W, "left") ?: p.getAttributeValue(W, "start"))?.toIntOrNull()?.let { r = r.copy(left = it) }
                (p.getAttributeValue(W, "right") ?: p.getAttributeValue(W, "end"))?.toIntOrNull()?.let { r = r.copy(right = it) }
                p.getAttributeValue(W, "firstLine")?.toIntOrNull()?.let { r = r.copy(first = it) }
                p.getAttributeValue(W, "hanging")?.toIntOrNull()?.let { r = r.copy(first = -it) }
                skip(p)
            }
            "shd" -> { color(p.getAttributeValue(W, "fill"))?.let { r = r.copy(shading = it) }; skip(p) }
            "pageBreakBefore" -> { r = r.copy(pageBreakBefore = onOff(p)); skip(p) }
            "pBdr" -> readBorderSet(p)["bottom"]?.let { r = r.copy(borderBottom = it) }
            "tabs" -> {
                val tabs = ArrayList<WTab>()
                val d2 = p.depth
                while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "tab") {
                        val pos = p.getAttributeValue(W, "pos")?.toIntOrNull() ?: continue
                        val kind = when (p.getAttributeValue(W, "val")) {
                            "center" -> TabKind.CENTER
                            "right", "end" -> TabKind.RIGHT
                            "decimal" -> TabKind.DECIMAL
                            "clear" -> null
                            else -> TabKind.LEFT
                        }
                        if (kind != null) tabs += WTab(pos, kind)
                    }
                }
                r = r.copy(tabs = tabs)
            }
            "numPr" -> {
                val d2 = p.depth
                while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "ilvl" -> r = r.copy(ilvl = p.getAttributeValue(W, "val")?.toIntOrNull())
                        "numId" -> r = r.copy(numId = p.getAttributeValue(W, "val")?.toIntOrNull())
                    }
                }
            }
            else -> skip(p)
        }
        return r
    }

    /** Runs and run containers inside a paragraph (hyperlinks, insertions, fields, smart tags…). */
    private fun content(p: XmlPullParser, direct: PPr, runs: MutableList<Pair<WRun, Boolean>>, extra: MutableList<WBlock>) {
        when (p.name) {
            "r" -> run(p, direct, runs, extra)
            "hyperlink", "ins", "smartTag", "fldSimple", "customXml", "moveTo", "sdtContent", "sdt", "dir", "bdo" -> {
                val depth = p.depth
                while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                    if (p.eventType == XmlPullParser.START_TAG) content(p, direct, runs, extra)
                }
            }
            else -> skip(p) // del, moveFrom, bookmarks, proofErr, commentRange…
        }
    }

    private fun run(p: XmlPullParser, direct: PPr, runs: MutableList<Pair<WRun, Boolean>>, extra: MutableList<WBlock>) {
        var rPr = RPr()
        var rStyle: String? = null
        val text = StringBuilder()
        val depth = p.depth
        fun flush(pageBreak: Boolean = false) {
            val style = resolveRun(direct, rStyle, rPr)
            if (style.second) { text.setLength(0); return } // hidden
            if (text.isNotEmpty() || pageBreak) runs += WRun(text.toString(), style.first) to pageBreak
            text.setLength(0)
        }
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "rPr" -> {
                    val d2 = p.depth
                    var r = RPr()
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType != XmlPullParser.START_TAG) continue
                        if (p.name == "rStyle") { rStyle = p.getAttributeValue(W, "val"); skip(p) } else {
                            r = rPrChild(p, r)
                        }
                    }
                    rPr = r
                }
                "t" -> text.append(p.nextText())
                "tab", "ptab" -> { text.append('\t'); skip(p) }
                "br", "cr" -> {
                    val type = p.getAttributeValue(W, "type")
                    if (type == "page") flush(pageBreak = true) else text.append('\n')
                    skip(p)
                }
                "noBreakHyphen" -> { text.append('\u2011'); skip(p) }
                "softHyphen" -> skip(p)
                "sym" -> {
                    val ch = p.getAttributeValue(W, "char")?.toIntOrNull(16)
                    text.append(if (ch != null && ch in 0xF000..0xF0FF) "•" else ch?.toChar()?.toString() ?: "")
                    skip(p)
                }
                "drawing" -> {
                    flush()
                    drawing(p, extra)?.let { runs += WRun(image = it) to false }
                }
                "AlternateContent" -> {
                    flush()
                    alternate(p, runs, extra)
                }
                "pict", "object" -> {
                    flush()
                    vml(p, extra)?.let { runs += WRun(image = it) to false }
                }
                else -> skip(p) // instrText, fldChar, footnoteReference, lastRenderedPageBreak…
            }
        }
        flush()
    }

    private fun rPrChild(p: XmlPullParser, r: RPr): RPr {
        var out = r
        when (p.name) {
            "b" -> out = out.copy(bold = onOff(p))
            "i" -> out = out.copy(italic = onOff(p))
            "u" -> out = out.copy(underline = p.getAttributeValue(W, "val").let { it != "none" && it != "0" })
            "strike", "dstrike" -> out = out.copy(strike = onOff(p))
            "sz" -> p.getAttributeValue(W, "val")?.toFloatOrNull()?.let { out = out.copy(size = it / 2f) }
            "color" -> {
                val v = p.getAttributeValue(W, "val")
                out = if (v == "auto") out.copy(colorAuto = true, color = null) else out.copy(color = color(v))
            }
            "highlight" -> out = out.copy(highlight = HIGHLIGHTS[p.getAttributeValue(W, "val")])
            "shd" -> color(p.getAttributeValue(W, "fill"))?.let { out = out.copy(highlight = it) }
            "rFonts" -> (p.getAttributeValue(W, "ascii") ?: p.getAttributeValue(W, "hAnsi"))?.let { out = out.copy(font = it) }
            "vanish" -> out = out.copy(hidden = onOff(p))
            "caps" -> out = out.copy(caps = onOff(p))
        }
        skip(p)
        return out
    }

    /** Final style of a run (paragraph style, character style, direct formatting) and whether it is hidden. */
    private fun resolveRun(direct: PPr, rStyle: String?, rPr: RPr): Pair<RunStyle, Boolean> {
        val (_, pr) = styles.paragraph(direct.styleId)
        val r = rPr.over(styles.character(rStyle).over(pr))
        return r.toStyle() to (r.hidden == true)
    }

    private fun alternate(p: XmlPullParser, runs: MutableList<Pair<WRun, Boolean>>, extra: MutableList<WBlock>) {
        // Prefer the first Choice that has a DrawingML drawing; the Fallback is usually VML.
        val depth = p.depth
        var done = false
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            if (!done && (p.name == "Choice" || p.name == "Fallback")) {
                val d2 = p.depth
                while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "drawing" -> drawing(p, extra)?.let { runs += WRun(image = it) to false }
                        "pict" -> vml(p, extra)?.let { runs += WRun(image = it) to false }
                        else -> skip(p)
                    }
                }
                done = true
            } else {
                skip(p)
            }
        }
    }

    /** A DrawingML picture (inline or anchored); text boxes add their paragraphs to [extra]. */
    private fun drawing(p: XmlPullParser, extra: MutableList<WBlock>): WImage? {
        var cx = 0L
        var cy = 0L
        var anchored = false
        var behind = false
        var posX = 0L
        var posY = 0L
        var relH = "column"
        var relV = "paragraph"
        var embed: String? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "anchor" -> {
                    anchored = true
                    behind = p.getAttributeValue(null, "behindDoc") == "1"
                }
                "extent" -> if (p.namespace == WP) {
                    cx = p.getAttributeValue(null, "cx")?.toLongOrNull() ?: 0
                    cy = p.getAttributeValue(null, "cy")?.toLongOrNull() ?: 0
                }
                "positionH" -> {
                    relH = p.getAttributeValue(null, "relativeFrom") ?: relH
                    posX = posOffset(p)
                }
                "positionV" -> {
                    relV = p.getAttributeValue(null, "relativeFrom") ?: relV
                    posY = posOffset(p)
                }
                "blip" -> if (p.namespace == A) embed = p.getAttributeValue(R, "embed") ?: p.getAttributeValue(R, "link")
                "txbxContent" -> extra += blocks(p)
            }
        }
        val key = embed?.let { loadMedia(it) } ?: return null
        val float = if (anchored) WFloat(posX, posY, relH, relV, behind) else null
        return WImage(key, cx, cy, float)
    }

    private fun posOffset(p: XmlPullParser): Long {
        var v = 0L
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "posOffset") v = p.nextText().trim().toLongOrNull() ?: 0
        }
        return v
    }

    /** Old-style VML picture: <v:imagedata r:id> with a CSS width/height in the shape style. */
    private fun vml(p: XmlPullParser, extra: MutableList<WBlock>): WImage? {
        var id: String? = null
        var w = 0.0
        var h = 0.0
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "shape", "rect" -> p.getAttributeValue(null, "style")?.let { style ->
                    fun dim(n: String): Double? = Regex("$n:\\s*([0-9.]+)(pt|in|px|cm|mm)?").find(style)?.let { m ->
                        val v = m.groupValues[1].toDoubleOrNull() ?: return@let null
                        when (m.groupValues[2]) {
                            "in" -> v * 72
                            "cm" -> v * 72 / 2.54
                            "mm" -> v * 72 / 25.4
                            "px" -> v * 0.75
                            else -> v
                        }
                    }
                    w = dim("width") ?: w
                    h = dim("height") ?: h
                }
                "imagedata" -> id = p.getAttributeValue(R, "id")
                "txbxContent" -> extra += blocks(p)
            }
        }
        val key = id?.let { loadMedia(it) } ?: return null
        return WImage(key, (w * 12700).toLong(), (h * 12700).toLong())
    }

    private fun loadMedia(relId: String): String? {
        if (media.containsKey(relId)) return relId
        val target = rels[relId]?.second ?: return null
        val bytes = entries[target] ?: return null
        media[relId] = WMedia(bytes, target.substringAfterLast('.', "png").lowercase())
        return relId
    }

    private fun build(direct: PPr, markRPr: RPr, runs: List<Pair<WRun, Boolean>>, extra: List<WBlock>): List<WBlock> {
        val (stylePPr, styleRPr) = styles.paragraph(direct.styleId)
        val pp = direct.over(stylePPr)
        var list: WList? = null
        var left = pp.left ?: 0
        var first = pp.first ?: 0
        val numId = pp.numId
        if (numId != null && numId != 0) {
            val level = (pp.ilvl ?: 0).coerceIn(0, 8)
            numbering.label(numId, level)?.let { (label, numbered) ->
                if (label.isNotEmpty()) list = WList(numbered, level, label, numId)
                val (l, hanging) = numbering.indent(numId, level)
                if (direct.left == null && l != null) left = l
                if (direct.first == null && hanging != null) first = -hanging
            }
        }
        val markStyle = markRPr.over(styleRPr).toStyle()
        fun para(rs: List<WRun>) = WParagraph(
            runs = merge(rs).ifEmpty { listOf(WRun("", markStyle)) },
            align = pp.align ?: WAlign.LEFT,
            spaceBefore = pp.before ?: 0,
            spaceAfter = pp.after ?: 0,
            line = pp.line,
            lineRule = pp.lineRule ?: LineRule.AUTO,
            indentLeft = left,
            indentRight = pp.right ?: 0,
            indentFirst = first,
            tabs = pp.tabs.orEmpty(),
            shading = pp.shading,
            borderBottom = pp.borderBottom,
            list = list,
            styleId = direct.styleId,
        )
        val out = ArrayList<WBlock>()
        if (pp.pageBreakBefore == true) out += WPageBreak
        var current = ArrayList<WRun>()
        for ((run, breakAfter) in runs) {
            if (run.text.isNotEmpty() || run.image != null) current += run
            if (breakAfter) {
                if (current.isNotEmpty()) out += para(current)
                out += WPageBreak
                current = ArrayList()
            }
        }
        if (current.isNotEmpty() || out.isEmpty() || out.last() !is WPageBreak) out += para(current)
        out += extra
        return out
    }

    /** Joins neighbouring runs with the same style (Word often splits them for spell-check marks). */
    private fun merge(runs: List<WRun>): List<WRun> {
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

    private fun table(p: XmlPullParser): WTable {
        var tblPr = TblPr(null, null, null, null, null)
        val grid = ArrayList<Int>()
        val rows = ArrayList<WRow>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "tblPr" -> tblPr = readTblPr(p)
                "tblGrid" -> {
                    val d2 = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType == XmlPullParser.START_TAG && p.name == "gridCol") {
                            grid += p.getAttributeValue(W, "w")?.toIntOrNull() ?: 0
                        }
                    }
                }
                "tr" -> rows += row(p)
                "sdt" -> {
                    // Rows wrapped in a content control.
                    val d2 = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType == XmlPullParser.START_TAG && p.name == "tr") rows += row(p)
                    }
                }
                else -> skip(p)
            }
        }
        val columns = rows.maxOfOrNull { r -> r.cells.sumOf { it.span.coerceAtLeast(1) } } ?: 1
        val widths = if (grid.size >= columns && grid.all { it > 0 }) grid else {
            // No usable grid: equal columns over the text width.
            val w = page.textWidth / columns.coerceAtLeast(1)
            List(columns) { i -> grid.getOrNull(i)?.takeIf { it > 0 } ?: w }
        }
        val borders = tblPr.borders ?: styles.tableBorders(tblPr.styleId) ?: WBorders.NONE
        return WTable(
            grid = widths,
            rows = rows.ifEmpty { listOf(WRow(listOf(WCell()))) },
            indent = tblPr.indent ?: 0,
            borders = borders,
            align = tblPr.align ?: WAlign.LEFT,
            cellMarginH = tblPr.cellMargin ?: 108,
        )
    }

    private fun row(p: XmlPullParser): WRow {
        val cells = ArrayList<WCell>()
        var height: Int? = null
        var exact = false
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "trPr" -> {
                    val d2 = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType == XmlPullParser.START_TAG && p.name == "trHeight") {
                            height = p.getAttributeValue(W, "val")?.toIntOrNull()
                            exact = p.getAttributeValue(W, "hRule") == "exact"
                        }
                    }
                }
                "tc" -> cells += cell(p)
                "sdt" -> {
                    val d2 = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType == XmlPullParser.START_TAG && p.name == "tc") cells += cell(p)
                    }
                }
                else -> skip(p)
            }
        }
        return WRow(cells.ifEmpty { listOf(WCell()) }, height, exact)
    }

    private fun cell(p: XmlPullParser): WCell {
        var span = 1
        var vMerge = VMerge.NONE
        var shading: Int? = null
        var vAlign = VAlign.TOP
        var borders: CellBorders? = null
        val blocks = ArrayList<WBlock>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "tcPr" -> {
                    val d2 = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType != XmlPullParser.START_TAG || p.depth != d2 + 1) continue
                        when (p.name) {
                            "gridSpan" -> { span = p.getAttributeValue(W, "val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1; skip(p) }
                            "vMerge" -> { vMerge = if (p.getAttributeValue(W, "val") == "restart") VMerge.RESTART else VMerge.CONTINUE; skip(p) }
                            "shd" -> { shading = color(p.getAttributeValue(W, "fill")); skip(p) }
                            "vAlign" -> {
                                vAlign = when (p.getAttributeValue(W, "val")) {
                                    "center" -> VAlign.CENTER
                                    "bottom" -> VAlign.BOTTOM
                                    else -> VAlign.TOP
                                }
                                skip(p)
                            }
                            "tcBorders" -> {
                                val b = readBorderSet(p)
                                borders = CellBorders(
                                    top = b["top"], bottom = b["bottom"], left = b["left"], right = b["right"],
                                    noTop = b.containsKey("top") && b["top"] == null,
                                    noBottom = b.containsKey("bottom") && b["bottom"] == null,
                                    noLeft = b.containsKey("left") && b["left"] == null,
                                    noRight = b.containsKey("right") && b["right"] == null,
                                )
                            }
                            else -> skip(p)
                        }
                    }
                }
                "p" -> blocks += paragraph(p)
                "tbl" -> blocks += table(p)
                "sdt" -> blocks += sdt(p)
                "customXml" -> blocks += this.blocks(p)
                else -> skip(p)
            }
        }
        return WCell(blocks.ifEmpty { listOf(WParagraph()) }, span, vMerge, shading, vAlign, borders)
    }
}
