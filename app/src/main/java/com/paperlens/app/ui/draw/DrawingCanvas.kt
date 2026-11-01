package com.paperlens.app.ui.draw

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

data class InkStroke(val points: List<Offset>, val color: Color, val width: Float)

/** Freehand ink layer shared by the signature pad and page annotation. */
class DrawingState {
    val strokes = mutableStateListOf<InkStroke>()
    private val currentPoints = mutableStateListOf<Offset>()
    private var currentColor = Color.Black
    private var currentWidth = 8f
    var canvasSize by mutableStateOf(IntSize.Zero)
        internal set

    val isEmpty: Boolean get() = strokes.isEmpty()

    internal fun start(point: Offset, color: Color, width: Float) {
        currentColor = color
        currentWidth = width
        currentPoints.clear()
        currentPoints.add(point)
    }

    internal fun extend(point: Offset) {
        currentPoints.add(point)
    }

    internal fun finish() {
        if (currentPoints.isNotEmpty()) strokes.add(InkStroke(currentPoints.toList(), currentColor, currentWidth))
        currentPoints.clear()
    }

    internal fun inProgress(): InkStroke? =
        if (currentPoints.isEmpty()) null else InkStroke(currentPoints.toList(), currentColor, currentWidth)

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
    }

    fun clear() = strokes.clear()

    /**
     * Rasterises the ink at [outWidth] x proportional height on a transparent background.
     * With [cropToInk] the result is trimmed to the strokes' bounding box (used for signatures).
     */
    fun toBitmap(outWidth: Int, cropToInk: Boolean): Bitmap {
        val size = canvasSize
        require(size.width > 0 && size.height > 0) { "Canvas not laid out" }
        val scale = outWidth.toFloat() / size.width
        var left = 0f
        var top = 0f
        var right = size.width.toFloat()
        var bottom = size.height.toFloat()
        if (cropToInk && strokes.isNotEmpty()) {
            val pad = strokes.maxOf { it.width } * 2
            left = max(0f, strokes.minOf { s -> s.points.minOf { it.x } } - pad)
            top = max(0f, strokes.minOf { s -> s.points.minOf { it.y } } - pad)
            right = min(size.width.toFloat(), strokes.maxOf { s -> s.points.maxOf { it.x } } + pad)
            bottom = min(size.height.toFloat(), strokes.maxOf { s -> s.points.maxOf { it.y } } + pad)
        }
        val bitmap = Bitmap.createBitmap(
            max(1, ((right - left) * scale).toInt()),
            max(1, ((bottom - top) * scale).toInt()),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            paint.color = stroke.color.toArgb()
            paint.strokeWidth = stroke.width * scale
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { i, p ->
                val x = (p.x - left) * scale
                val y = (p.y - top) * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (stroke.points.size == 1) path.lineTo((stroke.points[0].x - left) * scale + 0.1f, (stroke.points[0].y - top) * scale)
            canvas.drawPath(path, paint)
        }
        return bitmap
    }
}

@Composable
fun DrawingCanvas(state: DrawingState, color: Color, strokeWidth: Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .onSizeChanged { state.canvasSize = it }
            .pointerInput(color, strokeWidth) {
                detectDragGestures(
                    onDragStart = { state.start(it, color, strokeWidth) },
                    onDrag = { change, _ ->
                        change.consume()
                        state.extend(change.position)
                    },
                    onDragEnd = { state.finish() },
                    onDragCancel = { state.finish() },
                )
            },
    ) {
        val all = state.strokes + listOfNotNull(state.inProgress())
        for (stroke in all) {
            val path = Path()
            stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (stroke.points.size == 1) path.lineTo(stroke.points[0].x + 0.1f, stroke.points[0].y)
            drawPath(path, stroke.color, style = Stroke(width = stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
