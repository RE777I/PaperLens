package com.paperlens.app.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal Office Open XML spreadsheet: one sheet per scanned page, inline strings, and numbers
 * stored as numbers (so "12.50" can be summed in Excel).
 */
object XlsxWriter {

    fun write(out: File, sheets: List<Pair<String, List<List<String>>>>): File {
        val names = mutableListOf<String>()
        sheets.forEachIndexed { i, (name, _) ->
            val clean = sheetName(name)
            names += if (clean in names) "${clean.take(27)} (${i + 1})" else clean
        }
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
                    sheets.indices.joinToString("") {
                        "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" " +
                            "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    } +
                    "</Types>",
            )
            put(
                "_rels/.rels",
                HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>",
            )
            put(
                "xl/workbook.xml",
                HEADER + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                    names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
                    "</sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    sheets.indices.joinToString("") {
                        "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                            "Target=\"worksheets/sheet${it + 1}.xml\"/>"
                    } +
                    "</Relationships>",
            )
            sheets.forEachIndexed { i, (_, rows) -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(rows)) }
        }
        return out
    }

    private fun sheetXml(rows: List<List<String>>): String = buildString {
        append(HEADER)
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        rows.forEachIndexed { r, cells ->
            append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, raw ->
                val ref = column(c) + (r + 1)
                val number = raw.trim().replace(",", "").toDoubleOrNull()
                if (number != null && raw.trim().matches(NUMERIC)) {
                    append("<c r=\"$ref\"><v>$number</v></c>")
                } else {
                    append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(raw)}</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** 0 → A, 25 → Z, 26 → AA … */
    private fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    // Excel sheet names: max 31 chars, no []:*?/\ (uniqueness handled by the caller).
    private fun sheetName(name: String): String =
        name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifBlank { "Sheet" }

    private fun esc(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\t' || ch == '\n' || ch >= ' ' -> append(ch)
        }
    }

    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private val NUMERIC = Regex("^-?[0-9][0-9,]*(\\.[0-9]+)?$")
}
