package com.paperlens.app.word

import com.paperlens.app.word.PageBuilder.Row
import android.graphics.Bitmap
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Tables and pictures for [PageBuilder]: ruled grids and shaded-row tables become Word tables, and picture
// regions become cropped images.


internal fun PageBuilder.gridTable(g: GridTable, words: List<OcrWord>, pics: List<RectF>): WTable {
    val nr = g.rows.size - 1
    val nc = g.cols.size - 1
    val colX = FloatArray(nc + 1) { g.colX(it) }
    val rowY = FloatArray(nr + 1) { g.rowY(it) }
    val grid = (0 until nc).map { ((colX[it + 1] - colX[it]) * tw).roundToInt().coerceAtLeast(60) }
    val regionAt = Array(nr) { arrayOfNulls<GridCell>(nc) }
    for (c in g.cells) for (r in c.r0..c.r1) for (cc in c.c0..c.c1) regionAt[r][cc] = c
    // Words and pictures into cells, by where their centre lies.
    fun cellAt(x: Float, y: Float): GridCell? = g.locate(x, y)?.let { (r, c) -> regionAt[r][c] }
    val cellWords = words.groupBy { cellAt(it.box.centerX(), it.box.centerY()) }
    val cellPics = pics.groupBy { cellAt(it.centerX(), it.centerY()) }
    val thickness = (g.rows + g.cols).map { it.thickness }.sorted().let { it[it.size / 2] }
    val border = WBorder((thickness * tw / 20 * 8).roundToInt().coerceIn(4, 18), lineColor(g))
    val rows = (0 until nr).map { r ->
        val cells = ArrayList<WCell>()
        var c = 0
        while (c < nc) {
            val region = regionAt[r][c] ?: GridCell(r, c, r, c)
            val span = region.c1 - region.c0 + 1
            if (region.r0 == r) {
                val rect = g.cellRect(region)
                val width = (region.c0..region.c1).sumOf { grid[it] }
                val (blocks, vAlign) = cellContent(cellWords[region].orEmpty(), cellPics[region].orEmpty(), rect, width)
                cells += WCell(
                    blocks = blocks,
                    span = span,
                    vMerge = if (region.r1 > region.r0) VMerge.RESTART else VMerge.NONE,
                    vAlign = vAlign,
                    shading = a.cellShade(rect),
                )
            } else {
                cells += WCell(blocks = listOf(WParagraph()), span = span, vMerge = VMerge.CONTINUE)
            }
            c += span
        }
        WRow(cells, height = ((rowY[r + 1] - rowY[r]) * tw).roundToInt().coerceAtLeast(120))
    }
    return WTable(
        grid = grid,
        rows = rows,
        indent = ((colX[0] * tw) - setup.marginLeft).roundToInt().coerceAtLeast(0),
        borders = WBorders(border, border, border, border, border, border),
        cellMarginH = 80,
    )
}

internal fun PageBuilder.bandTable(b: BandTable, words: List<OcrWord>): WTable {
    val nRows = b.rowEdges.size - 1
    // Columns: where text parts start, when several rows agree.
    val rows = rows(words)
    val starts = rows.flatMap { r -> r.segs.map { it.box.left } }.sorted()
    val colStarts = ArrayList<Float>()
    var i = 0
    while (i < starts.size) {
        var j = i
        while (j + 1 < starts.size && starts[j + 1] - starts[i] < w * 0.025f) j++
        if (j - i + 1 >= 2) colStarts += starts.subList(i, j + 1).average().toFloat()
        i = j + 1
    }
    if (colStarts.isEmpty() || colStarts[0] > b.box.left + w * 0.05f) colStarts.add(0, b.box.left)
    val edges = ArrayList<Float>()
    edges += b.box.left
    for (k in 1 until colStarts.size) edges += colStarts[k] - w * 0.01f
    edges += b.box.right
    val nCols = edges.size - 1
    val grid = (0 until nCols).map { ((edges[it + 1] - edges[it]) * tw).roundToInt().coerceAtLeast(60) }
    val cellWords = Array(nRows) { Array(nCols) { ArrayList<OcrWord>() } }
    for (wd in words) {
        val cy = wd.box.centerY()
        val r = (0 until nRows).firstOrNull { cy >= b.rowEdges[it] && cy < b.rowEdges[it + 1] } ?: continue
        val cx = wd.box.left + 1
        val c = (0 until nCols).lastOrNull { cx >= edges[it] } ?: 0
        cellWords[r][c] += wd
    }
    val tableRows = (0 until nRows).map { r ->
        val cells = (0 until nCols).map { c ->
            val rect = RectF(edges[c], b.rowEdges[r], edges[c + 1], b.rowEdges[r + 1])
            val (blocks, vAlign) = cellContent(cellWords[r][c], emptyList(), rect, grid[c])
            WCell(blocks = blocks, vAlign = vAlign, shading = b.rowShade[r])
        }
        WRow(cells, height = ((b.rowEdges[r + 1] - b.rowEdges[r]) * tw).roundToInt().coerceAtLeast(120))
    }
    return WTable(
        grid = grid,
        rows = tableRows,
        indent = ((b.box.left * tw) - setup.marginLeft).roundToInt().coerceAtLeast(0),
        borders = WBorders.NONE,
        cellMarginH = 80,
    )
}

