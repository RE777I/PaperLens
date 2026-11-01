package com.paperlens.app.word

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Reads a .docx into a [WordDocument]: paragraphs with their run formatting (direct and from styles),
 * lists, tabs, tables (merged cells, borders, shading), pictures (inline and floating), text boxes and
 * page breaks. Anything else (headers, footers, comments…) is kept in [WordDocument.source] and
 * written back unchanged on save.
 */
object DocxReader {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val MAX_TOTAL = 200L * 1024 * 1024

    class NotWordException(message: String) : Exception(message)

    fun read(file: File): WordDocument = read(file.readBytes())

    fun read(bytes: ByteArray): WordDocument {
        val entries = unzip(bytes)
        val rootRels = entries["_rels/.rels"]?.let { parseRels(it, "") } ?: emptyMap()
        val main = rootRels.values.firstOrNull { it.first.endsWith("/officeDocument") }?.second?.removePrefix("/")
            ?: "word/document.xml"
        val documentXml = entries[main] ?: throw NotWordException("This file has no Word document inside")
        val mainDir = main.substringBeforeLast('/', "")
        val relsPath = (if (mainDir.isEmpty()) "" else "$mainDir/") + "_rels/" + main.substringAfterLast('/') + ".rels"
        val rels = entries[relsPath]?.let { parseRels(it, mainDir) } ?: emptyMap()
        val styles = rels.values.firstOrNull { it.first.endsWith("/styles") }?.second?.let { entries[it] }
            ?.let(StyleSheet::parse) ?: StyleSheet()
        val numbering = rels.values.firstOrNull { it.first.endsWith("/numbering") }?.second?.let { entries[it] }
            ?.let(Numbering::parse) ?: Numbering()

        val text = documentXml.toString(Charsets.UTF_8)
        val parser = Parser(rels, entries, styles, numbering)
        val blocks = parser.parse(documentXml)
        return WordDocument(
            blocks = blocks.ifEmpty { listOf(WParagraph()) },
            page = parser.page,
            media = parser.media,
            source = SourcePackage(entries, main, bodySectPr(text), rootNamespaces(text)),
        )
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_TOTAL) throw NotWordException("This file is too large to open")
                    out[e.name.removePrefix("/")] = data
                }
            }
        } catch (e: NotWordException) {
            throw e
        } catch (e: Exception) {
            throw NotWordException("This is not a Word (.docx) file")
        }
        if (out.isEmpty()) throw NotWordException("This is not a Word (.docx) file")
        return out
    }

    /** Relationship id -> (type, target path resolved against [baseDir]). */
    private fun parseRels(bytes: ByteArray, baseDir: String): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id") ?: continue
                val type = p.getAttributeValue(null, "Type") ?: ""
                val target = p.getAttributeValue(null, "Target") ?: continue
                if (p.getAttributeValue(null, "TargetMode") == "External") continue
                out[id] = type to resolve(baseDir, target)
            }
        }
        return out
    }

    private fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (if (baseDir.isEmpty()) emptyList() else baseDir.split('/')).toMutableList()
        for (seg in target.split('/')) {
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> Unit
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(ByteArrayInputStream(bytes), null)
    }

    private fun bodySectPr(xml: String): String? {
        val bodyEnd = xml.lastIndexOf(":body>").takeIf { it > 0 } ?: return null
        val start = Regex("<(\\w+:)?sectPr[\\s>/]").findAll(xml.substring(0, bodyEnd)).lastOrNull() ?: return null
        val prefix = start.groupValues[1]
        val close = "</${prefix}sectPr>"
        val end = xml.indexOf(close, start.range.first)
        // Only the body-level one: it must not sit inside a paragraph.
        if (end < 0 || end > bodyEnd) return null
        val raw = xml.substring(start.range.first, end + close.length)
        return if (xml.indexOf("</${prefix}p>", end) in 0 until bodyEnd) null else raw
    }

    private fun rootNamespaces(xml: String): String {
        val rootStart = Regex("<(\\w+:)?document[\\s>]").find(xml)?.range?.first ?: return ""
        val rootEnd = xml.indexOf('>', rootStart)
        val attrs = xml.substring(rootStart, rootEnd)
        return Regex("\\s(xmlns:\\w+=\"[^\"]*\"|\\w+:Ignorable=\"[^\"]*\")").findAll(attrs).joinToString(" ") { it.groupValues[1] }
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

    private class Style(val type: String, val basedOn: String?, val pPr: PPr, val rPr: RPr, val borders: WBorders?)

    private class StyleSheet(
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

    private class Level(val format: String, val text: String, val start: Int, val left: Int?, val hanging: Int?)

    private class Numbering(val nums: Map<Int, Map<Int, Level>> = emptyMap()) {
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

    private fun skip(p: XmlPullParser) {
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

    private fun onOff(p: XmlPullParser): Boolean {
        val v = p.getAttributeValue(W, "val") ?: return true
        return v != "0" && v != "false" && v != "off" && v != "none"
    }

    private fun color(v: String?): Int? =
        v?.takeIf { it.length == 6 && it != "auto" }?.toIntOrNull(16)

    private val HIGHLIGHTS = mapOf(
        "yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "blue" to 0x0000FF,
        "red" to 0xFF0000, "darkBlue" to 0x000080, "darkCyan" to 0x008080, "darkGreen" to 0x008000,
        "darkMagenta" to 0x800080, "darkRed" to 0x800000, "darkYellow" to 0x808000, "darkGray" to 0x808080,
        "lightGray" to 0xC0C0C0, "black" to 0x000000,
    )

    private fun readRPrIn(p: XmlPullParser): RPr {
        var out = RPr()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "rPr") out = readRPr(p)
        }
        return out
    }

    private fun readPPrIn(p: XmlPullParser): PPr {
        var out = PPr()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "pPr") out = readPPr(p)
        }
        return out
    }

    private fun readRPr(p: XmlPullParser): RPr {
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

    private fun readPPr(p: XmlPullParser): PPr {
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

    private class TblPr(val styleId: String?, val borders: WBorders?, val indent: Int?, val align: WAlign?, val cellMargin: Int?)

    private fun readBorder(p: XmlPullParser): WBorder? {
        val v = p.getAttributeValue(W, "val") ?: return null
        if (v == "nil" || v == "none") return null
        val size = p.getAttributeValue(W, "sz")?.toIntOrNull() ?: 4
        return WBorder(size.coerceIn(2, 96), color(p.getAttributeValue(W, "color")))
    }

    /** Reads <w:tblBorders>/<w:tcBorders>; the map holds sides that were written (value null = "nil"). */
    private fun readBorderSet(p: XmlPullParser): Map<String, WBorder?> {
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

    private fun readTblPr(p: XmlPullParser): TblPr {
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

    // ---- the main document -----------------------------------------------------------------------

    private class Parser(
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
}
