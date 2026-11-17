package com.paperlens.app.ui.excel

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.excel.CellRange
import com.paperlens.app.excel.CellStyle
import com.paperlens.app.excel.CellValue
import com.paperlens.app.excel.Edge
import com.paperlens.app.excel.HAlign
import com.paperlens.app.excel.Sheet
import com.paperlens.app.excel.SheetThumbnail
import com.paperlens.app.excel.VAlign
import com.paperlens.app.excel.Workbook
import com.paperlens.app.excel.XCell
import com.paperlens.app.excel.cellKey
import com.paperlens.app.excel.columnName
import com.paperlens.app.excel.display
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Cell positions of a sheet in dp at 100 % (Excel's pixels), with room for a few empty rows and columns. */
private class Geometry(val sheet: Sheet) {
    val cols = max(sheet.colCount, 1) + 4
    val rows = max(sheet.rowCount, 1) + 12
    val x = FloatArray(cols + 1).also { for (c in 0 until cols) it[c + 1] = it[c] + SheetThumbnail.colPx(sheet, c) }
    val y = FloatArray(rows + 1).also { for (r in 0 until rows) it[r + 1] = it[r] + SheetThumbnail.rowPx(sheet, r) }

    /** Cell → the merged area it belongs to (small areas only; huge ones are found by search). */
    private val mergeOf = HashMap<Long, CellRange>().also { map ->
        for (m in sheet.merges) if ((m.r1 - m.r0 + 1).toLong() * (m.c1 - m.c0 + 1) <= 4000) {
            for (r in m.r0..m.r1) for (c in m.c0..m.c1) map[cellKey(r, c)] = m
        }
    }
    private val bigMerges = sheet.merges.filter { (it.r1 - it.r0 + 1).toLong() * (it.c1 - it.c0 + 1) > 4000 }

    fun merge(r: Int, c: Int): CellRange? = mergeOf[cellKey(r, c)] ?: bigMerges.firstOrNull { it.contains(r, c) }

    fun colAt(v: Float): Int = index(x, v, cols)
    fun rowAt(v: Float): Int = index(y, v, rows)

    private fun index(edges: FloatArray, v: Float, n: Int): Int {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (edges[mid] <= v) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun xAt(c: Int) = x[c.coerceIn(0, cols)]
    fun yAt(r: Int) = y[r.coerceIn(0, rows)]
}

private val GRID = Color(0xFFE1E1E1)
private val HEADER = Color(0xFFF3F3F3)
private val HEADER_ACTIVE = Color(0xFFD3E7DA)
private val HEADER_TEXT = Color(0xFF5F6368)
private val SELECT = Color(0xFF217346)

/**
 * A sheet drawn like Excel: column letters and row numbers, gridlines, fills, borders, merged cells,
 * text in its font and alignment (overflowing into empty cells on its right), and frozen header rows /
 * columns. One finger scrolls (with fling), two fingers zoom about the point between them, a tap selects
 * a cell and a double tap edits it.
 */
@Composable
internal fun SheetGrid(ed: SheetEditor, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val sheet = ed.sheet
    val book = ed.book
    val geometry = remember(sheet) { Geometry(sheet) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 400)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var sx by remember { mutableFloatStateOf(0f) }
    var sy by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val fling = remember { arrayOfNulls<Job>(1) }
    val rowHeaderW = with(density) { 40.dp.toPx() }
    val colHeaderH = with(density) { 22.dp.toPx() }
    LaunchedEffect(ed.sheetIndex) { sx = 0f; sy = 0f }

    fun k() = density.density * scale
    val fr = sheet.frozenRows.coerceIn(0, geometry.rows - 1)
    val fc = sheet.frozenCols.coerceIn(0, geometry.cols - 1)
    fun bodyLeft() = rowHeaderW + geometry.x[fc] * k()
    fun bodyTop() = colHeaderH + geometry.y[fr] * k()
    fun clamp() {
        val maxX = max(0f, (geometry.x[geometry.cols] - geometry.x[fc]) * k() - (size.width - bodyLeft()))
        val maxY = max(0f, (geometry.y[geometry.rows] - geometry.y[fr]) * k() - (size.height - bodyTop()))
        sx = sx.coerceIn(0f, maxX)
        sy = sy.coerceIn(0f, maxY)
    }

    /** The cell under a point on screen (the top-left one of a merged area), or null on the headers. */
    fun cellAt(p: Offset): Pair<Int, Int>? {
        if (p.x < rowHeaderW || p.y < colHeaderH) return null
        val col = if (p.x < bodyLeft()) geometry.colAt((p.x - rowHeaderW) / k()) else geometry.colAt((p.x - bodyLeft() + sx) / k() + geometry.x[fc])
        val row = if (p.y < bodyTop()) geometry.rowAt((p.y - colHeaderH) / k()) else geometry.rowAt((p.y - bodyTop() + sy) / k() + geometry.y[fr])
        val m = geometry.merge(row, col)
        return if (m != null) m.r0 to m.c0 else row to col
    }

    Canvas(
        modifier
            .onSizeChanged { size = it; clamp() }
            .semantics { contentDescription = S.str(R.string.sheet_x_y_rows_z_columns, sheet.name, sheet.rowCount, sheet.colCount) }
            .pointerInput(geometry) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling[0]?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moving = false
                    var pinched = false
                    var travel = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val pan = event.calculatePan()
                        if (!moving) {
                            travel += pan
                            if (travel.getDistance() > viewConfiguration.touchSlop || pressed >= 2) moving = true
                        }
                        if (moving) {
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (pressed >= 2 && zoom != 1f && centroid != Offset.Unspecified) {
                                pinched = true
                                val next = (scale * zoom).coerceIn(0.35f, 4f)
                                val f = next / scale
                                val px = centroid.x - bodyLeft()
                                val py = centroid.y - bodyTop()
                                sx = (sx + px) * f - px
                                sy = (sy + py) * f - py
                                scale = next
                            }
                            sx -= pan.x
                            sy -= pan.y
                            clamp()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        event.changes.firstOrNull { it.id == down.id && it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    } while (event.changes.any { it.pressed })
                    if (moving && !pinched) {
                        val v = tracker.calculateVelocity()
                        fling[0] = scope.launch {
                            launch {
                                var last = 0f
                                AnimationState(0f, v.x).animateDecay(decay) { sx -= value - last; last = value; clamp() }
                            }
                            launch {
                                var last = 0f
                                AnimationState(0f, v.y).animateDecay(decay) { sy -= value - last; last = value; clamp() }
                            }
                        }
                    }
                }
            }
            .pointerInput(geometry) {
                detectTapGestures(
                    onTap = { p -> cellAt(p)?.let { ed.selected = it } },
                    onDoubleTap = { p -> cellAt(p)?.let { ed.selected = it; onEdit() } },
                )
            },
    ) {
        drawRect(Color.White)
        val k = k()
        val left = bodyLeft()
        val top = bodyTop()
        val w = this.size.width
        val h = this.size.height
        val painter = CellPainter(this, geometry, book, sheet, measurer, k, density, ed.selected)
        val bodyX = left - geometry.x[fc] * k - sx
        val bodyY = top - geometry.y[fr] * k - sy
        painter.area(fr, geometry.rows - 1, fc, geometry.cols - 1, bodyX, bodyY, Rect(left, top, w, h))
        if (fr > 0) painter.area(0, fr - 1, fc, geometry.cols - 1, bodyX, colHeaderH, Rect(left, colHeaderH, w, top))
        if (fc > 0) painter.area(fr, geometry.rows - 1, 0, fc - 1, rowHeaderW, bodyY, Rect(rowHeaderW, top, left, h))
        if (fr > 0 && fc > 0) painter.area(0, fr - 1, 0, fc - 1, rowHeaderW, colHeaderH, Rect(rowHeaderW, colHeaderH, left, top))
        // Frozen pane edges.
        if (fr > 0) drawLine(Color(0xFFB0B0B0), Offset(0f, top), Offset(w, top), 1.5f * density.density)
        if (fc > 0) drawLine(Color(0xFFB0B0B0), Offset(left, 0f), Offset(left, h), 1.5f * density.density)

        // Headers: column letters along the top, row numbers down the side.
        val sel = ed.selected
        val selArea = sel?.let { geometry.merge(it.first, it.second) ?: CellRange(it.first, it.second, it.first, it.second) }
        drawRect(HEADER, Offset(rowHeaderW, 0f), Size(w - rowHeaderW, colHeaderH))
        drawRect(HEADER, Offset(0f, colHeaderH), Size(rowHeaderW, h - colHeaderH))
        val label = TextStyle(color = HEADER_TEXT, fontSize = with(density) { (11.dp.toPx()).toSp() })
        fun columnHeaders(c0: Int, c1: Int, ox: Float, clip: Rect) = clipRect(clip.left, clip.top, clip.right, clip.bottom) {
            for (c in c0..c1) {
                val x0 = ox + geometry.x[c] * k
                val x1 = ox + geometry.x[c + 1] * k
                if (x1 <= x0) continue
                if (selArea != null && c in selArea.c0..selArea.c1) drawRect(HEADER_ACTIVE, Offset(x0, 0f), Size(x1 - x0, colHeaderH))
                drawLine(GRID, Offset(x1, 0f), Offset(x1, colHeaderH), 1f)
                val t = measurer.measure(columnName(c), label)
                if (t.size.width < x1 - x0) drawText(t, topLeft = Offset((x0 + x1 - t.size.width) / 2, (colHeaderH - t.size.height) / 2))
            }
        }
        fun rowHeaders(r0: Int, r1: Int, oy: Float, clip: Rect) = clipRect(clip.left, clip.top, clip.right, clip.bottom) {
            for (r in r0..r1) {
                val y0 = oy + geometry.y[r] * k
                val y1 = oy + geometry.y[r + 1] * k
                if (y1 <= y0) continue
                if (selArea != null && r in selArea.r0..selArea.r1) drawRect(HEADER_ACTIVE, Offset(0f, y0), Size(rowHeaderW, y1 - y0))
                drawLine(GRID, Offset(0f, y1), Offset(rowHeaderW, y1), 1f)
                val t = measurer.measure((r + 1).toString(), label)
                if (t.size.height < (y1 - y0) * 1.4f) drawText(t, topLeft = Offset((rowHeaderW - t.size.width) / 2, (y0 + y1 - t.size.height) / 2))
            }
        }
        val visC0 = geometry.colAt((left - bodyX) / k).coerceAtLeast(fc)
        val visC1 = geometry.colAt((w - bodyX) / k)
        val visR0 = geometry.rowAt((top - bodyY) / k).coerceAtLeast(fr)
        val visR1 = geometry.rowAt((h - bodyY) / k)
        columnHeaders(visC0, visC1, bodyX, Rect(left, 0f, w, colHeaderH))
        if (fc > 0) columnHeaders(0, fc - 1, rowHeaderW, Rect(rowHeaderW, 0f, left, colHeaderH))
        rowHeaders(visR0, visR1, bodyY, Rect(0f, top, rowHeaderW, h))
        if (fr > 0) rowHeaders(0, fr - 1, colHeaderH, Rect(0f, colHeaderH, rowHeaderW, top))
        drawRect(HEADER, Offset.Zero, Size(rowHeaderW, colHeaderH))
        drawLine(Color(0xFFC8C8C8), Offset(0f, colHeaderH), Offset(w, colHeaderH), 1f)
        drawLine(Color(0xFFC8C8C8), Offset(rowHeaderW, 0f), Offset(rowHeaderW, h), 1f)
    }
}

/** Draws one pane of cells (body, frozen rows, frozen columns or their corner). */
private class CellPainter(
    val scope: DrawScope,
    val g: Geometry,
    val book: Workbook,
    val sheet: Sheet,
    val measurer: TextMeasurer,
    val k: Float,
    val screen: Density,
    val selected: Pair<Int, Int>?,
) {
    private class Item(val rect: Rect, val cell: XCell?, val row: Int, val col: Int, val merged: Boolean)

    /** Rows [r0, r1] × columns [c0, c1] with cell (0, 0) at ([ox], [oy]), clipped to [clip]. */
    fun area(r0: Int, r1: Int, c0: Int, c1: Int, ox: Float, oy: Float, clip: Rect) = with(scope) {
        if (clip.width <= 0f || clip.height <= 0f) return@with
        val vc0 = max(c0, g.colAt((clip.left - ox) / k))
        val vc1 = min(c1, g.colAt((clip.right - ox) / k))
        val vr0 = max(r0, g.rowAt((clip.top - oy) / k))
        val vr1 = min(r1, g.rowAt((clip.bottom - oy) / k))
        if (vc1 < vc0 || vr1 < vr0) return@with
        fun rect(a: CellRange) = Rect(ox + g.xAt(a.c0) * k, oy + g.yAt(a.r0) * k, ox + g.xAt(a.c1 + 1) * k, oy + g.yAt(a.r1 + 1) * k)
        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
            // Gridlines first: fills and merged areas cover them, as in Excel.
            val gx0 = ox + g.x[vc0] * k
            val gx1 = ox + g.x[vc1 + 1] * k
            val gy0 = oy + g.y[vr0] * k
            val gy1 = oy + g.y[vr1 + 1] * k
            for (c in vc0..vc1 + 1) drawLine(GRID, Offset(ox + g.x[c] * k, gy0), Offset(ox + g.x[c] * k, gy1), 1f)
            for (r in vr0..vr1 + 1) drawLine(GRID, Offset(gx0, oy + g.y[r] * k), Offset(gx1, oy + g.y[r] * k), 1f)

            val items = ArrayList<Item>()
            val seenMerges = HashSet<CellRange>()
            for (r in vr0..vr1) {
                if (g.y[r + 1] <= g.y[r]) continue
                for (c in vc0..vc1) {
                    val m = g.merge(r, c)
                    if (m != null) {
                        if (seenMerges.add(m)) items += Item(rect(m), sheet[m.r0, m.c0], m.r0, m.c0, true)
                        continue
                    }
                    val cell = sheet[r, c]
                    if (cell != null) items += Item(rect(CellRange(r, c, r, c)), cell, r, c, false)
                }
            }
            for (it in items) {
                val fill = it.cell?.style?.fill
                if (fill != null) drawRect(Color(0xFF000000.toInt() or fill), it.rect.topLeft, it.rect.size)
                else if (it.merged) drawRect(Color.White, Offset(it.rect.left + 0.5f, it.rect.top + 0.5f), Size(it.rect.width - 1f, it.rect.height - 1f))
            }
            for (it in items) borders(it.rect, it.cell?.style ?: continue)
            // Borders of cells covered by a merge (Excel draws the merged area's edges from them).
            for (m in seenMerges) if ((m.r1 - m.r0 + 1).toLong() * (m.c1 - m.c0 + 1) <= 4000) for (r in m.r0..m.r1) for (c in m.c0..m.c1) {
                if (r == m.r0 && c == m.c0) continue
                val s = sheet[r, c]?.style ?: continue
                borders(rect(CellRange(r, c, r, c)), s.copy(left = s.left.takeIf { c == m.c0 }, right = s.right.takeIf { c == m.c1 }, top = s.top.takeIf { r == m.r0 }, bottom = s.bottom.takeIf { r == m.r1 }))
            }
            for (it in items) text(it, vc1)
            selected?.let { (sr, sc) ->
                val area = g.merge(sr, sc) ?: CellRange(sr, sc, sr, sc)
                if (area.r1 >= r0 && area.r0 <= r1 && area.c1 >= c0 && area.c0 <= c1) {
                    val rc = rect(area)
                    drawRect(SELECT, rc.topLeft, rc.size, style = Stroke(2f * screen.density))
                }
            }
        }
    }

    private fun DrawScope.borders(rect: Rect, s: CellStyle) {
        fun edge(e: Edge?, a: Offset, b: Offset) {
            e ?: return
            drawLine(Color(0xFF000000.toInt() or (e.color ?: 0)), a, b, max(1f, e.width * screen.density * 0.6f * (k / screen.density).coerceAtMost(1.5f)))
        }
        edge(s.top, rect.topLeft, rect.topRight)
        edge(s.bottom, rect.bottomLeft, rect.bottomRight)
        edge(s.left, rect.topLeft, rect.bottomLeft)
        edge(s.right, rect.topRight, rect.bottomRight)
    }

    private fun DrawScope.text(item: Item, lastCol: Int) {
        val cell = item.cell ?: return
        val shown = book.display(cell)
        if (shown.isEmpty()) return
        val s = cell.style
        val px = (s.fontSize ?: 11f) * 4f / 3f * k
        if (px < 3f) return
        val pad = 2.5f * k
        val numeric = cell.value is CellValue.Number
        val align = when (s.hAlign) {
            HAlign.GENERAL -> when (cell.value) {
                is CellValue.Number -> HAlign.RIGHT
                is CellValue.Bool, is CellValue.Error -> HAlign.CENTER
                else -> HAlign.LEFT
            }
            else -> s.hAlign
        }
        val wrap = s.wrap
        var right = item.rect.right
        // Unwrapped text runs on over empty cells to its right.
        if (!wrap && !numeric && align == HAlign.LEFT && !item.merged) {
            var c = item.col + 1
            while (c <= lastCol + 8 && c < g.cols && c - item.col <= 12 && g.merge(item.row, c) == null && book.display(sheet[item.row, c]).isEmpty()) {
                right = item.rect.left + (g.x[c + 1] - g.x[item.col]) * k
                c++
            }
        }
        val style = TextStyle(
            color = Color(0xFF000000.toInt() or (s.fontColor ?: 0)),
            fontSize = with(screen) { px.toSp() },
            fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                s.underline -> TextDecoration.Underline
                s.strike -> TextDecoration.LineThrough
                else -> null
            },
            textAlign = when (align) { HAlign.CENTER -> TextAlign.Center; HAlign.RIGHT -> TextAlign.Right; else -> TextAlign.Left },
        )
        val width = (item.rect.width - 2 * pad).toInt().coerceAtLeast(1)
        val layout: TextLayoutResult = measurer.measure(
            AnnotatedString(shown),
            style,
            overflow = TextOverflow.Clip,
            softWrap = wrap,
            constraints = if (wrap) Constraints.fixedWidth(width) else Constraints(),
        )
        val tw = layout.size.width.toFloat()
        val th = layout.size.height.toFloat()
        val x = when {
            wrap -> item.rect.left + pad
            align == HAlign.RIGHT -> item.rect.right - pad - tw
            align == HAlign.CENTER -> item.rect.left + (item.rect.width - tw) / 2
            else -> item.rect.left + pad
        }
        val y = when (s.vAlign) {
            VAlign.TOP -> item.rect.top + pad * 0.6f
            VAlign.CENTER -> item.rect.top + (item.rect.height - th) / 2
            VAlign.BOTTOM -> item.rect.bottom - pad * 0.6f - th
        }
        clipRect(item.rect.left, item.rect.top, max(right, item.rect.right), item.rect.bottom) {
            drawText(layout, topLeft = Offset(x, y))
        }
    }
}
