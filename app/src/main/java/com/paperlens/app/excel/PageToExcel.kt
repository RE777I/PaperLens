package com.paperlens.app.excel

import android.content.Context
import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.data.PageEntity
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.ml.Ocr
import com.paperlens.app.ml.OcrScript
import com.paperlens.app.word.BandTable
import com.paperlens.app.word.GridTable
import com.paperlens.app.word.OcrWord
import com.paperlens.app.word.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "To Excel": turns photographed tables into a workbook, one sheet per page. Tables drawn with lines keep
 * their grid, merged cells, shading and borders (found the same way as for To Word); tables of shaded rows
 * and borderless tables get their columns from the gaps the text leaves; text around tables goes into
 * rows of its own. Cell text keeps its line breaks, header rows stay bold, and amounts become numbers
 * that add up in Excel while showing as printed.
 */
object PageToExcel {

    /** Pages are analysed at this size (long side, px), like To Word. */
    private const val WORK = 2400

    /** Pages as stored: the unfiltered scan and its rotation (filters whiten faint lines and tint shading). */
    suspend fun convertPages(context: Context, pages: List<PageEntity>, script: OcrScript): Workbook =
        convert(context, pages.map { File(it.originalPath) }, script, pages.map { it.rotation })

    suspend fun convert(context: Context, pages: List<File>, script: OcrScript, rotations: List<Int> = emptyList()): Workbook {
        val sheets = pages.mapIndexed { index, file ->
            val bitmap = withContext(Dispatchers.IO) {
                BitmapUtils.decode(file, WORK).let { b -> rotations.getOrNull(index)?.takeIf { it % 360 != 0 }?.let { BitmapUtils.rotate(b, it) } ?: b }
            }
            val text = Ocr.recognizeText(context, bitmap, script)
            withContext(Dispatchers.Default) { SheetBuilder(bitmap, text, S.str(R.string.page_x, index + 1)).build().sheet }
        }
        return Workbook(sheets.ifEmpty { listOf(Sheet(S.str(R.string.page_x, 1), emptyMap())) })
    }
}

/** One converted page, plus what the analysis found (for the debug probe's overlay). */
internal class SheetResult(val sheet: Sheet, val debug: SheetDebug)

internal class SheetDebug(
    val words: List<OcrWord>,
    val grids: List<GridTable>,
    val bands: List<BandTable>,
    /** Borderless tables and the x positions of their column breaks. */
    val loose: List<Pair<RectF, List<Float>>>,
    val rules: List<Rule>,
    /** The page was read as a negative (light text on a dark board). */
    val inverted: Boolean,
    val notes: List<String>,
)
