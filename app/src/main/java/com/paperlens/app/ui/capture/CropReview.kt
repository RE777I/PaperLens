package com.paperlens.app.ui.capture

import com.paperlens.app.S
import com.paperlens.app.R

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.paperlens.app.imaging.DocumentDetector
import com.paperlens.app.imaging.PageOutline
import com.paperlens.app.runLogged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The photo with the page outline: after each single shot, and for Crop on a saved page. Drag a
 * corner to fix it, or the handle in the middle of a side to move that whole side (its corners slide
 * along the neighbouring sides); a magnifier shows the spot under your finger. Handles are magnetic:
 * near a corner or straight edge found in the photo they jump onto it (with a tick of haptic feedback).
 * Switch between the detected outline and the full photo, cancel (Retake) or confirm.
 *
 * [preview] is a downscaled copy of the photo; [fullWidth]/[fullHeight] are the photo's real size, and
 * corners are passed in and out in full-size pixels. The outline starts at [initial] (else the whole
 * photo); Auto goes back to [detected].
 */
@Composable
fun CropReview(
    preview: Bitmap,
    fullWidth: Int,
    fullHeight: Int,
    detected: PageOutline?,
    onRetake: () -> Unit,
    onConfirm: (PageOutline) -> Unit,
    initial: PageOutline? = detected,
    cancelLabel: String = S.str(R.string.retake),
    cancelIcon: ImageVector = Icons.Filled.Replay,
    message: String = if (initial != null) S.str(R.string.drag_the_corners_or_sides_to_adjust) else S.str(R.string.no_page_found_drag_the_corners_onto_the_page),
) {
    val full = remember(fullWidth, fullHeight) {
        listOf(PointF(0f, 0f), PointF(fullWidth.toFloat(), 0f), PointF(fullWidth.toFloat(), fullHeight.toFloat()), PointF(0f, fullHeight.toFloat()))
    }
    val corners = remember { mutableStateListOf<PointF>().apply { addAll(initial?.corners ?: full) } }
    // The curl of each side (book pages) follows the corners when they are dragged.
    var bends by remember { mutableStateOf(initial?.bends) }
    // 0..3 = a corner, 4..7 = the handle on side (active - 4); -1 = nothing held.
    var active by remember { mutableIntStateOf(-1) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    var snapped by remember { mutableStateOf(false) }
    // The outline when the drag began and the finger's total movement (photo pixels): handles follow
    // start + drag, so they can be pulled off a snap again.
    var dragStart by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var dragTotal by remember { mutableStateOf(PointF(0f, 0f)) }
    val image = remember(preview) { preview.asImageBitmap() }
    val haptics = LocalHapticFeedback.current

    // Snap targets: edges and corners found in the photo, the detected page corners, the photo's own
    // corners and borders.
    var snap by remember { mutableStateOf(CropSnap(fullWidth.toFloat(), fullHeight.toFloat(), full, borders(full))) }
    LaunchedEffect(preview, detected) {
        val f = fullWidth.toFloat() / preview.width
        val found = withContext(Dispatchers.Default) { runLogged("CropReview", "Finding snap guides") { DocumentDetector.guides(preview) }.getOrNull() }
        fun up(p: PointF) = PointF(p.x * f, p.y * f)
        val points = full + (detected?.corners ?: emptyList()) + (found?.corners?.map(::up) ?: emptyList())
        val edges = borders(full) + (detected?.corners?.let(::borders) ?: emptyList()) +
            (found?.edges?.map { (a, b) -> up(a) to up(b) } ?: emptyList())
        snap = CropSnap(fullWidth.toFloat(), fullHeight.toFloat(), points, edges)
    }

    BackHandler(onBack = onRetake)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        )
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = fullWidth.toFloat() / fullHeight
            // The touch area reaches [margin] past the photo so corners sitting on its edge can be grabbed.
            val margin = 24.dp
            val boxW = minOf(maxWidth - margin * 2, (maxHeight - margin * 2) * ratio)
            val density = LocalDensity.current
            val scale = with(density) { boxW.toPx() } / fullWidth
            val m = with(density) { margin.toPx() }
            val area = with(density) { Size(boxW.toPx(), (boxW / ratio).toPx()) }
            val handle = with(density) { 14.dp.toPx() }
            val grab = with(density) { 56.dp.toPx() }
            val pill = with(density) { Size(30.dp.toPx(), 11.dp.toPx()) }
            // Magnet reach: about a fingertip on screen, in photo pixels.
            val magnet = with(density) { 22.dp.toPx() } / scale
            val loupeRadius = with(density) { 56.dp.toPx() }
            Canvas(
                Modifier
                    .size(boxW + margin * 2, boxW / ratio + margin * 2)
                    .pointerInput(scale) {
                        detectDragGestures(
                            onDragStart = { down ->
                                val p = down - Offset(m, m)
                                val shape = PageOutline(corners.toList(), bends)
                                // Corners and side handles, in view pixels; the closest one within reach is picked.
                                val targets = corners.map { Offset(it.x * scale, it.y * scale) } +
                                    (0 until 4).map { side -> shape.sidePoint(side, 0.5f).let { Offset(it.x * scale, it.y * scale) } }
                                val nearest = targets.indices.minByOrNull { (targets[it] - p).getDistance() } ?: -1
                                active = if (nearest >= 0 && (targets[nearest] - p).getDistance() <= grab) nearest else -1
                                dragStart = corners.toList()
                                dragTotal = PointF(0f, 0f)
                                snapped = false
                                touch = if (active >= 0) targets[active] else null
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (active >= 0) {
                                    dragTotal = PointF(dragTotal.x + amount.x / scale, dragTotal.y + amount.y / scale)
                                    val (moved, hit) = if (active < 4) {
                                        snap.moveCorner(dragStart, active, dragTotal, magnet)
                                    } else {
                                        snap.moveSide(dragStart, active - 4, dragTotal, magnet)
                                    }
                                    if (hit && !snapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    snapped = hit
                                    moved.forEachIndexed { i, c -> if (corners[i] != c) corners[i] = c }
                                    val at = if (active < 4) {
                                        corners[active]
                                    } else {
                                        PageOutline(corners.toList(), bends).sidePoint(active - 4, 0.5f)
                                    }
                                    touch = Offset(at.x * scale, at.y * scale)
                                }
                            },
                            onDragEnd = { active = -1; touch = null; snapped = false },
                            onDragCancel = { active = -1; touch = null; snapped = false },
                        )
                    }
                    .semantics { contentDescription = S.str(R.string.photo_with_page_outline) },
            ) {
                drawPhoto(image, Offset(m, m), 1f, area)
                val shape = PageOutline(corners.toList(), bends)
                val points = (0 until 4).flatMap { side ->
                    (0 until OUTLINE_STEPS).map { i ->
                        val p = shape.sidePoint(side, i.toFloat() / OUTLINE_STEPS)
                        Offset(m + p.x * scale, m + p.y * scale)
                    }
                }
                drawOutline(points, Offset.Zero, 1f, Offset.Zero)
                // Side handles: a pill in the middle of each side, lying along it.
                for (side in 0 until 4) {
                    val mid = shape.sidePoint(side, 0.5f).let { Offset(m + it.x * scale, m + it.y * scale) }
                    val a = shape.sidePoint(side, 0.45f)
                    val b = shape.sidePoint(side, 0.55f)
                    val degrees = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                    val held = active == side + 4
                    rotate(degrees, pivot = mid) {
                        val topLeft = mid - Offset(pill.width / 2, pill.height / 2)
                        val r = CornerRadius(pill.height / 2)
                        drawRoundRect(color = if (held) Accent else Color.White, topLeft = topLeft, size = pill, cornerRadius = r)
                        drawRoundRect(color = Accent, topLeft = topLeft, size = pill, cornerRadius = r, style = Stroke(3.dp.toPx()))
                    }
                }
                corners.map { Offset(m + it.x * scale, m + it.y * scale) }.forEachIndexed { i, at ->
                    drawCircle(color = if (i == active) Accent else Color.White, radius = handle, center = at)
                    drawCircle(color = Accent, radius = handle, center = at, style = Stroke(3.dp.toPx()))
                }
                // A snapped handle gets a ring, so the jump is visible as well as felt.
                if (snapped) touch?.let { drawCircle(color = Color.White, radius = handle * 1.9f, center = it + Offset(m, m), style = Stroke(2.dp.toPx())) }
                touch?.let { drawLoupe(image, area, points, it + Offset(m, m), Offset(m, m), loupeRadius) }
            }
        }
        // Bottom controls: Retake | Auto | confirm | Full page — the confirm button sits in the middle.
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                ReviewButton(cancelLabel, cancelIcon, onClick = onRetake)
                ReviewButton(S.str(R.string.auto_2), Icons.Filled.AutoFixHigh, enabled = detected != null) {
                    detected?.let { corners.clear(); corners.addAll(it.corners); bends = it.bends }
                }
            }
            Box(
                Modifier
                    .size(72.dp)
                    .background(Accent, CircleShape)
                    .clickable { onConfirm(PageOutline(corners.toList(), bends)) }
                    .semantics { contentDescription = S.str(R.string.confirm_crop) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, Modifier.size(36.dp), tint = Color.White) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                ReviewButton(S.str(R.string.full_page), Icons.Filled.CropFree) { corners.clear(); corners.addAll(full); bends = null }
            }
        }
    }
}

