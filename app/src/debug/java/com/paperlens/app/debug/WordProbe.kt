package com.paperlens.app.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.ml.Ocr
import com.paperlens.app.ml.OcrScript
import com.paperlens.app.word.DocxReader
import com.paperlens.app.word.DocxWriter
import com.paperlens.app.word.PageBuilder
import com.paperlens.app.word.WBlock
import com.paperlens.app.word.WPageBreak
import com.paperlens.app.word.WParagraph
import com.paperlens.app.word.WTable
import com.paperlens.app.word.WordDocument
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Debug builds only: converts one page photo to Word and writes `<name>.docx`, `<name>_debug.jpg`
 * (what the analysis found) and `<name>.txt` (the document structure, word measurements and a
 * read-back check), for tuning the converter off the device.
 */
internal object WordProbe {

    suspend fun convert(context: Context, file: File, outDir: File, script: OcrScript) {
        val bitmap = BitmapUtils.decode(file, 2400)
        val text = Ocr.recognizeText(context, bitmap, script)
        val scanner = BarcodeScanning.getClient()
        val codes = try {
            scanner.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { it.boundingBox?.let(::RectF) }
        } finally {
            scanner.close()
        }
        val result = PageBuilder(bitmap, text, codes, "p1_", null).build()
        val doc = WordDocument(result.blocks, result.setup, result.media)
        val name = file.nameWithoutExtension
        val docx = File(outDir, "$name.docx")
        DocxWriter.write(doc, docx)

        // Overlay: words (red = bold, blue = regular), table cells (green), shaded-row tables (orange), pictures (magenta).
        val overlay = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(overlay)
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        val d = result.debug
        for (wd in d.words) { stroke.color = if (wd.bold) Color.RED else Color.BLUE; c.drawRect(wd.box, stroke) }
        stroke.strokeWidth = 5f
        stroke.color = Color.rgb(0, 170, 0); d.gridCells.forEach { c.drawRect(it, stroke) }
        stroke.color = Color.rgb(255, 140, 0); d.bandTables.forEach { c.drawRect(it, stroke) }
        stroke.color = Color.MAGENTA; d.pictures.forEach { c.drawRect(it, stroke) }
        stroke.color = Color.CYAN; stroke.strokeWidth = 3f
        for (r in d.rules) for (sp in r.spans) {
            if (r.horizontal) c.drawLine(sp[0], r.at(sp[0]) + 6, sp[1], r.at(sp[1]) + 6, stroke)
            else c.drawLine(r.at(sp[0]) + 6, sp[0], r.at(sp[1]) + 6, sp[1], stroke)
        }
        BitmapUtils.saveJpeg(BitmapUtils.scaleDown(overlay, 1400), File(outDir, "${name}_debug.jpg"), 85)

        val sb = StringBuilder()
        sb.append("page ${bitmap.width}x${bitmap.height} setup ${result.setup}\n")
        dump(sb, result.blocks, "")
        sb.append("\ngrid rules:\n")
        for (r in d.gridRules) {
            sb.append(if (r.horizontal) "H" else "V").append(" a=${r.a.toInt()} b=${"%.4f".format(r.b)} ${r.start.toInt()}..${r.end.toInt()} spans=")
                .append(r.spans.sortedBy { it[0] }.joinToString(",") { "${it[0].toInt()}-${it[1].toInt()}" }).append('\n')
        }
        sb.append("\nwords (text em weight bold color conf):\n")
        for (wd in d.words.sortedWith(compareBy({ it.box.top.toInt() / 20 }, { it.box.left }))) {
            sb.append(String.format(java.util.Locale.ROOT, "%-24s em=%5.1f wt=%.3f %s %s c=%.2f box=%s\n", wd.text.take(24), wd.em, wd.weight, if (wd.bold) "B" else "-",
                wd.color?.let { String.format("%06X", it) } ?: "auto", wd.confidence, wd.box.toShortString()))
        }
        val back = DocxReader.read(docx)
        sb.append("\nread back: ${back.blocks.size} blocks, ${back.media.size} pictures, text chars ${back.blocks.filterIsInstance<WParagraph>().sumOf { it.text.length }}\n")
        File(outDir, "$name.txt").writeText(sb.toString())
    }

    private fun dump(sb: StringBuilder, blocks: List<WBlock>, indent: String) {
        for (b in blocks) {
            when (b) {
                is WParagraph -> {
                    sb.append(indent).append("P before=${b.spaceBefore} ind=${b.indentLeft} ${b.align} line=${b.line}/${b.lineRule}")
                    if (b.tabs.isNotEmpty()) sb.append(" tabs=").append(b.tabs.joinToString(",") { "${it.position}${it.kind.name.first()}" })
                    b.borderBottom?.let { sb.append(" border") }
                    sb.append(" | ")
                    for (r in b.runs) {
                        if (r.image != null) {
                            sb.append("[IMG ${r.image.widthEmu / 635}x${r.image.heightEmu / 635}tw${if (r.image.float != null) " float" else ""}]")
                        } else {
                            sb.append("[${r.style.size}${if (r.style.bold) "B" else ""}${r.style.color?.let { " #" + String.format("%06X", it) } ?: ""}]")
                                .append(r.text.replace("\n", "⏎").replace("\t", "⇥"))
                        }
                    }
                    sb.append('\n')
                }
                is WTable -> {
                    sb.append(indent).append("TABLE grid=${b.grid} indent=${b.indent} borders=${b.borders.top != null}\n")
                    b.rows.forEachIndexed { r, row ->
                        sb.append(indent).append("  row $r h=${row.height}:\n")
                        row.cells.forEach { cell ->
                            sb.append(indent).append("    cell span=${cell.span} v=${cell.vMerge} ${cell.vAlign} shade=${cell.shading?.let { String.format("%06X", it) }}\n")
                            dump(sb, cell.blocks, "$indent      ")
                        }
                    }
                }
                WPageBreak -> sb.append(indent).append("PAGE BREAK\n")
            }
        }
    }
}
