package com.paperlens.app.word

/**
 * A Word document as the app reads, shows, edits and writes it: paragraphs (with styled runs, tabs,
 * line breaks and pictures), tables (merged cells, shading, borders) and page breaks.
 *
 * Measurements follow Word: twips (1/20 pt) for page and paragraph geometry, EMU (1/914400 inch)
 * for pictures, points for font sizes. Everything is immutable; edits make new copies.
 */
data class WordDocument(
    val blocks: List<WBlock>,
    val page: PageSetup = PageSetup(),
    /** Picture bytes by key (referenced from [WImage.mediaKey]). */
    val media: Map<String, WMedia> = emptyMap(),
    /** For imported files: the original package, so headers, footers, styles etc. survive a save. */
    val source: SourcePackage? = null,
)

class WMedia(val bytes: ByteArray, val ext: String)

/** The parts of an imported .docx that the app does not edit but keeps when saving. */
class SourcePackage(
    /** Every zip entry of the original file. */
    val entries: Map<String, ByteArray>,
    /** Path of the main document part, normally word/document.xml. */
    val mainPart: String,
    /** The original body-level section properties (headers, footers, columns…), written back verbatim. */
    val sectPrXml: String?,
    /** Namespace declarations of the original root element (the section properties may need them). */
    val rootNamespaces: String = "",
)

data class PageSetup(
    val width: Int = A4_WIDTH,
    val height: Int = A4_HEIGHT,
    val marginTop: Int = 1134,
    val marginBottom: Int = 1134,
    val marginLeft: Int = 1134,
    val marginRight: Int = 1134,
) {
    val textWidth: Int get() = (width - marginLeft - marginRight).coerceAtLeast(1440)

    companion object {
        const val A4_WIDTH = 11906
        const val A4_HEIGHT = 16838
    }
}

sealed interface WBlock

enum class WAlign { LEFT, CENTER, RIGHT, JUSTIFY }

enum class VAlign { TOP, CENTER, BOTTOM }

enum class LineRule { AUTO, EXACT, AT_LEAST }

data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Font size in points. */
    val size: Float = DEFAULT_SIZE,
    /** Text colour as 0xRRGGBB, null = automatic (black). */
    val color: Int? = null,
    /** Highlight / run shading as 0xRRGGBB. */
    val highlight: Int? = null,
    val font: String? = null,
) {
    companion object {
        const val DEFAULT_SIZE = 11f
    }
}

/**
 * A piece of text with one style, or a picture ([image] set, [text] empty). Text may contain '\t'
 * (tab) and '\n' (line break inside the paragraph).
 */
data class WRun(val text: String = "", val style: RunStyle = RunStyle(), val image: WImage? = null)

/** A picture: inline (flows with the text) or floating at a fixed spot when [float] is set. */
data class WImage(val mediaKey: String, val widthEmu: Long, val heightEmu: Long, val float: WFloat? = null)

/** Position of a floating picture: offsets in EMU from the page (or margin / paragraph) corner. */
data class WFloat(
    val x: Long,
    val y: Long,
    val relativeH: String = "page",
    val relativeV: String = "page",
    val behindText: Boolean = false,
)

enum class TabKind { LEFT, CENTER, RIGHT, DECIMAL }

data class WTab(val position: Int, val kind: TabKind = TabKind.LEFT)

/** Bulleted or numbered list item. [label] is the text shown before it ("•", "3.", "b)"). */
data class WList(val numbered: Boolean, val level: Int = 0, val label: String = if (numbered) "1." else "•", val numId: Int? = null)

