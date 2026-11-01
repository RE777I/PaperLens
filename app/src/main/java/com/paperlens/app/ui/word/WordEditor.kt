package com.paperlens.app.ui.word

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.paperlens.app.word.BlockPath
import com.paperlens.app.word.RunStyle
import com.paperlens.app.word.WAlign
import com.paperlens.app.word.WImage
import com.paperlens.app.word.WMedia
import com.paperlens.app.word.WPageBreak
import com.paperlens.app.word.WParagraph
import com.paperlens.app.word.WRun
import com.paperlens.app.word.WTable
import com.paperlens.app.word.WordDocument
import com.paperlens.app.word.WordEditing
import com.paperlens.app.word.blockAt
import com.paperlens.app.word.gridStarts
import com.paperlens.app.word.insertBlock
import com.paperlens.app.word.removeBlock
import com.paperlens.app.word.replaceBlock
import kotlin.math.roundToLong

/**
 * Editing state of an open Word document. Changes stay in memory ([doc]) until saved ([saved]);
 * [dirty] says whether there is something to save. One paragraph at a time is being typed in ([focus],
 * with its text and selection in [value]); formatting applies to the selection there, or to the whole
 * paragraph when nothing is selected.
 */
internal class WordEditor(initial: WordDocument) {
    var saved by mutableStateOf(initial)
    var doc by mutableStateOf(initial)
        private set
    var editing by mutableStateOf(false)
    var focus by mutableStateOf<BlockPath?>(null)
        private set
    var value by mutableStateOf(TextFieldValue(""))
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val dirty: Boolean get() = doc !== saved

    private data class Snap(val doc: WordDocument, val focus: BlockPath?, val selection: TextRange)

    private val undoStack = ArrayList<Snap>()
    private val redoStack = ArrayList<Snap>()
    private var lastKey: String? = null

    val focusedParagraph: WParagraph? get() = focus?.let { doc.blockAt(it) as? WParagraph }

    /** The table the cursor is in, with the row, the cell index in that row and its grid column. */
    val focusedTable: TableSpot?
        get() {
            val tablePath = focus?.table ?: return null
            val t = doc.blockAt(tablePath) as? WTable ?: return null
            val f = focus ?: return null
            val row = t.rows.getOrNull(f.row) ?: return null
            if (f.cell >= row.cells.size) return null
            return TableSpot(tablePath, t, f.row, f.cell, row.gridStarts()[f.cell])
        }

    class TableSpot(val path: BlockPath, val table: WTable, val row: Int, val cell: Int, val gridCol: Int)

    fun startEditing() {
        editing = true
    }

    fun stopEditing() {
        editing = false
        focus = null
        lastKey = null
    }

    fun focusParagraph(path: BlockPath, offset: Int) {
        val p = doc.blockAt(path) as? WParagraph ?: return
        val text = WordEditing.text(p)
        focus = path
        value = TextFieldValue(text, TextRange(offset.coerceIn(0, text.length)))
        lastKey = null
    }

    fun onValueChange(v: TextFieldValue) {
        val path = focus ?: return
        val p = doc.blockAt(path) as? WParagraph ?: return
        if (v.text != value.text) {
            change(doc.replaceBlock(path, WordEditing.edit(p, value.text, v.text)), key = "type $path")
        }
        value = TextFieldValue(v.text, v.selection, v.composition)
    }

