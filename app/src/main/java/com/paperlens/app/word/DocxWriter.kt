package com.paperlens.app.word

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Writes a [WordDocument] as a .docx (Office Open XML) that Word, Google Docs, WPS and LibreOffice open.
 * New documents get a minimal package; imported ones keep every original part except the main
 * document, so headers, footers, styles and settings survive editing.
 */
object DocxWriter {

    fun write(doc: WordDocument, file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { write(doc, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun write(doc: WordDocument, out: OutputStream) {
        val ctx = Context(doc)
        val body = StringBuilder()
        for (b in doc.blocks) ctx.block(body, b, inCell = false)
        // Word wants the body to end with a paragraph (and a table directly before sectPr is not allowed).
        if (doc.blocks.lastOrNull() !is WParagraph) body.append("<w:p/>")
        body.append(doc.source?.sectPrXml ?: sectPr(doc.page))
        val documentXml = XML_HEADER + "<w:document ${rootAttributes(doc.source)}><w:body>$body</w:body></w:document>"

        ZipOutputStream(out).use { zip ->
            val written = HashSet<String>()
            fun put(name: String, bytes: ByteArray) {
                if (!written.add(name)) return
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))

            val source = doc.source
            val main = source?.mainPart ?: "word/document.xml"
            val mainDir = main.substringBeforeLast('/', "")
            val relsPath = (if (mainDir.isEmpty()) "" else "$mainDir/") + "_rels/" + main.substringAfterLast('/') + ".rels"

            // Content types: the original ones plus picture extensions we use.
            val newNumbering = source != null && ctx.usesNumbering && source.entries.keys.none { it.endsWith("numbering.xml") }
            var types = source?.entries?.get("[Content_Types].xml")?.toString(Charsets.UTF_8) ?: CONTENT_TYPES
            if (newNumbering) {
                val part = "/" + (if (mainDir.isEmpty()) "" else "$mainDir/") + "numbering.xml"
                types = types.replace("</Types>", "<Override PartName=\"$part\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>")
            }
            put("[Content_Types].xml", withDefaults(types, ctx.mediaFiles.keys.map { it.substringAfterLast('.') }.toSet()))

            // Relationships of the main part: the original ones (styles, headers…) plus our pictures.
            val baseRels = source?.entries?.get(relsPath)?.toString(Charsets.UTF_8)
                ?: (XML_HEADER + "<Relationships xmlns=\"$REL_NS\">" + NEW_DOC_RELS + "</Relationships>")
            var rels = addRelationships(baseRels, ctx.mediaFiles.map { (name, id) -> id to "media/${name}" })
            if (newNumbering && !rels.contains("rIdSnapNumbering")) {
                rels = rels.replace(
                    "</Relationships>",
                    "<Relationship Id=\"rIdSnapNumbering\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/></Relationships>",
                )
            }
            put(relsPath, rels)
            put(main, documentXml)
            for ((name, _) in ctx.mediaFiles) put("word/media/$name", ctx.mediaBytes.getValue(name))

            if (source == null) {
                put("_rels/.rels", PACKAGE_RELS)
                put("word/styles.xml", STYLES)
                put("word/settings.xml", SETTINGS)
                put("docProps/core.xml", CORE)
                put("docProps/app.xml", APP)
                put("word/numbering.xml", NUMBERING)
            } else {
                // New bullets / numbering in an imported file use our list definitions (numIds 9001/9002),
                // added to the file's numbering part, or as a new part when it has none.
                val numberingPart = source.entries.keys.firstOrNull { it.endsWith("numbering.xml") }
                if (ctx.usesNumbering) {
                    if (numberingPart != null) {
                        put(numberingPart, withOurLists(source.entries.getValue(numberingPart).toString(Charsets.UTF_8)))
                    } else {
                        put((if (mainDir.isEmpty()) "" else "$mainDir/") + "numbering.xml", NUMBERING)
                    }
                }
                for ((name, bytes) in source.entries) {
                    if (name == main || name == relsPath || name == "[Content_Types].xml") continue
                    put(name, bytes)
                }
            }
        }
    }

    // ---- body ------------------------------------------------------------------------------------

    private class Context(val doc: WordDocument) {
        /** File name in word/media -> relationship id. */
        val mediaFiles = LinkedHashMap<String, String>()
        val mediaBytes = HashMap<String, ByteArray>()
        private val keyToFile = HashMap<String, String>()
        var usesNumbering = false
        private var drawingId = 1

        fun relFor(key: String): String? {
            keyToFile[key]?.let { return mediaFiles[it] }
            val media = doc.media[key] ?: return null
            val n = mediaFiles.size + 1
            val name = "snap_image$n.${media.ext}"
            val id = "rIdSnapImg$n"
            keyToFile[key] = name
            mediaFiles[name] = id
            mediaBytes[name] = media.bytes
            return id
        }

        fun nextDrawingId() = drawingId++

        fun block(sb: StringBuilder, b: WBlock, inCell: Boolean) {
            when (b) {
                is WParagraph -> paragraph(sb, b)
                is WTable -> table(sb, b)
                WPageBreak -> sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }

        fun paragraph(sb: StringBuilder, p: WParagraph) {
            sb.append("<w:p><w:pPr>")
            p.styleId?.let { sb.append("<w:pStyle w:val=\"").append(esc(it)).append("\"/>") }
            p.list?.let { l ->
                usesNumbering = true
                val numId = l.numId ?: if (l.numbered) NUM_ID_DECIMAL else NUM_ID_BULLET
                sb.append("<w:numPr><w:ilvl w:val=\"${l.level.coerceIn(0, 8)}\"/><w:numId w:val=\"$numId\"/></w:numPr>")
            }
            p.borderBottom?.let { b ->
                sb.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"${b.size}\" w:space=\"1\" w:color=\"${b.color?.let(::hex) ?: "auto"}\"/></w:pBdr>")
            }
            p.shading?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
            if (p.tabs.isNotEmpty()) {
                sb.append("<w:tabs>")
                for (t in p.tabs.sortedBy { it.position }) {
                    val kind = when (t.kind) {
                        TabKind.LEFT -> "left"
                        TabKind.CENTER -> "center"
                        TabKind.RIGHT -> "right"
                        TabKind.DECIMAL -> "decimal"
                    }
                    sb.append("<w:tab w:val=\"$kind\" w:pos=\"${t.position}\"/>")
                }
                sb.append("</w:tabs>")
            }
            sb.append("<w:spacing w:before=\"${p.spaceBefore.coerceAtLeast(0)}\" w:after=\"${p.spaceAfter.coerceAtLeast(0)}\"")
            p.line?.let { line ->
                val rule = when (p.lineRule) {
                    LineRule.AUTO -> "auto"
                    LineRule.EXACT -> "exact"
                    LineRule.AT_LEAST -> "atLeast"
                }
                sb.append(" w:line=\"$line\" w:lineRule=\"$rule\"")
            }
            sb.append("/>")
            if (p.indentLeft != 0 || p.indentRight != 0 || p.indentFirst != 0) {
                sb.append("<w:ind w:left=\"${p.indentLeft}\" w:right=\"${p.indentRight}\"")
                if (p.indentFirst >= 0) sb.append(" w:firstLine=\"${p.indentFirst}\"") else sb.append(" w:hanging=\"${-p.indentFirst}\"")
                sb.append("/>")
            }
            val jc = when (p.align) {
                WAlign.LEFT -> null
                WAlign.CENTER -> "center"
                WAlign.RIGHT -> "right"
                WAlign.JUSTIFY -> "both"
            }
            jc?.let { sb.append("<w:jc w:val=\"$it\"/>") }
            // Paragraph mark formatting: an empty paragraph keeps the size of its text.
            sb.append("<w:rPr>").append(runProps(p.firstStyle)).append("</w:rPr>")
            sb.append("</w:pPr>")
            for (r in p.runs) run(sb, r)
            sb.append("</w:p>")
        }

        fun run(sb: StringBuilder, r: WRun) {
            r.image?.let { image(sb, it); return }
            if (r.text.isEmpty()) return
            sb.append("<w:r><w:rPr>").append(runProps(r.style)).append("</w:rPr>")
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    sb.append("<w:t xml:space=\"preserve\">").append(esc(text.toString())).append("</w:t>")
                    text.setLength(0)
                }
            }
            for (ch in r.text) {
                when (ch) {
                    '\t' -> { flush(); sb.append("<w:tab/>") }
                    '\n' -> { flush(); sb.append("<w:br/>") }
                    '\r' -> Unit
                    else -> text.append(ch)
                }
            }
            flush()
            sb.append("</w:r>")
        }

        fun image(sb: StringBuilder, img: WImage) {
            val rel = relFor(img.mediaKey) ?: return
            val id = nextDrawingId()
            val cx = img.widthEmu.coerceAtLeast(1)
            val cy = img.heightEmu.coerceAtLeast(1)
            val graphic = "<a:graphic xmlns:a=\"$A_NS\"><a:graphicData uri=\"$PIC_URI\"><pic:pic xmlns:pic=\"$PIC_URI\">" +
                "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"Picture $id\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"$rel\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>"
            sb.append("<w:r><w:drawing>")
            val f = img.float
            if (f == null) {
                sb.append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>")
                    .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:docPr id=\"$id\" name=\"Picture $id\"/>")
                    .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$A_NS\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                    .append(graphic).append("</wp:inline>")
            } else {
                sb.append("<wp:anchor distT=\"0\" distB=\"0\" distL=\"114300\" distR=\"114300\" simplePos=\"0\" ")
                    .append("relativeHeight=\"${251658240 + id}\" behindDoc=\"${if (f.behindText) 1 else 0}\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">")
                    .append("<wp:simplePos x=\"0\" y=\"0\"/>")
                    .append("<wp:positionH relativeFrom=\"${f.relativeH}\"><wp:posOffset>${f.x}</wp:posOffset></wp:positionH>")
                    .append("<wp:positionV relativeFrom=\"${f.relativeV}\"><wp:posOffset>${f.y}</wp:posOffset></wp:positionV>")
                    .append("<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
                    .append(if (f.behindText) "<wp:wrapNone/>" else "<wp:wrapSquare wrapText=\"bothSides\"/>")
                    .append("<wp:docPr id=\"$id\" name=\"Picture $id\"/><wp:cNvGraphicFramePr/>")
                    .append(graphic).append("</wp:anchor>")
            }
            sb.append("</w:drawing></w:r>")
        }

        fun table(sb: StringBuilder, t: WTable) {
            sb.append("<w:tbl><w:tblPr>")
            sb.append("<w:tblW w:w=\"${t.width}\" w:type=\"dxa\"/>")
            when (t.align) {
                WAlign.CENTER -> sb.append("<w:jc w:val=\"center\"/>")
                WAlign.RIGHT -> sb.append("<w:jc w:val=\"right\"/>")
                else -> Unit
            }
            if (t.indent != 0 && t.align == WAlign.LEFT) sb.append("<w:tblInd w:w=\"${t.indent}\" w:type=\"dxa\"/>")
            sb.append("<w:tblBorders>")
            border(sb, "top", t.borders.top)
            border(sb, "left", t.borders.left)
            border(sb, "bottom", t.borders.bottom)
            border(sb, "right", t.borders.right)
            border(sb, "insideH", t.borders.insideH)
            border(sb, "insideV", t.borders.insideV)
            sb.append("</w:tblBorders>")
            sb.append("<w:tblLayout w:type=\"fixed\"/>")
            sb.append("<w:tblCellMar><w:left w:w=\"${t.cellMarginH}\" w:type=\"dxa\"/><w:right w:w=\"${t.cellMarginH}\" w:type=\"dxa\"/></w:tblCellMar>")
            sb.append("<w:tblLook w:val=\"04A0\" w:firstRow=\"0\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"1\" w:noVBand=\"1\"/>")
            sb.append("</w:tblPr><w:tblGrid>")
            for (w in t.grid) sb.append("<w:gridCol w:w=\"$w\"/>")
            sb.append("</w:tblGrid>")
            for (row in t.rows) {
                sb.append("<w:tr>")
                row.height?.let { h ->
                    sb.append("<w:trPr><w:trHeight w:val=\"$h\" w:hRule=\"${if (row.exactHeight) "exact" else "atLeast"}\"/></w:trPr>")
                }
                val starts = row.gridStarts()
                row.cells.forEachIndexed { i, cell ->
                    val span = cell.span.coerceAtLeast(1)
                    val width = (starts[i] until starts[i] + span).sumOf { t.grid.getOrElse(it) { 0 } }
                    sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
                    if (span > 1) sb.append("<w:gridSpan w:val=\"$span\"/>")
                    when (cell.vMerge) {
                        VMerge.RESTART -> sb.append("<w:vMerge w:val=\"restart\"/>")
                        VMerge.CONTINUE -> sb.append("<w:vMerge/>")
                        VMerge.NONE -> Unit
                    }
                    cell.borders?.let { cb ->
                        sb.append("<w:tcBorders>")
                        cellBorder(sb, "top", cb.top, cb.noTop)
                        cellBorder(sb, "left", cb.left, cb.noLeft)
                        cellBorder(sb, "bottom", cb.bottom, cb.noBottom)
                        cellBorder(sb, "right", cb.right, cb.noRight)
                        sb.append("</w:tcBorders>")
                    }
                    cell.shading?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                    when (cell.vAlign) {
                        VAlign.CENTER -> sb.append("<w:vAlign w:val=\"center\"/>")
                        VAlign.BOTTOM -> sb.append("<w:vAlign w:val=\"bottom\"/>")
                        VAlign.TOP -> Unit
                    }
                    sb.append("</w:tcPr>")
                    for (b in cell.blocks) block(sb, b, inCell = true)
                    // A cell must end with a paragraph.
                    if (cell.blocks.lastOrNull() !is WParagraph) sb.append("<w:p/>")
                    sb.append("</w:tc>")
                }
                sb.append("</w:tr>")
            }
            sb.append("</w:tbl>")
        }

        private fun border(sb: StringBuilder, side: String, b: WBorder?) {
            if (b == null) sb.append("<w:$side w:val=\"nil\"/>")
            else sb.append("<w:$side w:val=\"single\" w:sz=\"${b.size}\" w:space=\"0\" w:color=\"${b.color?.let(::hex) ?: "auto"}\"/>")
        }

        private fun cellBorder(sb: StringBuilder, side: String, b: WBorder?, off: Boolean) {
            when {
                off -> sb.append("<w:$side w:val=\"nil\"/>")
                b != null -> border(sb, side, b)
            }
        }
    }

    private fun runProps(s: RunStyle): String = buildString {
        s.font?.let { append("<w:rFonts w:ascii=\"${esc(it)}\" w:hAnsi=\"${esc(it)}\" w:cs=\"${esc(it)}\"/>") }
        if (s.bold) append("<w:b/><w:bCs/>")
        if (s.italic) append("<w:i/><w:iCs/>")
        if (s.strike) append("<w:strike/>")
        s.color?.let { append("<w:color w:val=\"${hex(it)}\"/>") }
        val half = (s.size * 2).roundToInt().coerceIn(2, 3276)
        append("<w:sz w:val=\"$half\"/><w:szCs w:val=\"$half\"/>")
        if (s.underline) append("<w:u w:val=\"single\"/>")
        s.highlight?.let { append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
    }

    private fun sectPr(p: PageSetup): String =
        "<w:sectPr><w:pgSz w:w=\"${p.width}\" w:h=\"${p.height}\"" + (if (p.width > p.height) " w:orient=\"landscape\"" else "") + "/>" +
            "<w:pgMar w:top=\"${p.marginTop}\" w:right=\"${p.marginRight}\" w:bottom=\"${p.marginBottom}\" w:left=\"${p.marginLeft}\" " +
            "w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/><w:cols w:space=\"708\"/></w:sectPr>"

    /** Our namespaces plus any others the original root declared (and its mc:Ignorable list). */
    private fun rootAttributes(source: SourcePackage?): String {
        val extra = source?.rootNamespaces.orEmpty().split(' ').filter { attr ->
            attr.isNotBlank() && OUR_PREFIXES.none { attr.startsWith("xmlns:$it=") }
        }
        return (listOf(NAMESPACES) + extra).joinToString(" ")
    }

    private val OUR_PREFIXES = listOf("w", "r", "wp", "a", "pic")

    /** The file's numbering part with our bullet / number definitions added (abstractNums before nums). */
    private fun withOurLists(xml: String): String {
        if (xml.contains("w:numId=\"$NUM_ID_BULLET\"")) return xml
        val abstracts = NUMBERING.substringAfter("<w:numbering xmlns:w=\"$W_NS\">").substringBefore("<w:num w:numId")
        val nums = "<w:num w:numId" + NUMBERING.substringAfter("<w:num w:numId").substringBefore("</w:numbering>")
        val firstNum = Regex("<w:num[ >]").find(xml)?.range?.first
        val withAbstracts = if (firstNum != null) xml.substring(0, firstNum) + abstracts + xml.substring(firstNum)
        else xml.replace("</w:numbering>", "$abstracts</w:numbering>")
        return withAbstracts.replace("</w:numbering>", "$nums</w:numbering>")
    }

    /** Adds <Default> entries for picture extensions the content types do not list yet. */
    private fun withDefaults(types: String, exts: Set<String>): String {
        var out = types
        for (ext in exts + setOf("png", "jpeg", "jpg")) {
            if (Regex("Extension=\"${Regex.escape(ext)}\"", RegexOption.IGNORE_CASE).containsMatchIn(out)) continue
            val mime = when (ext.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            out = out.replaceFirst("<Default ", "<Default Extension=\"$ext\" ContentType=\"$mime\"/><Default ")
        }
        return out
    }

    private fun addRelationships(rels: String, images: List<Pair<String, String>>): String {
        if (images.isEmpty()) return rels
        val add = images.joinToString("") { (id, target) ->
            "<Relationship Id=\"$id\" Type=\"$IMAGE_REL\" Target=\"${esc(target)}\"/>"
        }
        // Drop earlier pictures of ours (re-saves renumber them).
        val cleaned = rels.replace(Regex("<Relationship[^>]*Id=\"rIdSnapImg\\d+\"[^>]*/>"), "")
        val end = cleaned.lastIndexOf("</Relationships>")
        return if (end < 0) cleaned else cleaned.substring(0, end) + add + cleaned.substring(end)
    }

    fun hex(rgb: Int): String = String.format("%06X", rgb and 0xFFFFFF)

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' || c == '\n' || c >= ' ' -> append(c)
                // other control characters are invalid in XML 1.0 and dropped
            }
        }
    }

