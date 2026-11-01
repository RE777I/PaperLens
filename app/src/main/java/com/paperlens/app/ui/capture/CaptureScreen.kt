package com.paperlens.app.ui.capture

import com.paperlens.app.S
import com.paperlens.app.R

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlin.math.min
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.paperlens.app.data.CaptureSidecar
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Image
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.DocumentDetector
import com.paperlens.app.imaging.PageOutline
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

internal val Accent = Color(0xFF14A38B)

/** Camera modes, in carousel order. */
enum class CaptureMode(private val labelRes: Int, val batchByDefault: Boolean = false) {
    EXTRACT_TEXT(R.string.extract_text),
    TRANSLATE(R.string.translate),
    TO_WORD(R.string.to_word),
    SIGN(R.string.sign),
    SCAN(R.string.scan, batchByDefault = true),
    SMART_ERASE(R.string.smart_erase),
    ID_CARD(R.string.id_card, batchByDefault = true),
    BOOK(R.string.book, batchByDefault = true),
    TO_EXCEL(R.string.to_excel),
    WHITEBOARD(R.string.whiteboard),
    TIMESTAMP(R.string.timestamp),
    SCAN_CODE(R.string.scan_code),
    ;
    val label: String get() = S.str(labelRes)


    val hint: String
        get() = when (this) {
            ID_CARD -> S.str(R.string.front_side_first_then_the_back)
            BOOK -> S.str(R.string.capture_the_open_book_both_pages_at_once)
            SCAN_CODE -> ""
            else -> label
        }
}

/** A photo waiting in the crop review: full-size, a screen-size copy, and the detected corners. */
private class PendingShot(val photo: Bitmap, val preview: Bitmap, val outline: PageOutline?)

/** Decodes a fresh capture (and deletes the file) and finds the page in it. */
private fun pendingShot(raw: File): PendingShot {
    val photo = BitmapUtils.decode(raw)
    raw.delete()
    return PendingShot(photo, BitmapUtils.scaleDown(photo, 1600), DocumentDetector.detectOutline(photo))
}

/** True when [corners] are the photo's own corners ("Full Page"), so no warp is needed. */
private fun isWholePhoto(corners: List<PointF>, photo: Bitmap): Boolean {
    val w = photo.width.toFloat()
    val h = photo.height.toFloat()
    val expected = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(w, 0f), floatArrayOf(w, h), floatArrayOf(0f, h))
    for (i in 0 until 4) {
        val dx: Float = corners[i].x - expected[i][0]
        val dy: Float = corners[i].y - expected[i][1]
        if (dx * dx + dy * dy > 1f) return false
    }
    return true
}

private enum class Flash(val mode: Int) { OFF(ImageCapture.FLASH_MODE_OFF), AUTO(ImageCapture.FLASH_MODE_AUTO), ON(ImageCapture.FLASH_MODE_ON) }

/**
 * Full-screen in-app camera: live page outline, Single / Batch, the mode carousel, and
 * All Features / Import Images / Import Files. Each shot is auto-cropped to the page.
 * [onFinish] gets the mode and the cropped page images; [onFallback] asks for the Google scanner
 * (camera permission refused).
 */
@Composable
fun CaptureScreen(
    initialMode: CaptureMode,
    onFinish: (CaptureMode, List<Uri>) -> Unit,
    onCancel: () -> Unit,
    onFallback: (CaptureMode) -> Unit,
    onAllFeatures: () -> Unit,
    onImportImages: () -> Unit,
    onImportFiles: () -> Unit,
    onScanCode: () -> Unit,
    singleShot: Boolean = false,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var mode by remember { mutableStateOf(initialMode) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            context.toast(S.str(R.string.camera_permission_refused_using_the_google_d))
            onFallback(mode)
        }
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    // Drawn over the whole app window (the host sits above every screen), so system-bar insets apply.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        if (granted) {
            CameraContent(
                initialMode = mode,
                onModeChange = { mode = it },
                onFinish = onFinish,
                onCancel = onCancel,
                onAllFeatures = onAllFeatures,
                onImportImages = onImportImages,
                onImportFiles = onImportFiles,
                onScanCode = onScanCode,
                singleShot = singleShot,
            )
        } else {
            BackHandler { onCancel() }
        }
    }
}