/** Paragraphs (and pictures) of one cell, plus its vertical alignment. */
private fun PageBuilder.cellContent(words: List<OcrWord>, pics: List<RectF>, rect: RectF, widthTw: Int): Pair<List<WBlock>, VAlign> {
    val rows = rows(words)
    val items = ArrayList<Pair<Float, WBlock>>()
    // Lines into paragraphs: a line that ends early is a deliberate break, a full one wraps.
    val groups = ArrayList<MutableList<Row>>()
    for (row in rows) {
        val prev = groups.lastOrNull()?.last()
        if (prev != null && row.box.top - prev.box.bottom < prev.box.height() * 0.9f &&
            abs(row.segs[0].em - prev.segs[0].em) < prev.segs[0].em * 0.2f
        ) groups.last() += row else groups += mutableListOf(row)
    }
    for (g in groups) {
        val runs = ArrayList<WRun>()
        g.forEachIndexed { i, row ->
            if (i > 0) {
                val prev = g[i - 1]
                val full = rect.right - prev.box.right < rect.width() * 0.22f
                val s = prev.segs.last()
                runs += WRun(if (full) " " else "\n", styleOf(s.words.last(), s))
            }
            row.segs.forEachIndexed { k, s ->
                if (k > 0) runs += WRun(" ", styleOf(s.words[0], s))
                runs += runsOf(s)
            }
        }
        val box = RectF(g[0].box).apply { g.forEach { union(it.box) } }
        val leftGap = box.left - rect.left
        val rightGap = rect.right - box.right
        val align = when {
            abs(leftGap - rightGap) < rect.width() * 0.08f && leftGap > rect.width() * 0.12f -> WAlign.CENTER
            rightGap < rect.width() * 0.06f && leftGap > rect.width() * 0.3f -> WAlign.RIGHT
            else -> WAlign.LEFT
        }
        items += box.top to WParagraph(runs = merge(runs), align = align)
    }
    for (p in pics) {
        val maxW = ((widthTw - 160) * 635L).coerceAtLeast(635L * 200)
        var image = pictureImage(p, float = false)
        if (image.widthEmu > maxW) {
            val f = maxW.toDouble() / image.widthEmu
            image = image.copy(widthEmu = maxW, heightEmu = (image.heightEmu * f).roundToLong())
        }
        val cx = p.centerX() - rect.centerX()
        val align = if (abs(cx) < rect.width() * 0.1f) WAlign.CENTER else if (cx > 0) WAlign.RIGHT else WAlign.LEFT
        items += p.top to WParagraph(runs = listOf(WRun(image = image)), align = align)
    }
    if (items.isEmpty()) return listOf<WBlock>(WParagraph(runs = listOf(WRun("", RunStyle(size = ptOf(PageAnalysis.median(words.map { it.em }).takeIf { it > 0 } ?: 30f)))))) to VAlign.CENTER
    val contentTop = min(words.minOfOrNull { it.box.top } ?: Float.MAX_VALUE, pics.minOfOrNull { it.top } ?: Float.MAX_VALUE)
    val contentBottom = max(words.maxOfOrNull { it.box.bottom } ?: 0f, pics.maxOfOrNull { it.bottom } ?: 0f)
    val topGap = contentTop - rect.top
    val bottomGap = rect.bottom - contentBottom
    val vAlign = when {
        abs(topGap - bottomGap) < rect.height() * 0.15f -> VAlign.CENTER
        bottomGap < topGap * 0.5f -> VAlign.BOTTOM
        else -> if (topGap > rect.height() * 0.25f) VAlign.CENTER else VAlign.TOP
    }
    return items.sortedBy { it.first }.map { it.second } to vAlign
}