@Composable
private fun ReviewButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Column(
        Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Draws [image] stretched over [area] at [offset] scaled by [zoom]. */
private fun DrawScope.drawPhoto(image: ImageBitmap, offset: Offset, zoom: Float, area: Size) {
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
        dstSize = IntSize((area.width * zoom).roundToInt(), (area.height * zoom).roundToInt()),
    )
}

private const val OUTLINE_STEPS = 12

/** The four sides of the outline [c] (TL, TR, BR, BL) as end-point pairs. */
private fun borders(c: List<PointF>): List<Pair<PointF, PointF>> = (0 until 4).map { c[it] to c[(it + 1) % 4] }

/** The page outline through [points] (view pixels), magnified by [zoom] around [focus] placed at [centre]. */
private fun DrawScope.drawOutline(points: List<Offset>, focus: Offset, zoom: Float, centre: Offset) {
    val path = Path()
    points.forEachIndexed { i, p ->
        val q = centre + (p - focus) * zoom
        if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
    }
    path.close()
    if (zoom == 1f) drawPath(path = path, color = Accent.copy(alpha = 0.16f))
    drawPath(path = path, color = Accent, style = Stroke(width = 2.5.dp.toPx()))
}

/** 2.5× magnifier of the spot under the finger, drawn in the top corner away from it. */
private fun DrawScope.drawLoupe(image: ImageBitmap, area: Size, points: List<Offset>, touch: Offset, origin: Offset, radius: Float) {
    val zoom = 2.5f
    val margin = 8.dp.toPx()
    val centre = Offset(if (touch.x < size.width / 2) size.width - radius - margin else radius + margin, radius + margin)
    val circle = Path().apply { addOval(Rect(center = centre, radius = radius)) }
    clipPath(circle) {
        drawRect(color = Color.Black, topLeft = centre - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        drawPhoto(image, centre + (origin - touch) * zoom, zoom, area)
        drawOutline(points, touch, zoom, centre)
    }
    drawCircle(color = Color.White, radius = radius, center = centre, style = Stroke(2.dp.toPx()))
    val cross = 10.dp.toPx()
    drawLine(color = Accent, start = centre - Offset(cross, 0f), end = centre + Offset(cross, 0f), strokeWidth = 1.5.dp.toPx())
    drawLine(color = Accent, start = centre - Offset(0f, cross), end = centre + Offset(0f, cross), strokeWidth = 1.5.dp.toPx())
}

