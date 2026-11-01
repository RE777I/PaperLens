package com.paperlens.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import kotlin.math.max

/**
 * Zoom and pan of a picture drawn with `graphicsLayer` (scaled about its centre, then moved by [offset]).
 * Pinches zoom about the point between the fingers, so that part of the page stays under them; zooming
 * back out to 1× puts the page back in the middle.
 */
@Stable
class PanZoom(private val maxScale: Float = 5f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The gesture area. */
    var viewport = Size.Zero
        internal set

    /** Size of the page at 1× (centred in the viewport), so panning stops at its edges. Defaults to the viewport. */
    var content: Size? = null

    val zoomed get() = scale > 1f

    fun set(newScale: Float, newOffset: Offset) {
        scale = newScale.coerceIn(1f, maxScale)
        offset = if (scale <= 1f) Offset.Zero else clamp(scale, newOffset)
    }

    /** [o] limited so the page never leaves a gap at an edge it could cover. */
    fun clamp(s: Float, o: Offset): Offset {
        val c = content ?: viewport
        val mx = max(0f, (c.width * s - viewport.width) / 2)
        val my = max(0f, (c.height * s - viewport.height) / 2)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    /** Zooms by [zoom] about [centroid] (viewport coordinates) and moves by [pan]. */
    fun pinch(centroid: Offset, pan: Offset, zoom: Float) {
        // The event that lifts the last finger has no centroid; there is nothing to zoom about then.
        if (!centroid.isSpecified) return set(scale, offset + (if (pan.isSpecified) pan else Offset.Zero))
        val s = (scale * zoom).coerceIn(1f, maxScale)
        val k = s / scale
        val d = centroid - Offset(viewport.width / 2, viewport.height / 2)
        // The content point under the fingers stays under them: o' = (o - d)·k + d.
        set(s, (offset - d) * k + d + pan)
    }

    /** Double tap: zoom in on [at], or back out to the whole page. */
    fun toggle(at: Offset, to: Float = 2.5f) {
        if (zoomed) {
            set(1f, Offset.Zero)
        } else {
            scale = 1f
            offset = Offset.Zero
            pinch(at, Offset.Zero, to)
        }
    }
}

/**
 * Pinch / double-tap / drag-while-zoomed gestures for [state]. Single-finger drags at 1× are left alone
 * so a surrounding pager still swipes.
 */
fun Modifier.panZoom(state: PanZoom, key: Any?): Modifier = this
    .onSizeChanged { state.viewport = it.toSize() }
    .pointerInput(key) { detectTapGestures(onDoubleTap = { state.toggle(it) }) }
    .pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2 || state.zoomed) {
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    // Only movement is claimed, so a double tap while zoomed still reaches the tap detector.
                    if (zoom != 1f || pan != Offset.Zero) {
                        state.pinch(event.calculateCentroid(useCurrent = false), pan, zoom)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
