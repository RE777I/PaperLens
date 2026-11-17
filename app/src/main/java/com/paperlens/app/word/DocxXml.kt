package com.paperlens.app.word

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Pieces of WordprocessingML shared by [DocxReader] and [DocxBodyParser]: namespaces, run and paragraph
 * properties, the style sheet, list numbering and small element readers.
 */
internal object DocxXml {

    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"

    fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(ByteArrayInputStream(bytes), null)
    }

    // ---- formatting ------------------------------------------------------------------------------

    /** Run properties as written (null = not set here, inherit). */
    data class RPr(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val size: Float? = null,
        val color: Int? = null,
        val colorAuto: Boolean = false,
        val highlight: Int? = null,
        val font: String? = null,
        val hidden: Boolean? = null,
        val caps: Boolean? = null,
    ) {
        fun over(base: RPr) = RPr(
            bold ?: base.bold, italic ?: base.italic, underline ?: base.underline, strike ?: base.strike,
            size ?: base.size, if (colorAuto) null else color ?: base.color, colorAuto || (color == null && base.colorAuto),
            highlight ?: base.highlight, font ?: base.font, hidden ?: base.hidden, caps ?: base.caps,
        )

        fun toStyle() = RunStyle(
            bold = bold == true, italic = italic == true, underline = underline == true, strike = strike == true,
            size = size ?: 10f, color = color, highlight = highlight, font = font,
        )
    }

    data class PPr(
        val align: WAlign? = null,
        val before: Int? = null,
        val after: Int? = null,
        val line: Int? = null,
        val lineRule: LineRule? = null,
        val left: Int? = null,
        val right: Int? = null,
        val first: Int? = null,
        val tabs: List<WTab>? = null,
        val shading: Int? = null,
        val borderBottom: WBorder? = null,
        val numId: Int? = null,
        val ilvl: Int? = null,
        val styleId: String? = null,
        val pageBreakBefore: Boolean? = null,
    ) {
        fun over(base: PPr) = PPr(
            align ?: base.align, before ?: base.before, after ?: base.after, line ?: base.line, lineRule ?: base.lineRule,
            left ?: base.left, right ?: base.right, first ?: base.first,
            (base.tabs.orEmpty() + tabs.orEmpty()).takeIf { it.isNotEmpty() }, shading ?: base.shading,
            borderBottom ?: base.borderBottom, numId ?: base.numId, ilvl ?: base.ilvl, styleId ?: base.styleId, pageBreakBefore ?: base.pageBreakBefore,
        )
    }

    class Style(val type: String, val basedOn: String?, val pPr: PPr, val rPr: RPr, val borders: WBorders?)

    class StyleSheet(
        val defaultR: RPr = RPr(size = 10f),
        val defaultP: PPr = PPr(),
        val styles: Map<String, Style> = emptyMap(),
        val defaultParagraph: String? = null,
        val defaultTable: String? = null,
    ) {
        fun paragraph(id: String?): Pair<PPr, RPr> {
            val chain = chain(id ?: defaultParagraph)
            var p = defaultP
            var r = defaultR
            for (s in chain) { p = s.pPr.over(p); r = s.rPr.over(r) }
            return p to r
        }

        fun character(id: String?): RPr {
            var r = RPr()
            for (s in chain(id)) r = s.rPr.over(r)
            return r
        }

        fun tableBorders(id: String?): WBorders? = chain(id ?: defaultTable).lastOrNull { it.borders != null }?.borders

        private fun chain(id: String?): List<Style> {
            val out = ArrayList<Style>()
            var cur = id
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                val s = styles[cur] ?: break
                out.add(0, s)
                cur = s.basedOn
            }
            return out
        }

        companion object {
            fun parse(bytes: ByteArray): StyleSheet {
                val p = parser(bytes)
                var defaultR = RPr(size = 10f)
                var defaultP = PPr()
                val styles = HashMap<String, Style>()
                var defaultParagraph: String? = null
                var defaultTable: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "rPrDefault" -> defaultR = readRPrIn(p).over(RPr(size = 10f))
                        "pPrDefault" -> defaultP = readPPrIn(p)
                        "style" -> {
                            val type = p.getAttributeValue(W, "type") ?: "paragraph"
                            val id = p.getAttributeValue(W, "styleId") ?: continue
                            val isDefault = p.getAttributeValue(W, "default").let { it == "1" || it == "true" }
                            var basedOn: String? = null
                            var pPr = PPr()
                            var rPr = RPr()
                            var borders: WBorders? = null
                            val depth = p.depth
                            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                                if (p.eventType != XmlPullParser.START_TAG || p.depth != depth + 1) continue
                                when (p.name) {
                                    "basedOn" -> basedOn = p.getAttributeValue(W, "val")
                                    "pPr" -> pPr = readPPr(p)
                                    "rPr" -> rPr = readRPr(p)
                                    "tblPr" -> borders = readTblPr(p).borders
                                    else -> skip(p)
                                }
                            }
                            styles[id] = Style(type, basedOn, pPr, rPr, borders)
                            if (isDefault && type == "paragraph") defaultParagraph = id
                            if (isDefault && type == "table") defaultTable = id
                        }
                    }
                }
                return StyleSheet(defaultR, defaultP, styles, defaultParagraph, defaultTable)
            }
        }
    }

    class Level(val format: String, val text: String, val start: Int, val left: Int?, val hanging: Int?)

    class Numbering(val nums: Map<Int, Map<Int, Level>> = emptyMap()) {
        private val counters = HashMap<Int, IntArray>()

        fun label(numId: Int, level: Int): Pair<String, Boolean>? {
            val levels = nums[numId] ?: return null
            val lvl = levels[level] ?: return null
            if (lvl.format == "none") return "" to false
            val c = counters.getOrPut(numId) { IntArray(9) { i -> (levels[i]?.start ?: 1) - 1 } }
            c[level]++
            for (i in level + 1 until 9) c[i] = (levels[i]?.start ?: 1) - 1
            if (lvl.format == "bullet") return bullet(lvl.text) to false
            var text = lvl.text
            for (i in 0..level) {
                val f = levels[i]?.format ?: "decimal"
                text = text.replace("%${i + 1}", format(c[i].coerceAtLeast(1), f))
            }
            return text to true
        }

        fun indent(numId: Int, level: Int): Pair<Int?, Int?> = nums[numId]?.get(level)?.let { it.left to it.hanging } ?: (null to null)

        private fun bullet(text: String): String = when {
            text.isEmpty() -> "•"
            text[0] in '\uF000'..'\uF0FF' || text[0] in '\uE000'..'\uE0FF' -> when (text[0].code and 0xFF) {
                0xA7 -> "▪"
                0x6F -> "◦"
                0xD8 -> "➢"
                0xFC -> "✓"
                else -> "•"
            }
            else -> text
        }

        private fun format(n: Int, f: String): String = when (f) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "decimalZero" -> n.toString().padStart(2, '0')
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            var x = n
            val sb = StringBuilder()
            while (x > 0) { x--; sb.insert(0, 'A' + x % 26); x /= 26 }
            return sb.toString()
        }

        private fun roman(n: Int): String {
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n
            val sb = StringBuilder()
            for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
            return sb.toString()
        }

        companion object {
            fun parse(bytes: ByteArray): Numbering {
                val p = parser(bytes)
                val abstracts = HashMap<Int, HashMap<Int, Level>>()
                val numToAbstract = HashMap<Int, Int>()
                val overrides = HashMap<Int, HashMap<Int, Int>>()
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "abstractNum" -> {
                            val id = p.getAttributeValue(W, "abstractNumId")?.toIntOrNull() ?: continue
                            val levels = HashMap<Int, Level>()
                            val depth = p.depth
                            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                                if (p.eventType == XmlPullParser.START_TAG && p.name == "lvl") {
                                    val ilvl = p.getAttributeValue(W, "ilvl")?.toIntOrNull() ?: 0
                                    var fmt = "decimal"; var text = "%${ilvl + 1}."; var start = 1
                                    var left: Int? = null; var hanging: Int? = null
                                    val d2 = p.depth
                                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                                        if (p.eventType != XmlPullParser.START_TAG) continue
                                        when (p.name) {
                                            "numFmt" -> fmt = p.getAttributeValue(W, "val") ?: fmt
                                            "lvlText" -> text = p.getAttributeValue(W, "val") ?: text
                                            "start" -> start = p.getAttributeValue(W, "val")?.toIntOrNull() ?: 1
                                            "ind" -> {
                                                left = (p.getAttributeValue(W, "left") ?: p.getAttributeValue(W, "start"))?.toIntOrNull()
                                                hanging = p.getAttributeValue(W, "hanging")?.toIntOrNull()
                                            }
                                        }
                                    }
                                    if (ilvl in 0..8) levels[ilvl] = Level(fmt, text, start, left, hanging)
                                }
                            }
                            abstracts[id] = levels
                        }
                        "num" -> {
                            val numId = p.getAttributeValue(W, "numId")?.toIntOrNull() ?: continue
                            val depth = p.depth
                            var currentLevel = -1
                            while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
                                if (p.eventType != XmlPullParser.START_TAG) continue
                                when (p.name) {
                                    "abstractNumId" -> p.getAttributeValue(W, "val")?.toIntOrNull()?.let { numToAbstract[numId] = it }
                                    "lvlOverride" -> currentLevel = p.getAttributeValue(W, "ilvl")?.toIntOrNull() ?: -1
                                    "startOverride" -> if (currentLevel >= 0) {
                                        p.getAttributeValue(W, "val")?.toIntOrNull()?.let { overrides.getOrPut(numId) { HashMap() }[currentLevel] = it }
                                    }
                                }
                            }
                        }
                    }
                }
                val nums = HashMap<Int, Map<Int, Level>>()
                for ((numId, abs) in numToAbstract) {
                    val levels = abstracts[abs] ?: continue
                    val o = overrides[numId]
                    nums[numId] = if (o == null) levels else levels.mapValues { (l, lvl) ->
                        o[l]?.let { Level(lvl.format, lvl.text, it, lvl.left, lvl.hanging) } ?: lvl
                    }
                }
                return Numbering(nums)
            }
        }
    }

    // ---- shared element readers --------------------------------------------------------------------

    fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    fun onOff(p: XmlPullParser): Boolean {
        val v = p.getAttributeValue(W, "val") ?: return true
        return v != "0" && v != "false" && v != "off" && v != "none"
    }

    fun color(v: String?): Int? =
        v?.takeIf { it.length == 6 && it != "auto" }?.toIntOrNull(16)

    val HIGHLIGHTS = mapOf(
        "yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "blue" to 0x0000FF,
        "red" to 0xFF0000, "darkBlue" to 0x000080, "darkCyan" to 0x008080, "darkGreen" to 0x008000,
        "darkMagenta" to 0x800080, "darkRed" to 0x800000, "darkYellow" to 0x808000, "darkGray" to 0x808080,
        "lightGray" to 0xC0C0C0, "black" to 0x000000,
    )

    fun readRPrIn(p: XmlPullParser): RPr {
        var out = RPr()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "rPr") out = readRPr(p)
        }
        return out
    }

    fun readPPrIn(p: XmlPullParser): PPr {
        var out = PPr()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "pPr") out = readPPr(p)
        }
        return out
    }

    fun readRPr(p: XmlPullParser): RPr {
        var r = RPr()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            if (p.depth != depth + 1) { skip(p); continue }
            when (p.name) {
                "b" -> r = r.copy(bold = onOff(p))
                "i" -> r = r.copy(italic = onOff(p))
                "u" -> r = r.copy(underline = p.getAttributeValue(W, "val").let { it != "none" && it != "0" })
                "strike", "dstrike" -> r = r.copy(strike = onOff(p))
                "sz" -> p.getAttributeValue(W, "val")?.toFloatOrNull()?.let { r = r.copy(size = it / 2f) }
                "color" -> {
                    val v = p.getAttributeValue(W, "val")
                    r = if (v == "auto") r.copy(colorAuto = true, color = null) else r.copy(color = color(v))
                }
                "highlight" -> r = r.copy(highlight = HIGHLIGHTS[p.getAttributeValue(W, "val")])
                "shd" -> color(p.getAttributeValue(W, "fill"))?.let { r = r.copy(highlight = it) }
                "rFonts" -> (p.getAttributeValue(W, "ascii") ?: p.getAttributeValue(W, "hAnsi"))?.let { r = r.copy(font = it) }
                "vanish" -> r = r.copy(hidden = onOff(p))
                "caps" -> r = r.copy(caps = onOff(p))
            }
            skip(p)
        }
        return r
    }

    fun readPPr(p: XmlPullParser): PPr {
        var r = PPr()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            if (p.depth != depth + 1) { skip(p); continue }
            when (p.name) {
                "pStyle" -> r = r.copy(styleId = p.getAttributeValue(W, "val"))
                "jc" -> r = r.copy(
                    align = when (p.getAttributeValue(W, "val")) {
                        "center" -> WAlign.CENTER
                        "right", "end" -> WAlign.RIGHT
                        "both", "distribute" -> WAlign.JUSTIFY
                        else -> WAlign.LEFT
                    },
                )
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
                }
                "ind" -> {
                    (p.getAttributeValue(W, "left") ?: p.getAttributeValue(W, "start"))?.toIntOrNull()?.let { r = r.copy(left = it) }
                    (p.getAttributeValue(W, "right") ?: p.getAttributeValue(W, "end"))?.toIntOrNull()?.let { r = r.copy(right = it) }
                    p.getAttributeValue(W, "firstLine")?.toIntOrNull()?.let { r = r.copy(first = it) }
                    p.getAttributeValue(W, "hanging")?.toIntOrNull()?.let { r = r.copy(first = -it) }
                }
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
                    continue
                }
                "shd" -> color(p.getAttributeValue(W, "fill"))?.let { r = r.copy(shading = it) }
                "numPr" -> {
                    val d2 = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType != XmlPullParser.START_TAG) continue
                        when (p.name) {
                            "ilvl" -> r = r.copy(ilvl = p.getAttributeValue(W, "val")?.toIntOrNull())
                            "numId" -> r = r.copy(numId = p.getAttributeValue(W, "val")?.toIntOrNull())
                        }
                    }
                    continue
                }
                "pageBreakBefore" -> r = r.copy(pageBreakBefore = onOff(p))
            }
            skip(p)
        }
        return r
    }

    class TblPr(val styleId: String?, val borders: WBorders?, val indent: Int?, val align: WAlign?, val cellMargin: Int?)

    fun readBorder(p: XmlPullParser): WBorder? {
        val v = p.getAttributeValue(W, "val") ?: return null
        if (v == "nil" || v == "none") return null
        val size = p.getAttributeValue(W, "sz")?.toIntOrNull() ?: 4
        return WBorder(size.coerceIn(2, 96), color(p.getAttributeValue(W, "color")))
    }

    /** Reads <w:tblBorders>/<w:tcBorders>; the map holds sides that were written (value null = "nil"). */
    fun readBorderSet(p: XmlPullParser): Map<String, WBorder?> {
        val out = HashMap<String, WBorder?>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            val side = when (p.name) {
                "start" -> "left"
                "end" -> "right"
                else -> p.name
            }
            out[side] = readBorder(p)
            skip(p)
        }
        return out
    }

    fun readTblPr(p: XmlPullParser): TblPr {
        var styleId: String? = null
        var borders: WBorders? = null
        var indent: Int? = null
        var align: WAlign? = null
        var cellMargin: Int? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            if (p.depth != depth + 1) { skip(p); continue }
            when (p.name) {
                "tblStyle" -> { styleId = p.getAttributeValue(W, "val"); skip(p) }
                "tblBorders" -> {
                    val b = readBorderSet(p)
                    borders = WBorders(b["top"], b["bottom"], b["left"], b["right"], b["insideH"], b["insideV"])
                }
                "tblInd" -> { if (p.getAttributeValue(W, "type") != "pct") indent = p.getAttributeValue(W, "w")?.toIntOrNull(); skip(p) }
                "jc" -> {
                    align = when (p.getAttributeValue(W, "val")) {
                        "center" -> WAlign.CENTER
                        "right", "end" -> WAlign.RIGHT
                        else -> WAlign.LEFT
                    }
                    skip(p)
                }
                "tblCellMar" -> {
                    val d2 = p.depth
                    while (!(p.next() == XmlPullParser.END_TAG && p.depth == d2)) {
                        if (p.eventType == XmlPullParser.START_TAG && (p.name == "left" || p.name == "start")) {
                            cellMargin = p.getAttributeValue(W, "w")?.toIntOrNull()
                        }
                    }
                }
                else -> skip(p)
            }
        }
        return TblPr(styleId, borders, indent, align, cellMargin)
    }

}
