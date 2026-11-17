package com.paperlens.app.word

import com.paperlens.app.word.DocxXml.Numbering
import com.paperlens.app.word.DocxXml.StyleSheet
import com.paperlens.app.word.DocxXml.parser
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
        val parser = DocxBodyParser(rels, entries, styles, numbering)
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
}