data class WParagraph(
    val runs: List<WRun> = listOf(WRun()),
    val align: WAlign = WAlign.LEFT,
    /** Twips. */
    val spaceBefore: Int = 0,
    val spaceAfter: Int = 0,
    /** AUTO: 240 = single line; EXACT / AT_LEAST: twips. Null = document default. */
    val line: Int? = null,
    val lineRule: LineRule = LineRule.AUTO,
    val indentLeft: Int = 0,
    val indentRight: Int = 0,
    /** First-line indent in twips; negative = hanging. */
    val indentFirst: Int = 0,
    val tabs: List<WTab> = emptyList(),
    /** Paragraph background as 0xRRGGBB. */
    val shading: Int? = null,
    /** A line under the paragraph (horizontal rules). */
    val borderBottom: WBorder? = null,
    val list: WList? = null,
    /** Style id from an imported file (Heading1, Title…), written back so headings stay headings. */
    val styleId: String? = null,
) : WBlock {
    val text: String get() = runs.joinToString("") { it.text }
    val images: List<WImage> get() = runs.mapNotNull { it.image }

    /** Style of the first text run (or the default), used for empty paragraphs and new text. */
    val firstStyle: RunStyle get() = runs.firstOrNull { it.image == null }?.style ?: RunStyle()
}

data object WPageBreak : WBlock

data class WBorder(val size: Int = 4, val color: Int? = null)

/** Table border set; null entries mean no line. Sizes are in eighths of a point. */
data class WBorders(
    val top: WBorder? = null,
    val bottom: WBorder? = null,
    val left: WBorder? = null,
    val right: WBorder? = null,
    val insideH: WBorder? = null,
    val insideV: WBorder? = null,
) {
    companion object {
        val NONE = WBorders()
        val ALL = WBorders(WBorder(), WBorder(), WBorder(), WBorder(), WBorder(), WBorder())
    }
}

/** Per-cell border overrides: null = take the table's border on that side. */
data class CellBorders(
    val top: WBorder? = null,
    val bottom: WBorder? = null,
    val left: WBorder? = null,
    val right: WBorder? = null,
    /** Sides explicitly switched off ("nil"). */
    val noTop: Boolean = false,
    val noBottom: Boolean = false,
    val noLeft: Boolean = false,
    val noRight: Boolean = false,
)

enum class VMerge { NONE, RESTART, CONTINUE }

data class WCell(
    val blocks: List<WBlock> = listOf(WParagraph()),
    /** Grid columns this cell covers. */
    val span: Int = 1,
    val vMerge: VMerge = VMerge.NONE,
    val shading: Int? = null,
    val vAlign: VAlign = VAlign.TOP,
    val borders: CellBorders? = null,
)

data class WRow(
    val cells: List<WCell>,
    /** Minimum (or exact) height in twips. */
    val height: Int? = null,
    val exactHeight: Boolean = false,
)

data class WTable(
    /** Column widths in twips. */
    val grid: List<Int>,
    val rows: List<WRow>,
    /** Distance from the left margin, twips. */
    val indent: Int = 0,
    val borders: WBorders = WBorders.ALL,
    val align: WAlign = WAlign.LEFT,
    /** Cell padding left/right, twips. */
    val cellMarginH: Int = 108,
) : WBlock {
    val width: Int get() = grid.sum()
}

// ---- addressing blocks inside nested tables ------------------------------------------------------

/**
 * Where a block sits: [blockIndex] in the body, then for each table on the way down a (row, cell,
 * block-in-cell) triple. [0] is the first body block; [3, 1, 2, 0] is the first paragraph of the
 * third cell in the second row of the table that is body block 3.
 */
data class BlockPath(val steps: List<Int>) {
    val depth: Int get() = (steps.size - 1) / 3
    val last: Int get() = steps.last()

    fun withLast(index: Int) = BlockPath(steps.dropLast(1) + index)

    /** Path of the table that contains this block (null at body level). */
    val table: BlockPath? get() = if (steps.size >= 4) BlockPath(steps.dropLast(3)) else null

    /** Row and cell of this block inside [table]. */
    val row: Int get() = steps[steps.size - 3]
    val cell: Int get() = steps[steps.size - 2]

    companion object {
        fun body(index: Int) = BlockPath(listOf(index))
    }
}

fun WordDocument.blockAt(path: BlockPath): WBlock? = blockIn(blocks, path.steps)

private fun blockIn(blocks: List<WBlock>, steps: List<Int>): WBlock? {
    val b = blocks.getOrNull(steps[0]) ?: return null
    if (steps.size == 1) return b
    val table = b as? WTable ?: return null
    val cell = table.rows.getOrNull(steps[1])?.cells?.getOrNull(steps[2]) ?: return null
    return blockIn(cell.blocks, steps.drop(3))
}

