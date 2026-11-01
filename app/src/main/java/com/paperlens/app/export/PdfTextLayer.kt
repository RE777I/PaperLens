package com.paperlens.app.export

import com.paperlens.app.ml.TextLine
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.util.Locale

/**
 * Hidden text over a scanned page, like a scanner app's searchable PDFs: each recognised word is written
 * in invisible text (rendering mode 3) exactly over its picture, so PDF readers can search, select and
 * copy it while the page still shows only the scan.
 *
 * The font is a "glyph-less" one, as OCR tools (Tesseract, OCRmyPDF) use: nothing is drawn, so it has
 * no glyphs to embed, and every character is written as its own Unicode value (Identity-H) with a
 * ToUnicode map back, so any script (Latin, Chinese, …) works without a large font file.
 */
class PdfTextLayer(private val doc: PDDocument) {

    private val fontName = COSName.getPDFName("FOcr")

    /** One shared font object for the whole document. */
    private val font: COSDictionary by lazy {
        val descriptor = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("FontDescriptor"))
            setName(COSName.getPDFName("FontName"), "GlyphLessFont")
            setInt(COSName.getPDFName("Flags"), 5)
            setItem(COSName.getPDFName("FontBBox"), numbers(0, 0, CHAR_WIDTH, 1000))
            setInt(COSName.getPDFName("ItalicAngle"), 0)
            setInt(COSName.getPDFName("Ascent"), 1000)
            setInt(COSName.getPDFName("Descent"), 0)
            setInt(COSName.getPDFName("CapHeight"), 1000)
            setInt(COSName.getPDFName("StemV"), 80)
        }
        val cidFont = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType2"))
            setName(COSName.getPDFName("BaseFont"), "GlyphLessFont")
            setItem(COSName.getPDFName("CIDSystemInfo"), COSDictionary().apply {
                setItem(COSName.getPDFName("Registry"), COSString("Adobe"))
                setItem(COSName.getPDFName("Ordering"), COSString("Identity"))
                setInt(COSName.getPDFName("Supplement"), 0)
            })
            setItem(COSName.getPDFName("FontDescriptor"), descriptor)
            setInt(COSName.getPDFName("DW"), CHAR_WIDTH)
            setItem(COSName.getPDFName("CIDToGIDMap"), COSName.getPDFName("Identity"))
        }
        COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("Type0"))
            setName(COSName.getPDFName("BaseFont"), "GlyphLessFont")
            setItem(COSName.getPDFName("Encoding"), COSName.getPDFName("Identity-H"))
            setItem(COSName.getPDFName("DescendantFonts"), COSArray().apply { add(cidFont) })
            setItem(COSName.getPDFName("ToUnicode"), stream(toUnicode()).cosObject)
        }
    }

    /**
     * Adds [words] (boxes in the pixels of a [imageW] × [imageH] picture) to [page], where that picture
     * is drawn at ([x], [y]) with size [w] × [h] in points (PDF origin bottom-left).
     */
    fun add(page: PDPage, words: List<TextLine>, imageW: Int, imageH: Int, x: Float, y: Float, w: Float, h: Float) {
        val ops = StringBuilder("BT 3 Tr\n")
        val sx = w / imageW
        val sy = h / imageH
        for (word in words) {
            // Characters outside the Basic Multilingual Plane (rare emoji, historic scripts) are left out.
            val text = word.text.filter { !it.isSurrogate() }
            if (text.isBlank()) continue
            val bw = word.box.width() * sx
            val bh = word.box.height() * sy
            if (bw <= 0f || bh <= 0f) continue
            val size = bh
            // Stretch the run so it spans the word's box; the trailing space separates words when copied.
            val scaling = 100f * bw / (text.length * size * CHAR_WIDTH / 1000f)
            val left = x + word.box.left * sx
            val baseline = y + h - word.box.bottom * sy
            val hex = (text + " ").map { String.format(Locale.ROOT, "%04X", it.code) }.joinToString("")
            ops.append(String.format(Locale.ROOT, "/FOcr %.2f Tf %.2f Tz 1 0 0 1 %.2f %.2f Tm <%s> Tj\n", size, scaling, left, baseline, hex))
        }
        ops.append("ET\n")
        // Font resource, then the text as an extra content stream after the page picture.
        val resources = page.resources ?: PDResources().also { page.resources = it }
        val fonts = resources.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary
            ?: COSDictionary().also { resources.cosObject.setItem(COSName.FONT, it) }
        fonts.setItem(fontName, font)
        val contents = COSArray()
        when (val existing = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
            is COSArray -> existing.forEach { contents.add(it) }
            null -> Unit
            else -> contents.add(existing)
        }
        contents.add(stream(ops.toString()).cosObject)
        page.cosObject.setItem(COSName.CONTENTS, contents)
    }

    private fun stream(text: String): PDStream = PDStream(doc).apply {
        createOutputStream().use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
    }

    /** Every 2-byte code maps back to the same Unicode value (in 256 ranges, one per high byte). */
    private fun toUnicode(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        for (chunk in 0 until 256 step 100) {
            val end = minOf(256, chunk + 100)
            append(end - chunk).append(" beginbfrange\n")
            for (hi in chunk until end) {
                val h = String.format(Locale.ROOT, "%02X", hi)
                append("<${h}00> <${h}FF> <${h}00>\n")
            }
            append("endbfrange\n")
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }

    private fun numbers(vararg v: Int) = COSArray().apply { v.forEach { add(COSInteger.get(it.toLong())) } }

    private companion object {
        /** Width of every character, in thousandths of the font size. */
        const val CHAR_WIDTH = 500
    }
}