@Composable
private fun CameraContent(
    initialMode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    onFinish: (CaptureMode, List<Uri>) -> Unit,
    onCancel: () -> Unit,
    onAllFeatures: () -> Unit,
    onImportImages: () -> Unit,
    onImportFiles: () -> Unit,
    onScanCode: () -> Unit,
    singleShot: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initialMode) }
    var batch by remember { mutableStateOf(!singleShot && initialMode.batchByDefault) }
    var flash by remember { mutableStateOf(Flash.OFF) }
    var busy by remember { mutableStateOf(false) }
    val shots = remember { mutableStateListOf<Shot>() }
    // Pages are cropped one at a time in the background so the shutter is free again at once.
    val processing = remember { Mutex() }
    // Shutter feedback: a white flash, the frozen frame flying into the tray, or (single shots) the
    // frozen frame with a spinner while the page is found.
    val flashAlpha = remember { Animatable(0f) }
    val flight = remember { Animatable(0f) }
    var flying by remember { mutableStateOf<ImageBitmap?>(null) }
    var flightTarget by remember { mutableIntStateOf(0) }
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var previewRect by remember { mutableStateOf(Rect.Zero) }
    var trayRect by remember { mutableStateOf(Rect.Zero) }
    // Live outline in analysed-frame pixels (steadied by the tracker), plus that frame's size.
    var outline by remember { mutableStateOf<PageOutline?>(null) }
    var frameW by remember { mutableIntStateOf(3) }
    var frameH by remember { mutableIntStateOf(4) }
    // Auto-capture: shoot once the page has held still; then wait for a new page before shooting again.
    var autoCapture by rememberSaveable { mutableStateOf(true) }
    var stillFrames by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(true) }
    var autoFire by remember { mutableStateOf(false) }
    val tracker = remember { LiveTracker() }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            var last = 0L
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val now = System.currentTimeMillis()
                if (now - last > 350) {
                    last = now
                    runCatching {
                        val frame = BitmapUtils.rotate(BitmapUtils.scaleDown(proxy.toBitmap(), 480), proxy.imageInfo.rotationDegrees)
                        val steady = tracker.update(DocumentDetector.detectOutline(frame), frame.width, frame.height)
                        frameW = frame.width
                        frameH = frame.height
                        outline = steady
                        stillFrames = tracker.stableFrames
                        // A new page (or none) arms the next auto shot.
                        if (steady == null || tracker.lastMove > 0.15f) armed = true
                        if (autoCapture && armed && steady != null && tracker.stableFrames >= STILL_FRAMES_TO_SHOOT) {
                            armed = false
                            autoFire = true
                        }
                    }
                }
                proxy.close()
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            }.onFailure { context.toast(S.str(R.string.camera_unavailable_x, it.message)) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }
    LaunchedEffect(flash) { imageCapture.flashMode = flash.mode }

    /** Hands the pages over once every shot has been processed (a "Preparing" overlay shows meanwhile). */
    fun finish() {
        if (shots.isNotEmpty()) finishing = true
    }
    LaunchedEffect(finishing) {
        if (!finishing) return@LaunchedEffect
        snapshotFlow { shots.all { it.page != null } }.first { it }
        onFinish(mode, shots.mapNotNull { it.page })
    }

    // Single shots go through the crop review before they are kept.
    var review by remember { mutableStateOf<PendingShot?>(null) }

    fun afterShot() {
        val needed = if (mode == CaptureMode.ID_CARD) 2 else 1
        when {
            !batch -> finish()
            mode == CaptureMode.ID_CARD && shots.size == 1 -> context.toast(S.str(R.string.now_turn_the_card_over_and_capture_the_back))
            shots.size >= needed && mode == CaptureMode.ID_CARD -> finish()
        }
    }

    fun capture() {
        if (busy || finishing) return
        busy = true
        val reviewIt = mode != CaptureMode.BOOK && (!batch || mode == CaptureMode.ID_CARD)
        val frame = runCatching { previewView.bitmap?.asImageBitmap() }.getOrNull()
        scope.launch {
            flashAlpha.snapTo(0.75f)
            flashAlpha.animateTo(0f, tween(260))
        }
        val shot = Shot(frame)
        if (reviewIt) {
            frozen = frame
        } else {
            // The frozen frame shrinks into its tray slot; the cropped page replaces it when ready.
            flightTarget = shots.size
            flying = frame
            shots += shot
            scope.launch {
                flight.snapTo(0f)
                flight.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                flying = null
            }
        }
        val raw = File(context.cacheDir, "capture/raw_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (reviewIt) {
                        scope.launch {
                            review = withContext(Dispatchers.Default) { pendingShot(raw) }
                            frozen = null
                            busy = false
                        }
                    } else {
                        busy = false
                        val crop = mode != CaptureMode.BOOK
                        scope.launch {
                            val page = withContext(Dispatchers.Default) { processing.withLock { processShot(context, raw, crop) } }
                            shot.page = Uri.fromFile(page)
                        }
                        afterShot()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    frozen = null
                    shots.remove(shot)
                    context.toast(S.str(R.string.capture_failed_x, exception.message))
                }
            },
        )
    }

    LaunchedEffect(autoFire) {
        if (autoFire) {
            autoFire = false
            if (review == null && frozen == null && !finishing) capture()
        }
    }

    val pending = review
    if (pending != null) {
        CropReview(
            preview = pending.preview,
            fullWidth = pending.photo.width,
            fullHeight = pending.photo.height,
            detected = pending.outline,
            onRetake = { review = null },
            onConfirm = { outline ->
                review = null
                val shot = Shot(pending.preview.asImageBitmap())
                shots += shot
                scope.launch {
                    val page = withContext(Dispatchers.Default) { processing.withLock { saveReviewed(context, pending.photo, outline) } }
                    shot.page = Uri.fromFile(page)
                }
                afterShot()
            },
        )
        return
    }

    BackHandler { onCancel() }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
    Column(Modifier.fillMaxSize()) {
        // Top bar
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
                    .clickable { autoCapture = !autoCapture; armed = true }
                    .semantics { contentDescription = if (autoCapture) S.str(R.string.auto_capture_on) else S.str(R.string.auto_capture_off) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { flash = Flash.entries[(flash.ordinal + 1) % Flash.entries.size] }) {
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

        // Preview + outline + mode hint + Single/Batch
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(2.dp, Accent)
                .onGloballyPositioned { previewRect = it.boundsInRoot() },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            frozen?.let { still ->
                Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Text(S.str(R.string.finding_the_page), color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
            val density = LocalDensity.current
            val viewW = with(density) { maxWidth.toPx() }
            val viewH = with(density) { maxHeight.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                val shape = outline ?: return@Canvas
                // FIT_CENTER: the frame is letter-boxed inside the view.
                val k = min(viewW / frameW, viewH / frameH)
                val ox = (viewW - frameW * k) / 2
                val oy = (viewH - frameH * k) / 2
                val path = Path()
                for (side in 0 until 4) for (i in 0 until 10) {
                    val p = shape.sidePoint(side, i / 10f)
                    if (side == 0 && i == 0) path.moveTo(ox + p.x * k, oy + p.y * k) else path.lineTo(ox + p.x * k, oy + p.y * k)
                }
                path.close()
                val ready = autoCapture && stillFrames >= STILL_FRAMES_TO_SHOOT - 1
                drawPath(path, Accent.copy(alpha = if (ready) 0.3f else 0.18f))
                drawPath(path, Accent, style = Stroke(width = 3.dp.toPx()))
            }
            // Guidance, like the Google and vFlat scanners
            if (autoCapture && frozen == null) {
                val tip = when {
                    outline == null -> S.str(R.string.looking_for_a_page)
                    !armed -> S.str(R.string.turn_to_the_next_page)
                    stillFrames < STILL_FRAMES_TO_SHOOT -> S.str(R.string.hold_still)
                    else -> S.str(R.string.capturing)
                }
                Text(
                    tip,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (mode.hint.isNotEmpty() && shots.isEmpty() && outline == null) {
                Text(
                    mode.hint,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            // Single / Batch
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    .padding(4.dp),
            ) {
                listOf(false to S.str(R.string.single), true to S.str(R.string.batch)).forEach { (isBatch, label) ->
                    Text(
                        label,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .background(if (batch == isBatch) Color.White.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(20.dp))
                            .clickable { batch = isBatch }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // Mode carousel
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
                        modifier = Modifier.clickable {
                            if (m == CaptureMode.SCAN_CODE) {
                                onScanCode()
                            } else {
                                mode = m
                                batch = m.batchByDefault
                                shots.clear()
                                onModeChange(m)
                            }
                        },
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

        // Batch tray: where captured pages land (reserved in batch mode so the first one has a target).
        if (shots.isNotEmpty() || batch) {
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
                        .onGloballyPositioned { trayRect = it.boundsInRoot() },
                    horizontalArrangement = Arrangement.spacedBy(TraySpacing),
                ) {
                    itemsIndexed(shots) { i, shot ->
                        // Hidden while its frozen frame is still flying in.
                        val landed = flying == null || i != flightTarget
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
                if (shots.isNotEmpty()) Surface(onClick = ::finish, shape = RoundedCornerShape(20.dp), color = Accent) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = Color.White)
                        Text(" ${shots.size}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        // Bottom controls: the two sides get equal widths so the shutter sits exactly in the middle.
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
                        val progress = if (autoCapture && armed && outline != null) stillFrames.toFloat() / STILL_FRAMES_TO_SHOOT else 0f
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
                    .clickable(enabled = !busy) { capture() }
                    .semantics { contentDescription = S.str(R.string.take_picture) },
            )
            Row(Modifier.weight(1f)) {
                BottomButton(S.str(R.string.import_images), Icons.Filled.PhotoLibrary, onImportImages, Modifier.weight(1f))
                BottomButton(S.str(R.string.import_files), Icons.Filled.DriveFolderUpload, onImportFiles, Modifier.weight(1f))
            }
        }
    }

    // Shutter flash
    if (flashAlpha.value > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = flashAlpha.value }
                .background(Color.White),
        )
    }
    // The frozen frame shrinking from the preview into its tray slot
    flying?.let { still ->
        val density = LocalDensity.current
        val slot = with(density) {
            val w = TrayWidth.toPx()
            val h = TrayHeight.toPx()
            val left = trayRect.left + flightTarget * (w + TraySpacing.toPx())
            Rect(left, trayRect.top + (trayRect.height - h) / 2, left + w, trayRect.top + (trayRect.height + h) / 2)
        }
        val t = flight.value
        val from = previewRect.translate(-rootOrigin)
        val to = slot.translate(-rootOrigin)
        val box = Rect(
            lerp(from.left, to.left, t),
            lerp(from.top, to.top, t),
            lerp(from.right, to.right, t),
            lerp(from.bottom, to.bottom, t),
        )
        Image(
            still,
            null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                .border(2.dp, Color.White),
        )
    }
    // Waiting for the last pages after Done
    if (finishing) {
        val ready = shots.count { it.page != null }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    S.str(R.string.preparing_pages_x_x, ready, shots.size),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
    }
}

/** Analysed frames (about 350 ms apart) the page must hold still before an auto shot. */
private const val STILL_FRAMES_TO_SHOOT = 3

private val TrayWidth = 44.dp
private val TrayHeight = 56.dp
private val TraySpacing = 6.dp

/** One captured page in the tray: the frozen frame at once, the processed page when ready. */
private class Shot(val thumb: ImageBitmap?) {
    var page by mutableStateOf<Uri?>(null)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Crops a fresh capture to the page it finds (unless [crop] is false) and saves it for the document. */
private fun processShot(context: android.content.Context, raw: File, crop: Boolean): File {
    val photo = BitmapUtils.decode(raw)
    raw.delete()
    val outline = if (crop) DocumentDetector.detectOutline(photo) else null
    val page = File(context.cacheDir, "capture/page_${UUID.randomUUID()}.jpg")
    BitmapUtils.saveJpeg(outline?.let { DocumentDetector.warp(photo, it) } ?: photo, page, 92)
    if (outline != null) CaptureSidecar.write(page, photo, outline)
    return page
}

/** Saves a single shot cropped to the corners chosen in the review. */
private fun saveReviewed(context: android.content.Context, photo: Bitmap, outline: PageOutline): File {
    val page = File(context.cacheDir, "capture/page_${UUID.randomUUID()}.jpg")
    if (isWholePhoto(outline.corners, photo) && !outline.curved) {
        BitmapUtils.saveJpeg(photo, page, 92)
    } else {
        BitmapUtils.saveJpeg(DocumentDetector.warp(photo, outline), page, 92)
        CaptureSidecar.write(page, photo, outline)
    }
    return page
}

@Composable
private fun BottomButton(
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
