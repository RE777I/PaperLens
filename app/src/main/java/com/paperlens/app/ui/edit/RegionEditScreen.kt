package com.paperlens.app.ui.edit

import com.paperlens.app.S
import com.paperlens.app.R

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.paperlens.app.app
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.DocumentDetector
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.imaging.TextLook
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.rememberBusy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class RegionTool(private val titleRes: Int, private val hintRes: Int) {
    CROP(R.string.crop, R.string.drag_the_corners_onto_the_page_edges),
    ERASE(R.string.smart_erase, R.string.drag_over_marks_stains_or_text_to_wipe_them),
    MOSAIC(R.string.mosaic, R.string.drag_over_anything_you_want_to_hide),
    TEXT(R.string.add_text, R.string.tap_where_the_text_should_start),
;
    val title: String get() = S.str(titleRes)

    val hint: String get() = S.str(hintRes)
}

private val Accent = Color(0xFF14A38B)

/** Crop (4 corners), Smart Erase / Mosaic (drag boxes) and Add Text (tap + type) on one page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionEditScreen(pageId: Long, tool: RegionTool, onDone: () -> Unit) {
    val repo = LocalContext.current.app.repository
    val busy = rememberBusy()
    val page by remember(pageId) { repo.observePage(pageId) }.collectAsStateWithLifecycle(initialValue = null)
    val current = page ?: return
    // Crop works on the untouched scan; the other tools on what the user sees.
    val file = remember(current.id, tool) { File(if (tool == RegionTool.CROP) current.originalPath else current.imagePath) }
    val (imgW, imgH) = remember(file) { BitmapUtils.imageSize(file) }

    val boxes = remember { mutableStateListOf<RectF>() }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }
    val corners = remember { mutableStateListOf<PointF>() }
    var textAt by remember { mutableStateOf<PointF?>(null) }
    var text by remember { mutableStateOf("") }
    var askText by remember { mutableStateOf(false) }
    var textSize by remember { mutableFloatStateOf(0.04f) }
    var textLook by remember { mutableStateOf(TextLook()) }

    fun fullCorners() = listOf(PointF(0f, 0f), PointF(imgW.toFloat(), 0f), PointF(imgW.toFloat(), imgH.toFloat()), PointF(0f, imgH.toFloat()))

    LaunchedEffect(file) {
        if (tool == RegionTool.CROP && corners.isEmpty()) corners.addAll(fullCorners())
    }

    val canApply = when (tool) {
        RegionTool.CROP -> corners.size == 4
        RegionTool.ERASE, RegionTool.MOSAIC -> boxes.isNotEmpty()
        RegionTool.TEXT -> textAt != null && text.isNotBlank()
    }

    fun apply() {
        busy.run(S.str(R.string.applying_x, tool.title)) {
            when (tool) {
                RegionTool.CROP -> repo.recropPage(current, com.paperlens.app.imaging.PageOutline.of(corners.toList()))
                RegionTool.ERASE -> repo.bakeEdit(current) { bmp -> PageComposer.erase(bmp, scaled(boxes, bmp.width.toFloat() / imgW)) }
                RegionTool.MOSAIC -> repo.bakeEdit(current) { bmp -> PageComposer.mosaic(bmp, scaled(boxes, bmp.width.toFloat() / imgW)) }
                RegionTool.TEXT -> {
                    val at = textAt ?: return@run
                    repo.bakeEdit(current) { bmp ->
                        val s = bmp.width.toFloat() / imgW
                        PageComposer.addText(bmp, text, at.x * s, at.y * s, textSize * bmp.width, textLook)
                    }
                }
            }
            onDone()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, S.str(R.string.cancel)) } },
                    title = { Text(tool.title) },
                    actions = {
                        if (tool == RegionTool.ERASE || tool == RegionTool.MOSAIC) {
                            IconButton(enabled = boxes.isNotEmpty(), onClick = { boxes.removeAt(boxes.lastIndex) }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, S.str(R.string.undo))
                            }
                        }
                        IconButton(enabled = canApply, onClick = ::apply) { Icon(Icons.Filled.Check, S.str(R.string.apply)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                Text(tool.hint, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF303030)),
                    contentAlignment = Alignment.Center,
                ) {
                    val ratio = if (imgW > 0 && imgH > 0) imgW.toFloat() / imgH else 0.707f
                    val boxW = minOf(maxWidth, maxHeight * ratio)
                    val density = LocalDensity.current
                    val scale = with(density) { boxW.toPx() } / max(1, imgW)
                    Box(Modifier.size(boxW, boxW / ratio)) {
                        AsyncImage(file, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(tool, scale) {
                                    when (tool) {
                                        RegionTool.CROP -> {
                                            var active = -1
                                            detectDragGestures(
                                                onDragStart = { p ->
                                                    active = corners.indices.minByOrNull { hypot(corners[it].x * scale - p.x, corners[it].y * scale - p.y) } ?: -1
                                                    if (active >= 0 && hypot(corners[active].x * scale - p.x, corners[active].y * scale - p.y) > 60.dp.toPx()) active = -1
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    if (active >= 0) {
                                                        val c = corners[active]
                                                        corners[active] = PointF(
                                                            (c.x + amount.x / scale).coerceIn(0f, imgW.toFloat()),
                                                            (c.y + amount.y / scale).coerceIn(0f, imgH.toFloat()),
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                        RegionTool.ERASE, RegionTool.MOSAIC -> detectDragGestures(
                                            onDragStart = { dragStart = it; dragNow = it },
                                            onDrag = { change, _ -> change.consume(); dragNow = change.position },
                                            onDragEnd = {
                                                val a = dragStart
                                                val b = dragNow
                                                if (a != null && b != null) {
                                                    val r = RectF(min(a.x, b.x) / scale, min(a.y, b.y) / scale, max(a.x, b.x) / scale, max(a.y, b.y) / scale)
                                                    if (r.width() > 4 && r.height() > 4) boxes.add(r)
                                                }
                                                dragStart = null
                                                dragNow = null
                                            },
                                        )
                                        // Drag moves the text; a tap (below) places it.
                                        RegionTool.TEXT -> detectDragGestures(
                                            onDragStart = { p -> if (textAt == null) textAt = PointF(p.x / scale, p.y / scale) },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                textAt?.let { textAt = PointF(it.x + amount.x / scale, it.y + amount.y / scale) }
                                            },
                                        )
                                    }
                                }
                                .pointerInput(tool, scale) {
                                    if (tool == RegionTool.TEXT) {
                                        detectTapGestures { p ->
                                            textAt = PointF(p.x / scale, p.y / scale)
                                            if (text.isBlank()) askText = true
                                        }
                                    }
                                },
                        ) {
                            when (tool) {
                                RegionTool.CROP -> if (corners.size == 4) {
                                    val path = Path().apply {
                                        moveTo(corners[0].x * scale, corners[0].y * scale)
                                        corners.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
                                        close()
                                    }
                                    drawPath(path, Accent.copy(alpha = 0.15f))
                                    drawPath(path, Accent, style = Stroke(width = 2.dp.toPx()))
                                    corners.forEach {
                                        drawCircle(Color.White, 12.dp.toPx(), Offset(it.x * scale, it.y * scale))
                                        drawCircle(Accent, 12.dp.toPx(), Offset(it.x * scale, it.y * scale), style = Stroke(3.dp.toPx()))
                                    }
                                }
                                RegionTool.ERASE, RegionTool.MOSAIC -> {
                                    val fill = if (tool == RegionTool.ERASE) Color.White.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.7f)
                                    boxes.forEach { r ->
                                        drawRect(fill, Offset(r.left * scale, r.top * scale), Size(r.width() * scale, r.height() * scale))
                                        drawRect(Accent, Offset(r.left * scale, r.top * scale), Size(r.width() * scale, r.height() * scale), style = Stroke(1.5.dp.toPx()))
                                    }
                                    val a = dragStart
                                    val b = dragNow
                                    if (a != null && b != null) {
                                        drawRect(
                                            Accent,
                                            Offset(min(a.x, b.x), min(a.y, b.y)),
                                            Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)),
                                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                                        )
                                    }
                                }
                                RegionTool.TEXT -> textAt?.let { at ->
                                    val size = textSize * imgW * scale
                                    if (text.isNotBlank()) {
                                        drawIntoCanvas { c -> PageComposer.drawText(c.nativeCanvas, text, at.x * scale, at.y * scale, size, textLook) }
                                        // dashed frame showing what can be dragged
                                        val b = PageComposer.textBounds(text, at.x * scale, at.y * scale, size, textLook)
                                        val pad = size * 0.25f + 4.dp.toPx()
                                        drawRect(
                                            Accent,
                                            Offset(b.left - pad, b.top - pad),
                                            Size(b.width() + 2 * pad, b.height() + 2 * pad),
                                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                                        )
                                    } else {
                                        drawCircle(Accent, 5.dp.toPx(), Offset(at.x * scale, at.y * scale))
                                    }
                                }
                            }
                        }
                    }
                }
                // Tool-specific controls
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    when (tool) {
                        RegionTool.CROP -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                busy.run(S.str(R.string.detecting_page)) {
                                    val detected = withContext(Dispatchers.Default) { DocumentDetector.detect(BitmapUtils.decode(file)) }
                                    corners.clear()
                                    corners.addAll(detected ?: fullCorners())
                                }
                            }) { Text(S.str(R.string.auto_detect_2)) }
                            OutlinedButton(onClick = { corners.clear(); corners.addAll(fullCorners()) }) { Text(S.str(R.string.full_page)) }
                        }
                        RegionTool.TEXT -> TextStylePanel(
                            hasText = text.isNotBlank(),
                            onEditText = { askText = true },
                            size = textSize,
                            onSize = { textSize = it },
                            look = textLook,
                            onLook = { textLook = it },
                        )
                        else -> Text(
                            S.str(R.string.x_area_s_marked, boxes.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        BusyOverlay(busy)
    }

    if (askText) {
        TextInputDialog(S.str(R.string.add_text_2), text, S.str(R.string.text), S.str(R.string.ok), onDismiss = { askText = false }) {
            askText = false
            text = it
        }
    }
}

private fun scaled(boxes: List<RectF>, s: Float): List<RectF> = boxes.map { RectF(it.left * s, it.top * s, it.right * s, it.bottom * s) }
