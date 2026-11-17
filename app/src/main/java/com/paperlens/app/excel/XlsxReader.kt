package com.paperlens.app.excel

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads an .xlsx into a [Workbook]: every worksheet with its values (shared and inline strings, numbers,
 * booleans, errors, formulas with their last result), cell formatting from the style sheet (fonts, fills,
 * borders, alignment, number formats; theme and indexed colours resolved), merged cells, column widths,
 * row heights, hidden rows / columns and frozen panes. Charts, pictures and the like are not shown, but
 * the file itself is kept, so saving an edit ([XlsxEditor]) leaves them in place.
 */
object XlsxReader {

    private const val MAX_TOTAL = 200L * 1024 * 1024

    /** Cells beyond this many are not loaded (the viewer would be unusable anyway). */
    private const val MAX_CELLS = 400_000

    class NotExcelException(message: String) : Exception(message)

    fun read(file: File): Workbook = read(file.readBytes())

    fun read(bytes: ByteArray): Workbook {
        val entries = unzip(bytes)
        val pkg = locate(entries)
        val rels = pkg.rels
        val strings = rels.values.firstOrNull { it.first.endsWith("/sharedStrings") }?.second?.let { entries[it] }?.let(::sharedStrings) ?: emptyList()
        val theme = rels.values.firstOrNull { it.first.endsWith("/theme") }?.second?.let { entries[it] }?.let(::themeColors) ?: DEFAULT_THEME
        val styles = rels.values.firstOrNull { it.first.endsWith("/styles") }?.second?.let { entries[it] }?.let { Styles.parse(it, theme) } ?: Styles()
        var budget = MAX_CELLS
        val sheets = pkg.sheets.map { (name, path) ->
            val sheet = SheetParser(name, strings, styles).parse(entries.getValue(path), budget)
            budget -= sheet.cells.size
            sheet
        }
        return Workbook(sheets, pkg.date1904)
    }

    /** Where the parts of a workbook package are: the shown sheets in order as (name, part path). */
    internal class Package(val workbook: String, val rels: Map<String, Pair<String, String>>, val sheets: List<Pair<String, String>>, val date1904: Boolean)