/** The list of blocks that [path] indexes into (body or a cell), changed by [change]. */
fun WordDocument.updateContainer(path: BlockPath, change: (List<WBlock>) -> List<WBlock>): WordDocument =
    copy(blocks = updateIn(blocks, path.steps, change))

private fun updateIn(blocks: List<WBlock>, steps: List<Int>, change: (List<WBlock>) -> List<WBlock>): List<WBlock> {
    if (steps.size == 1) return change(blocks)
    val table = blocks[steps[0]] as WTable
    val r = steps[1]
    val c = steps[2]
    val rows = table.rows.toMutableList()
    val cells = rows[r].cells.toMutableList()
    val inner = updateIn(cells[c].blocks, steps.drop(3), change)
    // A cell must always hold at least one paragraph.
    cells[c] = cells[c].copy(blocks = inner.ifEmpty { listOf(WParagraph()) })
    rows[r] = rows[r].copy(cells = cells)
    return blocks.toMutableList().also { it[steps[0]] = table.copy(rows = rows) }
}

fun WordDocument.updateBlock(path: BlockPath, change: (WBlock) -> WBlock): WordDocument =
    updateContainer(path) { list -> list.toMutableList().also { it[path.last] = change(it[path.last]) } }

fun WordDocument.replaceBlock(path: BlockPath, block: WBlock): WordDocument = updateBlock(path) { block }

fun WordDocument.insertBlock(path: BlockPath, at: Int, block: WBlock): WordDocument =
    updateContainer(path) { list -> list.toMutableList().also { it.add(at.coerceIn(0, it.size), block) } }

fun WordDocument.removeBlock(path: BlockPath): WordDocument =
    updateContainer(path) { list ->
        val out = list.toMutableList().also { it.removeAt(path.last) }
        // The body must not end up empty either.
        if (out.isEmpty() && path.depth == 0) listOf(WParagraph()) else out
    }

/** Every paragraph with its path, in reading order (tables included). */
fun WordDocument.paragraphs(): List<Pair<BlockPath, WParagraph>> {
    val out = ArrayList<Pair<BlockPath, WParagraph>>()
    fun walk(blocks: List<WBlock>, prefix: List<Int>) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is WParagraph -> out += BlockPath(prefix + i) to b
                is WTable -> b.rows.forEachIndexed { r, row ->
                    row.cells.forEachIndexed { c, cell -> walk(cell.blocks, prefix + listOf(i, r, c)) }
                }
                else -> Unit
            }
        }
    }
    walk(blocks, emptyList())
    return out
}

/** Plain text of the whole document (for search, sharing as text, the thumbnail). */
fun WordDocument.plainText(): String = paragraphs().joinToString("\n") { it.second.text }

// ---- table geometry --------------------------------------------------------------------------

/** Grid column where each cell of [row] starts. */
fun WRow.gridStarts(): IntArray {
    val starts = IntArray(cells.size)
    var g = 0
    cells.forEachIndexed { i, c -> starts[i] = g; g += c.span.coerceAtLeast(1) }
    return starts
}

/** Index of the cell in [row] that covers grid column [col], or -1. */
fun WRow.cellAtGrid(col: Int): Int {
    var g = 0
    cells.forEachIndexed { i, c ->
        val s = c.span.coerceAtLeast(1)
        if (col in g until g + s) return i
        g += s
    }
    return -1
}

/** How many rows the cell at ([row], [cell]) covers, following vertical merges. */
fun WTable.rowSpan(row: Int, cell: Int): Int {
    val c = rows[row].cells[cell]
    if (c.vMerge != VMerge.RESTART) return 1
    val col = rows[row].gridStarts()[cell]
    var n = 1
    while (row + n < rows.size) {
        val i = rows[row + n].cellAtGrid(col)
        if (i < 0 || rows[row + n].cells[i].vMerge != VMerge.CONTINUE) break
        n++
    }
    return n
}
