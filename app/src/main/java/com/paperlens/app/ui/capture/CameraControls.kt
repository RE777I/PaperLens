package com.paperlens.app.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperlens.app.R
import com.paperlens.app.S
import kotlin.math.max

// Parts of the camera screen's layout, lifted out of [CameraContent]; the state stays there.

/** Close, Auto (auto-capture on/off) and the flash mode. */
@Composable
internal fun CameraTopBar(autoCapture: Boolean, flash: Flash, onCancel: () -> Unit, onToggleAuto: () -> Unit, onCycleFlash: () -> Unit) {
    Row(
        Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, S.str(R.string.close_camera), tint = Color.White) }
        Spacer(Modifier.weight(1f))
        Text(
            S.str(R.string.auto),
            color = if (autoCapture) Color.Black else Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (autoCapture) Accent else Color.White.copy(alpha = 0.15f))
                .clickable(onClick = onToggleAuto)
                .semantics { contentDescription = if (autoCapture) S.str(R.string.auto_capture_on) else S.str(R.string.auto_capture_off) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onCycleFlash) {
            Icon(
                when (flash) {
                    Flash.OFF -> Icons.Filled.FlashOff
                    Flash.AUTO -> Icons.Filled.FlashAuto
                    Flash.ON -> Icons.Filled.FlashOn
                },
                S.str(R.string.flash_x, flash.name.lowercase()),
                tint = Color.White,
            )
        }
    }
}

/** The modes in a row; tapping one picks it ([onPick] handles Scan Code separately). */
@Composable
internal fun ModeCarousel(initialMode: CaptureMode, mode: CaptureMode, onPick: (CaptureMode) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, CaptureMode.entries.indexOf(initialMode) - 2))
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.padding(vertical = 14.dp),
    ) {
        itemsIndexed(CaptureMode.entries) { _, m ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    m.label,
                    color = if (m == mode) Accent else Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (m == mode) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable { onPick(m) },
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(width = 22.dp, height = 3.dp)
                        .background(if (m == mode) Accent else Color.Transparent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** Captured pages in batch mode and the Done button; [hiddenIndex] is the slot a frame is still flying into. */
@Composable
internal fun BatchTray(shots: List<Shot>, hiddenIndex: Int?, onPlaced: (Rect) -> Unit, onDone: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            Modifier
                .weight(1f)
                .onGloballyPositioned { onPlaced(it.boundsInRoot()) },
            horizontalArrangement = Arrangement.spacedBy(TraySpacing),
        ) {
            itemsIndexed(shots) { i, shot ->
                // Hidden while its frozen frame is still flying in.
                val landed = i != hiddenIndex
                Box(
                    Modifier
                        .size(TrayWidth, TrayHeight)
                        .border(1.dp, Color.White)
                        .graphicsLayer { alpha = if (landed) 1f else 0f },
                ) {
                    val page = shot.page
                    if (page != null) {
                        AsyncImage(page, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        shot.thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        CircularProgressIndicator(
                            Modifier
                                .align(Alignment.Center)
                                .size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
        if (shots.isNotEmpty()) Surface(onClick = onDone, shape = RoundedCornerShape(20.dp), color = Accent) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = Color.White)
                Text(" ${shots.size}", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** All Features, the shutter (with the auto-capture countdown ring) and the import buttons. */
@Composable
internal fun CameraBottomControls(
    busy: Boolean,
    autoProgress: () -> Float,
    onShutter: () -> Unit,
    onAllFeatures: () -> Unit,
    onImportImages: () -> Unit,
    onImportFiles: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 12.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            BottomButton(S.str(R.string.all_features), Icons.Filled.Apps, onAllFeatures, Modifier.width(76.dp))
        }
        Box(
            Modifier
                .size(78.dp)
                .border(4.dp, Accent, CircleShape)
                .padding(8.dp)
                .background(if (busy) Color.Gray else Color.White, CircleShape)
                .drawWithContent {
                    drawContent()
                    // Fills while the page holds still before an auto shot.
                    val progress = autoProgress()
                    if (progress > 0f) {
                        val ring = 4.dp.toPx()
                        drawArc(
                            Accent,
                            -90f,
                            360f * progress.coerceAtMost(1f),
                            false,
                            topLeft = Offset(ring / 2, ring / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - ring, size.height - ring),
                            style = Stroke(ring),
                        )
                    }
                }
                .clickable(enabled = !busy, onClick = onShutter)
                .semantics { contentDescription = S.str(R.string.take_picture) },
        )
        Row(Modifier.weight(1f)) {
            BottomButton(S.str(R.string.import_images), Icons.Filled.PhotoLibrary, onImportImages, Modifier.weight(1f))
            BottomButton(S.str(R.string.import_files), Icons.Filled.DriveFolderUpload, onImportFiles, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun BottomButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(0.dp))
    }
}
