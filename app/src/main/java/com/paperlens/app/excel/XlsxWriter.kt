package com.paperlens.app.excel

import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [Workbook] as an .xlsx that Excel, Google Sheets, WPS and LibreOffice open: inline strings,
 * numbers stored as numbers with their number format, fonts / fills / borders / alignment as a style sheet,
 * merged cells, column widths, row heights and frozen header rows.
 */
object XlsxWriter {

    fun write(book: Workbook, out: File): File {
        val names = mutableListOf<String>()
        book.sheets.forEachIndexed { i, s ->
            val clean = sheetName(s.name)
            names += if (names.any { it.equals(clean, ignoreCase = true) }) "${clean.take(26)} (${i + 1})" else clean
        }
        val styles = StyleTable()
        val sheetXml = book.sheets.map { sheetXml(it, styles) }
        ZipOutputStream(out.outputStream()).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                HEADER + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                    book.sheets.indices.joinToString("") {
                        "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" " +
                            "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    } +
                    "</Types>",
            )
            put(
                "_rels/.rels",
                HEADER + "<Relationships xmlns=\"$PKG_RELS\">" +
                    "<Relationship Id=\"rId1\" Type=\"$DOC_RELS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>",
            )
            put(
                "xl/workbook.xml",
                HEADER + "<workbook xmlns=\"$MAIN\" xmlns:r=\"$DOC_RELS\">" +
                    (if (book.date1904) "<workbookPr date1904=\"1\"/>" else "") +
                    "<sheets>" +
                    names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
                    "</sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                HEADER + "<Relationships xmlns=\"$PKG_RELS\">" +
                    book.sheets.indices.joinToString("") {
                        "<Relationship Id=\"rId${it + 1}\" Type=\"$DOC_RELS/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>"
                    } +
                    "<Relationship Id=\"rId${book.sheets.size + 1}\" Type=\"$DOC_RELS/styles\" Target=\"styles.xml\"/>" +
                    "</Relationships>",
            )
            sheetXml.forEachIndexed { i, xml -> put("xl/worksheets/sheet${i + 1}.xml", xml) }
            put("xl/styles.xml", styles.xml())
        }
        return out
    }

    private fun sheetXml(s: Sheet, styles: StyleTable): String = buildString {
        append(HEADER)
        append("<worksheet xmlns=\"$MAIN\" xmlns:r=\"$DOC_RELS\">")
        if (s.frozenRows > 0 || s.frozenCols > 0) {
            val topLeft = cellName(s.frozenRows, s.frozenCols)
            append("<sheetViews><sheetView workbookViewId=\"0\"><pane")
            if (s.frozenCols > 0) append(" xSplit=\"${s.frozenCols}\"")
            if (s.frozenRows > 0) append(" ySplit=\"${s.frozenRows}\"")
            val pane = when {
                s.frozenRows > 0 && s.frozenCols > 0 -> "bottomRight"
                s.frozenRows > 0 -> "bottomLeft"
                else -> "topRight"
            }
            append(" topLeftCell=\"$topLeft\" activePane=\"$pane\" state=\"frozen\"/></sheetView></sheetViews>")
        }
        append("<sheetFormatPr defaultRowHeight=\"${num(s.defaultRowHeight)}\"/>")
        val widths = (s.colWidths.keys + s.hiddenCols).sorted()
        if (widths.isNotEmpty()) {
            append("<cols>")
            for (c in widths) {
                append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${num(s.colWidths[c] ?: s.defaultColWidth)}\" customWidth=\"1\"")
                if (c in s.hiddenCols) append(" hidden=\"1\"")
                append("/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        val byRow = s.cells.entries.groupBy { keyRow(it.key) }
        val rows = (byRow.keys + s.rowHeights.keys + s.hiddenRows).toSortedSet()
        for (r in rows) {
            append("<row r=\"${r + 1}\"")
            s.rowHeights[r]?.let { append(" ht=\"${num(it)}\" customHeight=\"1\"") }
            if (r in s.hiddenRows) append(" hidden=\"1\"")
            append(">")
            for ((key, cell) in byRow[r].orEmpty().sortedBy { keyCol(it.key) }) append(cellXml(cellName(r, keyCol(key)), cell, styles.index(cell.style)))
            append("</row>")
        }
        append("</sheetData>")
        if (s.merges.isNotEmpty()) {
            append("<mergeCells count=\"${s.merges.size}\">")
            for (m in s.merges) append("<mergeCell ref=\"${cellName(m.r0, m.c0)}:${cellName(m.r1, m.c1)}\"/>")
            append("</mergeCells>")
        }
        append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        append("</worksheet>")
    }

    /** One `<c>` element; [style] 0 is left out. Shared with [XlsxEditor]. */
    internal fun cellXml(ref: String, cell: XCell, style: Int, prefix: String = ""): String {
        val s = if (style > 0) " s=\"$style\"" else ""
        val f = cell.formula?.let { "<${prefix}f>${esc(it)}</${prefix}f>" } ?: ""
        return when (val v = cell.value) {
            is CellValue.Number -> "<${prefix}c r=\"$ref\"$s>$f<${prefix}v>${NumberFormat.plain(v.value)}</${prefix}v></${prefix}c>"
            is CellValue.Bool -> "<${prefix}c r=\"$ref\"$s t=\"b\">$f<${prefix}v>${if (v.value) 1 else 0}</${prefix}v></${prefix}c>"
            is CellValue.Error -> "<${prefix}c r=\"$ref\"$s t=\"e\">$f<${prefix}v>${esc(v.code)}</${prefix}v></${prefix}c>"
            is CellValue.Text -> if (f.isNotEmpty()) {
                "<${prefix}c r=\"$ref\"$s t=\"str\">$f<${prefix}v>${esc(v.text)}</${prefix}v></${prefix}c>"
            } else {
                "<${prefix}c r=\"$ref\"$s t=\"inlineStr\"><${prefix}is><${prefix}t xml:space=\"preserve\">${esc(v.text)}</${prefix}t></${prefix}is></${prefix}c>"
            }
            CellValue.Empty -> "<${prefix}c r=\"$ref\"$s>$f</${prefix}c>"
        }
    }

    /** Unique cell styles in order of first use, written as fonts / fills / borders / number formats / cellXfs. */
    private class StyleTable {
        private val styles = linkedMapOf(CellStyle.DEFAULT to 0)

        fun index(style: CellStyle): Int = styles.getOrPut(style) { styles.size }

        fun xml(): String {
            val fonts = LinkedHashMap<String, Int>()
            val fills = LinkedHashMap<String, Int>()
            val borders = LinkedHashMap<String, Int>()
            val formats = LinkedHashMap<String, Int>()
            fun font(s: CellStyle) = buildString {
                append("<font>")
                if (s.bold) append("<b/>")
                if (s.italic) append("<i/>")
                if (s.strike) append("<strike/>")
                if (s.underline) append("<u/>")
                append("<sz val=\"${num(s.fontSize ?: 11f)}\"/>")
                s.fontColor?.let { append("<color rgb=\"${argb(it)}\"/>") }
                append("<name val=\"Calibri\"/><family val=\"2\"/><scheme val=\"minor\"/></font>")
            }
            fun fill(s: CellStyle) = s.fill?.let { "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${argb(it)}\"/><bgColor indexed=\"64\"/></patternFill></fill>" }
                ?: "<fill><patternFill patternType=\"none\"/></fill>"
            fun edge(tag: String, e: Edge?) = if (e == null) "<$tag/>" else {
                val st = when { e.width >= 3f -> "thick"; e.width >= 2f -> "medium"; else -> "thin" }
                "<$tag style=\"$st\">" + (e.color?.let { "<color rgb=\"${argb(it)}\"/>" } ?: "<color indexed=\"64\"/>") + "</$tag>"
            }
            fun border(s: CellStyle) = "<border>" + edge("left", s.left) + edge("right", s.right) + edge("top", s.top) + edge("bottom", s.bottom) + "<diagonal/></border>"
            fonts[font(CellStyle.DEFAULT)] = 0
            fills["<fill><patternFill patternType=\"none\"/></fill>"] = 0
            fills["<fill><patternFill patternType=\"gray125\"/></fill>"] = 1
            borders[border(CellStyle.DEFAULT)] = 0
            val builtIn = NumberFormat.BUILT_IN.entries.associate { it.value to it.key }
            val xfs = styles.keys.map { s ->
                val fo = fonts.getOrPut(font(s)) { fonts.size }
                val fi = fills.getOrPut(fill(s)) { fills.size }
                val bo = borders.getOrPut(border(s)) { borders.size }
                val nf = builtIn[s.numFmt] ?: formats.getOrPut(s.numFmt) { 164 + formats.size }
                buildString {
                    append("<xf numFmtId=\"$nf\" fontId=\"$fo\" fillId=\"$fi\" borderId=\"$bo\" xfId=\"0\"")
                    if (nf != 0) append(" applyNumberFormat=\"1\"")
                    if (fo != 0) append(" applyFont=\"1\"")
                    if (fi != 0) append(" applyFill=\"1\"")
                    if (bo != 0) append(" applyBorder=\"1\"")
                    val h = when (s.hAlign) { HAlign.LEFT -> "left"; HAlign.CENTER -> "center"; HAlign.RIGHT -> "right"; HAlign.GENERAL -> null }
                    val v = when (s.vAlign) { VAlign.TOP -> "top"; VAlign.CENTER -> "center"; VAlign.BOTTOM -> null }
                    if (h != null || v != null || s.wrap) {
                        append(" applyAlignment=\"1\"><alignment")
                        h?.let { append(" horizontal=\"$it\"") }
                        v?.let { append(" vertical=\"$it\"") }
                        if (s.wrap) append(" wrapText=\"1\"")
                        append("/></xf>")
                    } else {
                        append("/>")
                    }
                }
            }
            return HEADER + "<styleSheet xmlns=\"$MAIN\">" +
                (if (formats.isEmpty()) "" else "<numFmts count=\"${formats.size}\">" + formats.entries.joinToString("") { "<numFmt numFmtId=\"${it.value}\" formatCode=\"${esc(it.key)}\"/>" } + "</numFmts>") +
                "<fonts count=\"${fonts.size}\">" + fonts.keys.joinToString("") + "</fonts>" +
                "<fills count=\"${fills.size}\">" + fills.keys.joinToString("") + "</fills>" +
                "<borders count=\"${borders.size}\">" + borders.keys.joinToString("") + "</borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"${xfs.size}\">" + xfs.joinToString("") + "</cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "</styleSheet>"
        }

        private fun argb(rgb: Int) = "FF" + String.format(Locale.ROOT, "%06X", rgb and 0xFFFFFF)
    }

    // Excel sheet names: max 31 chars, no []:*?/\ (uniqueness handled by the caller).
    fun sheetName(name: String): String =
        name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().trim('\'').take(31).ifBlank { "Sheet" }

    private fun num(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(Locale.ROOT, "%.2f", f).trimEnd('0').trimEnd('.')

    internal fun esc(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\t' || ch == '\n' || ch == '\r' || (ch >= ' ' && ch != '￾' && ch != '￿') -> append(ch)
        }
    }

    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    internal const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val DOC_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}