    // ---- fixed parts -------------------------------------------------------------------------------

    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC_URI = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val IMAGE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
    private const val NAMESPACES = "xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\" xmlns:wp=\"$WP_NS\" xmlns:a=\"$A_NS\" xmlns:pic=\"$PIC_URI\""

    const val NUM_ID_BULLET = 9001
    const val NUM_ID_DECIMAL = 9002

    private const val CONTENT_TYPES = XML_HEADER +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private const val PACKAGE_RELS = XML_HEADER +
        "<Relationships xmlns=\"$REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private const val NEW_DOC_RELS =
        "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rIdSettings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>" +
            "<Relationship Id=\"rIdNumbering\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>"

    private const val STYLES = XML_HEADER +
        "<w:styles xmlns:w=\"$W_NS\"><w:docDefaults><w:rPrDefault><w:rPr>" +
        "<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"SimSun\" w:cs=\"Arial\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>" +
        "<w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:rPr><w:b/><w:sz w:val=\"48\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"24\"/></w:rPr></w:style>" +
        "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/>" +
        "<w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/>" +
        "<w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
        "</w:styles>"

    private const val SETTINGS = XML_HEADER +
        "<w:settings xmlns:w=\"$W_NS\"><w:defaultTabStop w:val=\"720\"/><w:characterSpacingControl w:val=\"doNotCompress\"/>" +
        "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>"

    private val NUMBERING = XML_HEADER +
        "<w:numbering xmlns:w=\"$W_NS\">" +
        "<w:abstractNum w:abstractNumId=\"9001\">" + (0..8).joinToString("") { l ->
            "<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"${if (l % 2 == 0) "•" else "◦"}\"/>" +
                "<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${360 * (l + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        } + "</w:abstractNum>" +
        "<w:abstractNum w:abstractNumId=\"9002\">" + (0..8).joinToString("") { l ->
            val fmt = listOf("decimal", "lowerLetter", "lowerRoman")[l % 3]
            "<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"%${l + 1}.\"/>" +
                "<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${360 * (l + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        } + "</w:abstractNum>" +
        "<w:num w:numId=\"$NUM_ID_BULLET\"><w:abstractNumId w:val=\"9001\"/></w:num>" +
        "<w:num w:numId=\"$NUM_ID_DECIMAL\"><w:abstractNumId w:val=\"9002\"/></w:num>" +
        "</w:numbering>"

    private const val CORE = XML_HEADER +
        "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>PaperLens</dc:creator></cp:coreProperties>"

    private const val APP = XML_HEADER +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>PaperLens</Application></Properties>"
}