    internal fun locate(entries: Map<String, ByteArray>): Package {
        val rootRels = entries["_rels/.rels"]?.let { parseRels(it, "") } ?: emptyMap()
        val main = rootRels.values.firstOrNull { it.first.endsWith("/officeDocument") }?.second ?: "xl/workbook.xml"
        val workbookXml = entries[main] ?: throw NotExcelException("This file has no Excel workbook inside")
        val rels = entries[relsPath(main)]?.let { parseRels(it, main.substringBeforeLast('/', "")) } ?: emptyMap()
        var date1904 = false
        val sheets = ArrayList<Pair<String, String>>()
        val p = parser(workbookXml)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
                "sheet" -> {
                    val id = p.getAttributeValue(R_NS, "id") ?: continue
                    if (p.attr("state") == "veryHidden") continue
                    // Chart sheets and dialog sheets have no cells to show.
                    val (type, path) = rels[id] ?: continue
                    if (!type.endsWith("/worksheet") || path !in entries) continue
                    sheets += (p.attr("name") ?: "Sheet${sheets.size + 1}") to path
                }
            }
        }
        if (sheets.isEmpty()) throw NotExcelException("This workbook has no sheets to show")
        return Package(main, rels, sheets, date1904)
    }

    internal fun relsPath(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        return (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
    }

    internal fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_TOTAL) throw NotExcelException("This file is too large to open")
                    out[e.name.removePrefix("/")] = data
                }
            }
        } catch (e: NotExcelException) {
            throw e
        } catch (e: Exception) {
            throw NotExcelException("This is not an Excel (.xlsx) file")
        }
        if (out.isEmpty()) throw NotExcelException("This is not an Excel (.xlsx) file")
        return out
    }

    /** Relationship id → (type, target path resolved against [baseDir]). */
    internal fun parseRels(bytes: ByteArray, baseDir: String): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.attr("Id") ?: continue
                if (p.attr("TargetMode") == "External") continue
                out[id] = (p.attr("Type") ?: "") to resolve(baseDir, p.attr("Target") ?: continue)
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

    internal fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(ByteArrayInputStream(bytes), null)
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    /** Text of every `<si>`: plain `<t>` or rich-text runs; phonetic guides (`<rPh>`) are left out. */
    private fun sharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(bytes)
        val cur = StringBuilder()
        var inT = false
        var phonetic = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> cur.clear()
                    "rPh" -> phonetic++
                    "t" -> inT = phonetic == 0
                }
                XmlPullParser.TEXT -> if (inT) cur.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> out += cur.toString()
                    "rPh" -> phonetic--
                    "t" -> inT = false
                }
            }
        }
        return out
    }

    // ---- colours --------------------------------------------------------------------------------------

    /** Office's default theme, in Excel's index order (light 1, dark 1, light 2, dark 2, accents 1–6, links). */
    private val DEFAULT_THEME = intArrayOf(
        0xFFFFFF, 0x000000, 0xE7E6E6, 0x44546A, 0x4472C4, 0xED7D31, 0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47, 0x0563C1, 0x954F72,
    )

    private fun themeColors(bytes: ByteArray): IntArray {
        // The file lists dk1, lt1, dk2, lt2, accent1–6, hlink, folHlink; Excel numbers lt1 first.
        val found = ArrayList<Int>()
        val p = parser(bytes)
        var inScheme = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "clrScheme") inScheme = true
                    else if (inScheme) {
                        when (p.name) {
                            "srgbClr" -> p.attr("val")?.toIntOrNull(16)?.let { found += it }
                            "sysClr" -> (p.attr("lastClr") ?: if (p.attr("val") == "window") "FFFFFF" else "000000").toIntOrNull(16)?.let { found += it }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "clrScheme") break
            }
        }
        if (found.size < 10) return DEFAULT_THEME
        val out = DEFAULT_THEME.copyOf()
        out[0] = found[1]; out[1] = found[0]; out[2] = found[3]; out[3] = found[2]
        for (i in 4 until min(found.size, 12)) out[i] = found[i]
        return out
    }

    /** Excel's legacy 64-colour palette (indexed colours). */
    private val INDEXED = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /** A `<color>`-like element's colour, or null for automatic / system colours. */
    private fun colorOf(p: XmlPullParser, theme: IntArray): Int? {
        val base = p.attr("rgb")?.let { (it.takeLast(6).toLongOrNull(16) ?: return null).toInt() }
            ?: p.attr("theme")?.toIntOrNull()?.let { theme.getOrNull(it) }
            ?: p.attr("indexed")?.toIntOrNull()?.let { INDEXED.getOrNull(it) }
            ?: return null
        val tint = p.attr("tint")?.toDoubleOrNull() ?: 0.0
        return if (tint == 0.0) base else tinted(base, tint)
    }

    /** Lightens (tint > 0) or darkens a colour the way Excel applies theme tints (in HSL lightness). */
    private fun tinted(rgb: Int, tint: Double): Int {
        val r = ((rgb shr 16) and 0xFF) / 255.0
        val g = ((rgb shr 8) and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        var l = (mx + mn) / 2
        val d = mx - mn
        val s = if (d == 0.0) 0.0 else d / (1 - kotlin.math.abs(2 * l - 1))
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> ((g - b) / d).mod(6.0)
            mx == g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        l = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60).mod(2.0) - 1))
        val m = l - c / 2
        val (r1, g1, b1) = when ((h / 60).toInt()) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    // ---- styles ---------------------------------------------------------------------------------------

    private class Font(val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false, val size: Float? = null, val color: Int? = null)
    private class Border(val left: Edge? = null, val top: Edge? = null, val right: Edge? = null, val bottom: Edge? = null)

    /** The style sheet: `s="n"` on a cell is index n into [xfs]. */
    private class Styles(val xfs: List<CellStyle> = emptyList()) {
        operator fun get(i: Int?): CellStyle = i?.let { xfs.getOrNull(it) } ?: xfs.firstOrNull() ?: CellStyle.DEFAULT

        companion object {
            fun parse(bytes: ByteArray, theme: IntArray): Styles {
                val formats = HashMap<Int, String>(NumberFormat.BUILT_IN)
                val fonts = ArrayList<Font>()
                val fills = ArrayList<Int?>()
                val borders = ArrayList<Border>()
                val xfs = ArrayList<CellStyle>()
                val p = parser(bytes)
                var section = ""
                // Element being read inside fonts / fills / borders.
                var font: MutableMap<String, Any?>? = null
                var fill: Int? = null
                var fillSolid = false
                var border: MutableMap<String, Edge?>? = null
                var edge: String? = null
                var edgeStyle: String? = null
                var xf: CellStyle? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (val n = p.name) {
                            "numFmts", "fonts", "fills", "borders", "cellXfs", "cellStyleXfs", "dxfs" -> section = n
                            "numFmt" -> if (section == "numFmts") {
                                val id = p.attr("numFmtId")?.toIntOrNull()
                                if (id != null) formats[id] = p.attr("formatCode") ?: "General"
                            }
                            "font" -> if (section == "fonts") font = HashMap()
                            "b", "i", "u", "strike" -> font?.let { f ->
                                val v = p.attr("val")
                                f[n] = v == null || (v != "0" && v != "false" && v != "none")
                            }
                            "sz" -> font?.put("sz", p.attr("val")?.toFloatOrNull())
                            "color" -> when {
                                font != null -> font["color"] = colorOf(p, theme)
                                border != null && edge != null && edgeStyle != null -> border[edge] = Edge(edgeWidth(edgeStyle), colorOf(p, theme))
                            }
                            "fill" -> if (section == "fills") { fill = null; fillSolid = false }
                            "patternFill" -> if (section == "fills") fillSolid = p.attr("patternType").let { it != null && it != "none" }
                            "fgColor" -> if (section == "fills" && fillSolid) fill = colorOf(p, theme)
                            "bgColor" -> if (section == "fills" && fillSolid && fill == null) fill = colorOf(p, theme)
                            "gradientFill" -> if (section == "fills") fillSolid = true
                            "border" -> if (section == "borders") border = HashMap()
                            "left", "right", "top", "bottom", "start", "end" -> if (border != null) {
                                edge = when (n) { "start" -> "left"; "end" -> "right"; else -> n }
                                edgeStyle = p.attr("style")?.takeIf { it != "none" }
                                if (edgeStyle != null) border[edge!!] = Edge(edgeWidth(edgeStyle), null)
                            }
                            "xf" -> if (section == "cellXfs") {
                                val f = fonts.getOrNull(p.attr("fontId")?.toIntOrNull() ?: 0) ?: Font()
                                val b = borders.getOrNull(p.attr("borderId")?.toIntOrNull() ?: 0) ?: Border()
                                xf = CellStyle(
                                    bold = f.bold, italic = f.italic, underline = f.underline, strike = f.strike,
                                    fontSize = f.size, fontColor = f.color,
                                    fill = fills.getOrNull(p.attr("fillId")?.toIntOrNull() ?: 0),
                                    numFmt = formats[p.attr("numFmtId")?.toIntOrNull() ?: 0] ?: "General",
                                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                                )
                            }
                            "alignment" -> xf?.let { s ->
                                xf = s.copy(
                                    hAlign = when (p.attr("horizontal")) {
                                        "left" -> HAlign.LEFT
                                        "center", "centerContinuous", "distributed" -> HAlign.CENTER
                                        "right" -> HAlign.RIGHT
                                        else -> HAlign.GENERAL
                                    },
                                    vAlign = when (p.attr("vertical")) {
                                        "top" -> VAlign.TOP
                                        "center", "distributed", "justify" -> VAlign.CENTER
                                        else -> VAlign.BOTTOM
                                    },
                                    wrap = p.attr("wrapText").let { it == "1" || it == "true" },
                                )
                            }
                        }
                        XmlPullParser.END_TAG -> when (p.name) {
                            "numFmts", "fonts", "fills", "borders", "cellXfs", "cellStyleXfs", "dxfs" -> section = ""
                            "font" -> font?.let { f ->
                                fonts += Font(f["b"] == true, f["i"] == true, f["u"] == true, f["strike"] == true, f["sz"] as Float?, f["color"] as Int?)
                                font = null
                            }
                            "fill" -> if (section == "fills") fills += fill
                            "left", "right", "top", "bottom", "start", "end" -> { edge = null; edgeStyle = null }
                            "border" -> border?.let { b ->
                                borders += Border(b["left"], b["top"], b["right"], b["bottom"])
                                border = null
                            }
                            "xf" -> xf?.let { xfs += it; xf = null }
                        }
                    }
                }
                return Styles(xfs)
            }

            private fun edgeWidth(style: String): Float = when (style) {
                "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot" -> 2f
                "thick", "double" -> 3f
                else -> 1f
            }
        }
    }

    // ---- worksheets -----------------------------------------------------------------------------------

    private class SheetParser(val name: String, val strings: List<String>, val styles: Styles) {
        fun parse(bytes: ByteArray, budget: Int): Sheet {
            val cells = HashMap<Long, XCell>()
            val merges = ArrayList<CellRange>()
            val colWidths = HashMap<Int, Float>()
            val rowHeights = HashMap<Int, Float>()
            val hiddenCols = HashSet<Int>()
            val hiddenRows = HashSet<Int>()
            var defaultColWidth = 8.43f
            var defaultRowHeight = 15f
            var frozenRows = 0
            var frozenCols = 0
            val p = parser(bytes)
            var row = -1
            var col = -1
            // Current cell.
            var cRow = 0; var cCol = 0; var cType: String? = null; var cStyle: Int? = null
            var value: StringBuilder? = null
            var formula: StringBuilder? = null
            var inline: StringBuilder? = null
            var target: StringBuilder? = null
            var phonetic = 0
            var full = false
            loop@ while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "sheetFormatPr" -> {
                            p.getAttributeValue(null, "defaultRowHeight")?.toFloatOrNull()?.let { defaultRowHeight = it }
                            p.getAttributeValue(null, "defaultColWidth")?.toFloatOrNull()?.let { defaultColWidth = it }
                                ?: p.getAttributeValue(null, "baseColWidth")?.toFloatOrNull()?.let { defaultColWidth = it + 0.71f }
                        }
                        "col" -> {
                            val min = (p.getAttributeValue(null, "min")?.toIntOrNull() ?: continue@loop) - 1
                            val max = ((p.getAttributeValue(null, "max")?.toIntOrNull() ?: (min + 1)) - 1).coerceAtMost(min + 16383).coerceAtMost(16383)
                            val width = p.getAttributeValue(null, "width")?.toFloatOrNull()
                            val hidden = p.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }
                            for (c in min..max) {
                                if (width != null) colWidths[c] = width
                                if (hidden) hiddenCols += c
                            }
                        }
                        "pane" -> if (p.getAttributeValue(null, "state").let { it == "frozen" || it == "frozenSplit" }) {
                            frozenRows = p.getAttributeValue(null, "ySplit")?.toFloatOrNull()?.toInt() ?: 0
                            frozenCols = p.getAttributeValue(null, "xSplit")?.toFloatOrNull()?.toInt() ?: 0
                        }
                        "row" -> {
                            row = (p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1)) ?: (row + 1)
                            col = -1
                            p.getAttributeValue(null, "ht")?.toFloatOrNull()?.let { rowHeights[row] = it }
                            if (p.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }) hiddenRows += row
                        }
                        "c" -> {
                            val ref = p.getAttributeValue(null, "r")?.let(::parseCellRef)
                            cRow = ref?.first ?: row
                            cCol = ref?.second ?: (col + 1)
                            col = cCol
                            cType = p.getAttributeValue(null, "t")
                            cStyle = p.getAttributeValue(null, "s")?.toIntOrNull()
                            value = null; formula = null; inline = null
                        }
                        "v" -> { value = StringBuilder(); target = value }
                        "f" -> { formula = StringBuilder(); target = formula }
                        "is" -> inline = StringBuilder()
                        "rPh" -> phonetic++
                        "t" -> if (inline != null && phonetic == 0) target = inline
                        "mergeCell" -> p.getAttributeValue(null, "ref")?.let { ref ->
                            val a = parseCellRef(ref.substringBefore(':')) ?: return@let
                            val b = parseCellRef(ref.substringAfter(':', ref)) ?: a
                            merges += CellRange(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
                        }
                    }
                    XmlPullParser.TEXT -> target?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v", "f", "t" -> target = null
                        "rPh" -> phonetic--
                        "c" -> {
                            if (full) continue@loop
                            val v = value?.toString()
                            val cellValue: CellValue = when (cType) {
                                "s" -> v?.trim()?.toIntOrNull()?.let { strings.getOrNull(it) }?.let { CellValue.Text(it) } ?: CellValue.Empty
                                "inlineStr" -> inline?.let { CellValue.Text(it.toString()) } ?: CellValue.Empty
                                "str" -> v?.let { CellValue.Text(it) } ?: CellValue.Empty
                                "b" -> v?.let { CellValue.Bool(it.trim() == "1" || it.trim() == "true") } ?: CellValue.Empty
                                "e" -> v?.let { CellValue.Error(it) } ?: CellValue.Empty
                                // "n", "d" (ISO date text, rare) and no type.
                                else -> v?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> t.toDoubleOrNull()?.let { CellValue.Number(it) } ?: CellValue.Text(t) } ?: CellValue.Empty
                            }
                            val f = formula?.toString()?.takeIf { it.isNotBlank() }
                            val style = styles[cStyle]
                            // Empty cells only matter when they show something (a fill or a border).
                            val visible = style.fill != null || style.left != null || style.top != null || style.right != null || style.bottom != null
                            if (cellValue != CellValue.Empty || f != null || visible) {
                                cells[cellKey(cRow, cCol)] = XCell(cellValue, style, f)
                                if (cells.size >= budget) full = true
                            }
                        }
                    }
                }
            }
            return Sheet(name, cells, merges, colWidths, rowHeights, defaultColWidth, defaultRowHeight, frozenRows, frozenCols, hiddenCols, hiddenRows)
        }
    }

    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
}
