package com.paperlens.app.ml

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.paperlens.app.imaging.BitmapUtils
import android.graphics.Rect
import java.io.File

/** One recognised line of text and where it sits on the page image (in image pixels). */
data class TextLine(val text: String, val box: RectF)

/** A paragraph as OCR grouped it: its lines and the area they cover. */
data class TextBlock(val text: String, val box: RectF, val lines: List<TextLine>)

/** OCR that keeps positions: the basis for on-page text editing and table export. */
object TextLayout {

    suspend fun lines(context: Context, image: File, script: OcrScript): List<TextLine> =
        blocks(context, image, script).flatMap { it.lines }

    /**
     * Words (ML Kit elements) with their boxes, in the file's pixels, plus the file's size. For the
     * hidden text layer of exported PDFs.
     */
    suspend fun words(context: Context, image: File, script: OcrScript): Pair<List<TextLine>, Pair<Int, Int>> {
        val (text, map) = read(context, image, script)
        val words = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
            e.boundingBox?.let { TextLine(e.text, map(it)) }
        }
        return words to BitmapUtils.imageSize(image)
    }

    suspend fun blocks(context: Context, image: File, script: OcrScript): List<TextBlock> {
        val (text, map) = read(context, image, script)
        return text.textBlocks.mapNotNull { block ->
            val lines = block.lines.mapNotNull { line -> line.boundingBox?.let { TextLine(line.text, map(it)) } }
            if (lines.isEmpty()) return@mapNotNull null
            val box = RectF(lines[0].box).apply { lines.forEach { union(it.box) } }
            TextBlock(block.text, box, lines)
        }
    }

    /** OCR of [image] (any script, or the best one for AUTO) and the mapping from its boxes to the file's pixels. */
    private suspend fun read(context: Context, image: File, script: OcrScript): Pair<Text, (Rect) -> RectF> {
        val input = InputImage.fromFilePath(context, Uri.fromFile(image))
        // ML Kit reports boxes in the coordinates of the bitmap it decoded; map them onto the file's pixels.
        val (fileW, fileH) = BitmapUtils.imageSize(image)
        val sx = if (input.width > 0) fileW.toFloat() / input.width else 1f
        val sy = if (input.height > 0) fileH.toFloat() / input.height else 1f
        return Ocr.process(context, input, script) to { b -> RectF(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy) }
    }
}