    /** Records an undo step (typing in one paragraph counts as one step) and applies [newDoc]. */
    fun change(newDoc: WordDocument, key: String? = null) {
        if (key == null || key != lastKey) {
            undoStack += Snap(doc, focus, value.selection)
            if (undoStack.size > 200) undoStack.removeAt(0)
        }
        lastKey = key
        redoStack.clear()
        doc = newDoc
        flags()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack += Snap(doc, focus, value.selection)
        restore(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack += Snap(doc, focus, value.selection)
        restore(s)
    }

    /** After saving, or to throw changes away. */
    fun reset(to: WordDocument) {
        undoStack.clear(); redoStack.clear()
        saved = to
        doc = to
        focus = null
        lastKey = null
        flags()
    }

    private fun restore(s: Snap) {
        doc = s.doc
        lastKey = null
        val p = s.focus?.let { doc.blockAt(it) as? WParagraph }
        if (p != null) {
            val text = WordEditing.text(p)
            focus = s.focus
            value = TextFieldValue(text, TextRange(s.selection.start.coerceIn(0, text.length), s.selection.end.coerceIn(0, text.length)))
        } else {
            focus = null
        }
        flags()
    }

    private fun flags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    // ---- character formatting ------------------------------------------------------------------

    private fun selection(): Pair<Int, Int> = value.selection.min to value.selection.max

    /** Whether the selection (or the paragraph) is entirely [test]. */
    fun selectionIs(test: (RunStyle) -> Boolean): Boolean {
        val p = focusedParagraph ?: return false
        val (s, e) = selection()
        return WordEditing.all(p, s, e, test)
    }

    private fun restyle(transform: (RunStyle) -> RunStyle) {
        val path = focus ?: return
        val p = focusedParagraph ?: return
        val (s, e) = selection()
        change(doc.replaceBlock(path, WordEditing.style(p, s, e, transform)))
    }

    private fun toggle(test: (RunStyle) -> Boolean, set: RunStyle.(Boolean) -> RunStyle) {
        val on = selectionIs(test)
        restyle { it.set(!on) }
    }

    fun bold() = toggle({ it.bold }) { copy(bold = it) }
    fun italic() = toggle({ it.italic }) { copy(italic = it) }
    fun underline() = toggle({ it.underline }) { copy(underline = it) }

    fun resize(bigger: Boolean) = restyle { s ->
        val next = if (bigger) SIZES.firstOrNull { it > s.size + 0.01f } ?: s.size else SIZES.lastOrNull { it < s.size - 0.01f } ?: s.size
        s.copy(size = next)
    }

    fun color(rgb: Int?) = restyle { it.copy(color = rgb) }

    fun highlight(rgb: Int?) = restyle { it.copy(highlight = rgb) }

    val currentStyle: RunStyle?
        get() {
            val p = focusedParagraph ?: return null
            return WordEditing.styleAt(p, value.selection.max)
        }

    // ---- paragraph formatting ------------------------------------------------------------------

    private fun paragraph(transform: (WParagraph) -> WParagraph) {
        val path = focus ?: return
        val p = focusedParagraph ?: return
        change(doc.replaceBlock(path, transform(p)))
    }

    fun cycleAlign() = paragraph {
        it.copy(
            align = when (it.align) {
                WAlign.LEFT -> WAlign.CENTER
                WAlign.CENTER -> WAlign.RIGHT
                WAlign.RIGHT -> WAlign.JUSTIFY
                WAlign.JUSTIFY -> WAlign.LEFT
            },
        )
    }

    fun cycleList() = paragraph { WordEditing.cycleList(it) }

    fun kind(kind: WordEditing.ParagraphKind) = paragraph { WordEditing.withKind(it, kind) }

    fun indent(more: Boolean) = paragraph { it.copy(indentLeft = (it.indentLeft + if (more) 360 else -360).coerceAtLeast(0)) }

    // ---- blocks --------------------------------------------------------------------------------

    /** A new empty paragraph after the current block (or at the end), with the cursor in it. */
    fun newParagraph() {
        val path = focus ?: BlockPath.body(doc.blocks.size - 1)
        val p = focusedParagraph
        val style = p?.let { WordEditing.styleAt(it, value.selection.max) } ?: RunStyle()
        val fresh = WParagraph(runs = listOf(WRun("", style)), align = p?.align ?: WAlign.LEFT, indentLeft = p?.indentLeft ?: 0, list = p?.list)
        change(doc.insertBlock(path, path.last + 1, fresh))
        focusParagraph(path.withLast(path.last + 1), 0)
    }

    fun deleteBlock() {
        val path = focus ?: return
        change(doc.removeBlock(path))
        focus = null
        // Put the cursor on the paragraph before, when there is one.
        val prev = path.withLast((path.last - 1).coerceAtLeast(0))
        if (doc.blockAt(prev) is WParagraph) focusParagraph(prev, WordEditing.text(doc.blockAt(prev) as WParagraph).length)
    }

    fun insertPageBreak() {
        val path = topLevel() ?: BlockPath.body(doc.blocks.size - 1)
        change(doc.insertBlock(path, path.last + 1, WPageBreak).insertBlock(path, path.last + 2, WParagraph()))
        focusParagraph(path.withLast(path.last + 2), 0)
    }

    fun insertTable(columns: Int, rows: Int) {
        val path = topLevel() ?: BlockPath.body(doc.blocks.size - 1)
        val style = focusedParagraph?.firstStyle ?: RunStyle()
        val table = WordEditing.newTable(columns, rows, doc.page.textWidth, style.copy(bold = false))
        var next = doc.insertBlock(path, path.last + 1, table)
        // Word wants a paragraph after a table.
        if (next.blocks.getOrNull(path.last + 2) !is WParagraph) next = next.insertBlock(path, path.last + 2, WParagraph(runs = listOf(WRun("", style))))
        change(next)
        focusParagraph(BlockPath(listOf(path.last + 1, 0, 0, 0)), 0)
    }

    /** Adds a picture (JPEG/PNG bytes, [w] x [h] px) in its own paragraph after the current block. */
    fun insertPicture(bytes: ByteArray, ext: String, w: Int, h: Int) {
        val key = "user_${System.currentTimeMillis()}"
        val maxW = doc.page.textWidth * 635L
        var cx = w * 9525L // at 96 dpi
        var cy = h * 9525L
        if (cx > maxW) { cy = (cy * maxW.toDouble() / cx).roundToLong(); cx = maxW }
        val path = focus ?: BlockPath.body(doc.blocks.size - 1)
        val withMedia = doc.copy(media = doc.media + (key to WMedia(bytes, ext)))
        change(withMedia.insertBlock(path, path.last + 1, WParagraph(runs = listOf(WRun(image = WImage(key, cx, cy))), align = WAlign.CENTER)))
        focusParagraph(path.withLast(path.last + 1), 0)
    }

    /** Makes the pictures in the current paragraph bigger or smaller. */
    fun scalePictures(factor: Float) = paragraph { p ->
        p.copy(runs = p.runs.map { r ->
            r.image?.let { img -> r.copy(image = img.copy(widthEmu = (img.widthEmu * factor).roundToLong(), heightEmu = (img.heightEmu * factor).roundToLong())) } ?: r
        })
    }

    val focusHasPicture: Boolean get() = focusedParagraph?.images?.isNotEmpty() == true

    /** The body-level block holding the cursor (the table, when in a table). */
    private fun topLevel(): BlockPath? = focus?.let { BlockPath.body(it.steps[0]) }

    // ---- tables ----------------------------------------------------------------------------------

    private fun table(op: (TableSpot) -> WTable?, focusAfter: (TableSpot, WTable) -> Pair<Int, Int>?) {
        val spot = focusedTable ?: return
        val result = op(spot)
        if (result == null) {
            change(doc.removeBlock(spot.path))
            focus = null
            return
        }
        change(doc.replaceBlock(spot.path, result))
        val target = focusAfter(spot, result)
        focus = null
        if (target != null) {
            val (r, c) = target
            if (r in result.rows.indices && c in result.rows[r].cells.indices) focusParagraph(BlockPath(spot.path.steps + listOf(r, c, 0)), 0)
        }
    }

    fun rowAbove() = table({ WordEditing.insertRow(it.table, it.row, below = false) }) { s, _ -> s.row to s.cell }
    fun rowBelow() = table({ WordEditing.insertRow(it.table, it.row, below = true) }) { s, _ -> s.row + 1 to s.cell }
    fun deleteRow() = table({ WordEditing.deleteRow(it.table, it.row) }) { s, t -> (s.row.coerceAtMost(t.rows.size - 1)) to 0 }
    fun columnLeft() = table({ WordEditing.insertColumn(it.table, it.gridCol, after = false) }) { s, _ -> s.row to s.cell }
    fun columnRight() = table({ WordEditing.insertColumn(it.table, it.gridCol, after = true) }) { s, _ -> s.row to s.cell + 1 }
    fun deleteColumn() = table({ WordEditing.deleteColumn(it.table, it.gridCol) }) { s, _ -> s.row to 0 }
    fun deleteTable() = table({ null }) { _, _ -> null }

    companion object {
        val SIZES = listOf(6f, 7f, 8f, 9f, 10f, 10.5f, 11f, 12f, 14f, 16f, 18f, 20f, 22f, 24f, 28f, 32f, 36f, 40f, 48f, 56f, 64f, 72f, 96f)
    }
}
