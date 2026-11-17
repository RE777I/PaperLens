package com.paperlens.app.ui.draw

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.saveSignature
import com.paperlens.app.data.signatures
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.toast
import java.io.File
import kotlin.math.roundToInt

private val inkColors = listOf(Color.Black, Color(0xFF0D47A1), Color(0xFFC62828), Color(0xFF2E7D32))

/** Pick or draw a signature, then drag / pinch it into place on the page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureScreen(pageId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val busy = rememberBusy()
    val page by remember(pageId) { repo.observePage(pageId) }.collectAsStateWithLifecycle(initialValue = null)

    var refresh by remember { mutableIntStateOf(0) }
    val signatures = remember(refresh) { repo.signatures() }
    var selected by remember { mutableStateOf<File?>(null) }
    var showPad by remember { mutableStateOf(false) }

    // placement as fractions of the page
    var left by remember { mutableFloatStateOf(0.55f) }
    var top by remember { mutableFloatStateOf(0.8f) }
    var widthFraction by remember { mutableFloatStateOf(0.35f) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { if (selected != null) selected = null else onDone() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back))
                        }
                    },
                    title = { Text(if (selected == null) S.str(R.string.choose_signature) else S.str(R.string.place_signature)) },
                    actions = {
                        val sig = selected
                        val current = page
                        if (sig != null && current != null) {
                            IconButton(onClick = {
                                busy.run(S.str(R.string.signing)) {
                                    val overlay = BitmapFactory.decodeFile(sig.path)
                                    repo.bakeEdit(current) { PageComposer.overlay(it, overlay, left, top, widthFraction) }
                                    onDone()
                                }
                            }) { Icon(Icons.Filled.Check, S.str(R.string.apply)) }
                        }
                    },
                )
            },
        ) { padding ->
            val sig = selected
            val current = page
            if (sig == null) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    item {
                        OutlinedButton(onClick = { showPad = true }, modifier = Modifier.height(96.dp)) {
                            Icon(Icons.Filled.Add, null)
                            Text(S.str(R.string.new_signature))
                        }
                    }
                    items(signatures, key = { it.path }) { file ->
                        Card(Modifier.clickable { selected = file }) {
                            Box(Modifier.background(Color.White)) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = S.str(R.string.saved_signature),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .padding(8.dp),
                                )
                                IconButton(
                                    onClick = { file.delete(); refresh++ },
                                    modifier = Modifier.align(Alignment.TopEnd),
                                ) { Icon(Icons.Filled.Delete, S.str(R.string.delete_signature), tint = Color.Gray) }
                            }
                        }
                    }
                }
            } else if (current != null) {
                val sigBitmap = remember(sig) { BitmapFactory.decodeFile(sig.path).asImageBitmap() }
                Column(Modifier.padding(padding)) {
                    Text(
                        S.str(R.string.drag_to_move_pinch_to_resize),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FittedPage(File(current.imagePath)) { boxSize ->
                        val density = LocalDensity.current
                        Image(
                            bitmap = sigBitmap,
                            contentDescription = S.str(R.string.signature),
                            modifier = Modifier
                                .offset { IntOffset((left * boxSize.width).roundToInt(), (top * boxSize.height).roundToInt()) }
                                .width(with(density) { (widthFraction * boxSize.width).toDp() })
                                .aspectRatio(sigBitmap.width.toFloat() / sigBitmap.height)
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary))
                                .pointerInput(boxSize) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        widthFraction = (widthFraction * zoom).coerceIn(0.05f, 1f)
                                        left = (left + pan.x / boxSize.width).coerceIn(0f, 1f - widthFraction)
                                        top = (top + pan.y / boxSize.height).coerceIn(0f, 0.97f)
                                    }
                                },
                        )
                    }
                }
            }
        }
        BusyOverlay(busy)
    }

    if (showPad) {
        SignaturePadDialog(
            onDismiss = { showPad = false },
            onSave = { state ->
                if (state.isEmpty) {
                    context.toast(S.str(R.string.draw_your_signature_first))
                } else {
                    showPad = false
                    selected = repo.saveSignature(state.toBitmap(outWidth = 1200, cropToInk = true))
                    refresh++
                }
            },
        )
    }
}

@Composable
private fun SignaturePadDialog(onDismiss: () -> Unit, onSave: (DrawingState) -> Unit) {
    val state = remember { DrawingState() }
    var color by remember { mutableStateOf(inkColors.first()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(S.str(R.string.sign_below), style = MaterialTheme.typography.titleLarge)
                DrawingCanvas(
                    state = state,
                    color = color,
                    strokeWidth = 8f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                        .background(Color.White)
                        .border(1.dp, Color.LightGray),
                )
                ColorRow(selected = color, onSelect = { color = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { state.clear() }) { Text(S.str(R.string.clear)) }
                    OutlinedButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) }
                    Button(onClick = { onSave(state) }) { Text(S.str(R.string.save_2)) }
                }
            }
        }
    }
}

/** Freehand pen / highlighter on top of the page; the ink is merged into the page image on save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(pageId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val busy = rememberBusy()
    val page by remember(pageId) { repo.observePage(pageId) }.collectAsStateWithLifecycle(initialValue = null)
    val state = remember { DrawingState() }
    var color by remember { mutableStateOf(inkColors[2]) }
    var width by remember { mutableFloatStateOf(6f) }
    var highlighter by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                    title = { Text(S.str(R.string.annotate)) },
                    actions = {
                        IconButton(onClick = { state.undo() }, enabled = !state.isEmpty) {
                            Icon(Icons.AutoMirrored.Filled.Undo, S.str(R.string.undo))
                        }
                        val current = page
                        IconButton(enabled = current != null && !state.isEmpty, onClick = {
                            if (current == null) return@IconButton
                            busy.run(S.str(R.string.saving)) {
                                val imageWidth = BitmapUtils.imageSize(File(current.imagePath)).first
                                    .coerceAtMost(BitmapUtils.MAX_PAGE_DIMENSION)
                                val ink = state.toBitmap(outWidth = imageWidth, cropToInk = false)
                                repo.bakeEdit(current) { PageComposer.overlay(it, ink, 0f, 0f, 1f) }
                                onDone()
                            }
                        }) { Icon(Icons.Filled.Check, S.str(R.string.save_2)) }
                    },
                )
            },
        ) { padding ->
            val current = page ?: return@Scaffold
            Column(Modifier.padding(padding)) {
                FittedPage(File(current.imagePath)) { _ ->
                    DrawingCanvas(
                        state = state,
                        color = if (highlighter) color.copy(alpha = 0.35f) else color,
                        strokeWidth = if (highlighter) width * 4 else width,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ColorRow(selected = color, onSelect = { color = it }, extra = listOf(Color(0xFFFFEB3B)))
                        OutlinedButton(onClick = { highlighter = !highlighter }) { Text(if (highlighter) S.str(R.string.highlighter) else S.str(R.string.pen)) }
                    }
                    Slider(value = width, onValueChange = { width = it }, valueRange = 2f..24f)
                }
            }
        }
        BusyOverlay(busy)
    }
}

@Composable
private fun ColorRow(selected: Color, onSelect: (Color) -> Unit, extra: List<Color> = emptyList()) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        (inkColors + extra).forEach { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .background(c, CircleShape)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape)
                    .clickable { onSelect(c) },
            )
        }
    }
}

/**
 * Shows the page image letter-boxed into the remaining space, with [overlay] laid over exactly
 * the image area. [overlay] receives the pixel size of that area.
 */
@Composable
private fun ColumnScope.FittedPage(file: File, overlay: @Composable (IntSize) -> Unit) {
    val (w, h) = remember(file) { BitmapUtils.imageSize(file) }
    val ratio = if (w > 0 && h > 0) w.toFloat() / h else 0.707f
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(Color(0xFF303030)),
        contentAlignment = Alignment.Center,
    ) {
        val boxWidth = minOf(maxWidth, maxHeight * ratio)
        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .width(boxWidth)
                .aspectRatio(ratio)
                .onSizeChanged { boxSize = it },
        ) {
            AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            if (boxSize != IntSize.Zero) overlay(boxSize)
        }
    }
}
