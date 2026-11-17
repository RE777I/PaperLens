package com.paperlens.app.excel

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Saves cell edits into an .xlsx by rewriting only the edited `<c>` elements of the sheets they are on,
 * so everything the app doesn't show (charts, pictures, conditional formats, other sheets' formulas…)
 * stays as it was. The calculation chain is dropped and Excel is told to recalculate on open, since an
 * edited value can change formula results. A sheet whose rows or cells carry no addresses (a few
 * generators leave them out) can't be patched; then the whole workbook is written again from [Workbook].
 */
object XlsxEditor {

    /** [edits]: sheet index → cell key → the new cell (null clears it). Returns the new file's bytes. */
    fun apply(bytes: ByteArray, book: Workbook, edits: Map<Int, Map<Long, XCell?>>): ByteArray {
        val entries = XlsxReader.unzip(bytes)
        val pkg = XlsxReader.locate(entries)
        for ((index, cells) in edits) {
            if (cells.isEmpty()) continue
            val path = pkg.sheets.getOrNull(index)?.second ?: continue
            val xml = entries.getValue(path).toString(Charsets.UTF_8)
            val patched = patchSheet(xml, cells) ?: return rewrite(book, edits)
            entries[path] = patched.toByteArray(Charsets.UTF_8)
        }
        dropCalcChain(entries, pkg)
        entries[pkg.workbook] = recalcOnLoad(entries.getValue(pkg.workbook).toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // [Content_Types].xml first, as Office writes it.
            for (name in entries.keys.sortedBy { if (it == "[Content_Types].xml") 0 else 1 }) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(entries.getValue(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The cell typed into the editor: a formula ("=…"), a number, TRUE / FALSE, or text. */
    fun parseInput(text: String, old: XCell?): XCell? {
        val style = old?.style ?: CellStyle.DEFAULT
        if (text.isEmpty()) return if (old == null || style == CellStyle.DEFAULT) null else XCell(CellValue.Empty, style)
        if (text.startsWith("=") && text.length > 1) return XCell(CellValue.Empty, style, formula = text.substring(1))
        val t = text.trim()
        if (t.matches(PLAIN_NUMBER)) t.toDoubleOrNull()?.takeIf { !it.isInfinite() }?.let { return XCell(CellValue.Number(it), style) }
        if (t.endsWith('%') && t.dropLast(1).trim().matches(PLAIN_NUMBER)) t.dropLast(1).trim().toDoubleOrNull()?.let {
            return XCell(CellValue.Number(it / 100), if (style.numFmt == "General") style.copy(numFmt = if (t.contains('.')) "0.00%" else "0%") else style)
        }
        if (t.equals("TRUE", ignoreCase = true)) return XCell(CellValue.Bool(true), style)
        if (t.equals("FALSE", ignoreCase = true)) return XCell(CellValue.Bool(false), style)
        return XCell(CellValue.Text(text), style)
    }

    private val PLAIN_NUMBER = Regex("-?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")

    private fun rewrite(book: Workbook, edits: Map<Int, Map<Long, XCell?>>): ByteArray {
        val sheets = book.sheets.mapIndexed { i, s ->
            val changes = edits[i] ?: return@mapIndexed s
            val cells = HashMap(s.cells)
            for ((k, v) in changes) if (v == null) cells.remove(k) else cells[k] = v
            s.copy(cells = cells)
        }
        val tmp = java.io.File.createTempFile("xlsx", ".xlsx")
        return try {
            XlsxWriter.write(book.copy(sheets = sheets), tmp).readBytes()
        } finally {
            tmp.delete()
        }
    }

    /** Patched sheet XML, or null when its rows / cells have no `r` addresses. */
    internal fun patchSheet(xml: String, edits: Map<Long, XCell?>): String? {
        val open = Regex("<(\\w+:)?sheetData(\\s[^>]*)?(/?)>").find(xml) ?: return null
        val prefix = open.groupValues[1]
        val selfClosing = open.groupValues[3] == "/"
        val contentStart = open.range.last + 1
        val close = "</${prefix}sheetData>"
        val contentEnd = if (selfClosing) contentStart else xml.indexOf(close, contentStart).takeIf { it >= 0 } ?: return null
        val content = xml.substring(contentStart, contentEnd)
        val rowRe = Regex("<${Regex.escape(prefix)}row(\\s[^>]*?)?(/>|>(.*?)</${Regex.escape(prefix)}row>)", RegexOption.DOT_MATCHES_ALL)
        val rows = ArrayList<Triple<Int, String, String>>() // row index, attributes, inner content
        for (m in rowRe.findAll(content)) {
            val attrs = m.groupValues[1]
            val r = R_ATTR.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            rows += Triple(r - 1, attrs, m.groupValues[3])
        }
        val cellRe = Regex("<${Regex.escape(prefix)}c(\\s[^>]*?)?(/>|>(.*?)</${Regex.escape(prefix)}c>)", RegexOption.DOT_MATCHES_ALL)
        val byRow = edits.entries.groupBy { keyRow(it.key) }
        val outRows = rows.associateBy({ it.first }) { it }.toSortedMap()
        for ((r, changes) in byRow) {
            val existing = outRows[r]
            val cells = sortedMapOf<Int, String>()
            if (existing != null) {
                for (m in cellRe.findAll(existing.third)) {
                    val ref = R_ATTR.find(m.groupValues[1])?.groupValues?.get(1) ?: return null
                    val col = parseCellRef(ref)?.second ?: return null
                    cells[col] = m.value
                }
            }
            for ((key, cell) in changes) {
                val col = keyCol(key)
                val style = cells[col]?.let { S_ATTR.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                if (cell == null) cells.remove(col)
                else cells[col] = XlsxWriter.cellXml(cellName(r, col), cell, style, prefix)
            }
            // Spans are a hint for the row's first / last cell; drop them rather than keep a wrong one.
            val attrs = (existing?.second ?: " r=\"${r + 1}\"").replace(SPANS, "")
            // Anything after the cells (an extension list) stays at the end of the row.
            val tail = existing?.third?.let { cellRe.replace(it, "") }?.trim().orEmpty()
            outRows[r] = Triple(r, attrs, cells.values.joinToString("") + tail)
        }
        val newContent = outRows.values.joinToString("") { (_, attrs, inner) ->
            if (inner.isEmpty()) "<${prefix}row$attrs/>" else "<${prefix}row$attrs>$inner</${prefix}row>"
        }
        val head = if (selfClosing) xml.substring(0, open.range.first) + "<${prefix}sheetData${open.groupValues[2]}>" else xml.substring(0, contentStart)
        val rest = if (selfClosing) close + xml.substring(contentStart) else xml.substring(contentEnd)
        return head + newContent + rest
    }

    private fun dropCalcChain(entries: LinkedHashMap<String, ByteArray>, pkg: XlsxReader.Package) {
        val chain = pkg.rels.entries.firstOrNull { it.value.first.endsWith("/calcChain") } ?: return
        entries.remove(chain.value.second)
        val relsPath = XlsxReader.relsPath(pkg.workbook)
        entries[relsPath]?.let { bytes ->
            val xml = bytes.toString(Charsets.UTF_8)
            entries[relsPath] = xml.replace(Regex("<Relationship\\s[^>]*Id=\"${Regex.escape(chain.key)}\"[^>]*/>"), "").toByteArray(Charsets.UTF_8)
        }
        entries["[Content_Types].xml"]?.let { bytes ->
            val xml = bytes.toString(Charsets.UTF_8)
            entries["[Content_Types].xml"] = xml.replace(Regex("<Override\\s[^>]*PartName=\"/${Regex.escape(chain.value.second)}\"[^>]*/>"), "").toByteArray(Charsets.UTF_8)
        }
    }

    /** Sets `fullCalcOnLoad` so formulas that depend on edited cells show fresh results in Excel. */
    private fun recalcOnLoad(xml: String): String {
        val calc = Regex("<(\\w+:)?calcPr(\\s[^>]*?)?(/?)>").find(xml)
        if (calc != null) {
            if (calc.value.contains("fullCalcOnLoad")) return xml.replace(Regex("fullCalcOnLoad=\"[^\"]*\""), "fullCalcOnLoad=\"1\"")
            val insert = calc.range.first + 1 + calc.groupValues[1].length + "calcPr".length
            return xml.substring(0, insert) + " fullCalcOnLoad=\"1\"" + xml.substring(insert)
        }
        val prefix = Regex("<(\\w+:)?workbook[\\s>]").find(xml)?.groupValues?.get(1).orEmpty()
        // calcPr comes after definedNames and before these elements.
        val after = listOf("oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr", "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst")
            .mapNotNull { xml.indexOf("<$prefix$it").takeIf { i -> i >= 0 } }.minOrNull()
            ?: xml.lastIndexOf("</${prefix}workbook>").takeIf { it >= 0 } ?: return xml
        return xml.substring(0, after) + "<${prefix}calcPr fullCalcOnLoad=\"1\"/>" + xml.substring(after)
    }

    private val R_ATTR = Regex("\\sr=\"([^\"]*)\"")
    private val S_ATTR = Regex("\\ss=\"(\\d+)\"")
    private val SPANS = Regex("\\sspans=\"[^\"]*\"")
}