/** Fill colour of a cell when it is clearly not white. */
internal fun PageAnalysis.cellShade(rect: RectF): Int? {
    var n = 0; var r = 0L; var g = 0L; var b = 0L
    val x0 = (rect.left + rect.width() * 0.08f).toInt().coerceIn(0, w - 1)
    val x1 = (rect.right - rect.width() * 0.08f).toInt().coerceIn(x0 + 1, w)
    val y0 = (rect.top + rect.height() * 0.15f).toInt().coerceIn(0, h - 1)
    val y1 = (rect.bottom - rect.height() * 0.15f).toInt().coerceIn(y0 + 1, h)
    for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
        if (ink[y * w + x]) continue
        val c = rgb(x, y)
        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
    }
    if (n < 20) return null
    val c = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    val luma = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    return if (luma < paper - 22) lighten(c) else null
}

private fun PageBuilder.lineColor(g: GridTable): Int? {
    val samples = ArrayList<Int>()
    for (rule in g.rows) {
        var x = rule.start
        while (x < rule.end) {
            val y = rule.at(x).roundToInt()
            if (y in 0 until h) samples += darkestPixelNear(x.toInt().coerceIn(0, w - 1), y)
            x += 9f
        }
    }
    return a.inkColor(samples)
}

/** The colour of the darkest pixel within 2 pixels above or below (x, y): the line itself, not the paper beside it. */
private fun PageBuilder.darkestPixelNear(x: Int, y: Int): Int {
    var bestY = y
    var bestGray = 256
    for (dy in -2..2) {
        val yy = (y + dy).coerceIn(0, h - 1)
        if (a.g(x, yy) < bestGray) {
            bestGray = a.g(x, yy)
            bestY = yy
        }
    }
    return a.rgb(x, bestY)
}

// ---- pictures --------------------------------------------------------------------------------

internal fun PageBuilder.pictureImage(rect: RectF, float: Boolean): WImage {
    val pad = w * 0.004f
    val x0 = (rect.left - pad).toInt().coerceIn(0, w - 1)
    val y0 = (rect.top - pad).toInt().coerceIn(0, h - 1)
    val x1 = (rect.right + pad).toInt().coerceIn(x0 + 1, w)
    val y1 = (rect.bottom + pad).toInt().coerceIn(y0 + 1, h)
    val crop = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
    val qr = codes.any { RectF.intersects(it, rect) }
    val bytes = ByteArrayOutputStream().use { out ->
        crop.compress(if (qr) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }
    val key = "$mediaPrefix${media.size + 1}"
    media[key] = WMedia(bytes, if (qr) "png" else "jpg")
    val f = if (float) WFloat((x0 * emu).roundToLong(), (y0 * emu).roundToLong()) else null
    return WImage(key, ((x1 - x0) * emu).roundToLong(), ((y1 - y0) * emu).roundToLong(), f)
}

/**
 * A ruled table worth keeping: columns wide enough for text, and text or pictures in a good share of
 * its cells (shading edges and photo outlines can look like grids of thin empty cells).
 */
internal fun GridTable.plausible(words: List<OcrWord>, textHeight: Float): Boolean {
    val nc = cols.size - 1
    val narrow = (0 until nc).count { colX(it + 1) - colX(it) < textHeight * 1.5f }
    if (narrow * 3 > nc) return false
    if (cells.size <= 1) return words.any { box.contains(it.box.centerX(), it.box.centerY()) }
    val filled = cells.count { cell ->
        val r = cellRect(cell)
        words.any { r.contains(it.box.centerX(), it.box.centerY()) }
    }
    return filled * 10 >= cells.size * 3
}

/** A real table of shaded rows has text in most rows (a photo's dark stretches have none) and no ruled table on it. */
internal fun BandTable.plausible(words: List<OcrWord>, gridBoxes: List<RectF>): Boolean {
    val rowsWithText = (0 until rowEdges.size - 1).count { r ->
        words.any { wd ->
            val cy = wd.box.centerY()
            cy >= rowEdges[r] && cy < rowEdges[r + 1] && wd.box.centerX() in box.left..box.right
        }
    }
    return gridBoxes.none { RectF.intersects(it, box) } && rowsWithText >= (rowEdges.size - 1) * 0.6f
}
