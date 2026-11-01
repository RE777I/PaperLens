package com.paperlens.app.ui.page

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.BoxWithConstraints
import com.paperlens.app.ui.components.PanZoom
import com.paperlens.app.ui.components.panZoom
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.print.PrintHelper
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.geometry.Size
import com.paperlens.app.app
import com.paperlens.app.data.DocumentEntity
import com.paperlens.app.data.PageEntity
import com.paperlens.app.data.imageFilter
import com.paperlens.app.data.pdfFile
import com.paperlens.app.ui.components.PdfZoomLayer
import com.paperlens.app.export.Sharing
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.ConfirmDialog
import com.paperlens.app.ui.components.OcrTextSheet
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.rememberBusy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(
    documentId: Long,
    initialIndex: Int,
    onBack: () -> Unit,
    openSignature: (pageId: Long) -> Unit,
    openAnnotate: (pageId: Long) -> Unit,
    openEditText: (index: Int) -> Unit,
    openCrop: (pageId: Long) -> Unit,
    openTranslate: (index: Int) -> Unit,
) {
    val context = LocalContext.current
    val repo = context.app.repository
    val busy = rememberBusy()
    val scope = rememberCoroutineScope()

    val pages by remember(documentId) { repo.observePages(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    val loaded = pages ?: return
    if (loaded.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, loaded.lastIndex)) { loaded.size }
    val index = pager.currentPage.coerceIn(0, loaded.lastIndex)
    val page = loaded[index]

    var zoomed by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var ocrPage by remember { mutableStateOf<PageEntity?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showWatermark by remember { mutableStateOf(false) }
    var moveTargets by remember { mutableStateOf<List<DocumentEntity>?>(null) }

    fun edit(filter: ImageFilter = page.imageFilter, rotation: Int = page.rotation) {
        busy.run(S.str(R.string.applying_x, filter.label)) { repo.editPage(page, filter, rotation) }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                    title = { Text(S.str(R.string.page_x_of_x, index + 1, loaded.size)) },
                    actions = {
                        IconButton(onClick = {
                            busy.run(S.str(R.string.preparing)) { Sharing.share(context, listOf(File(page.imagePath)), "image/jpeg") }
                        }) { Icon(Icons.Filled.Share, S.str(R.string.share_page)) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, S.str(R.string.more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(S.str(R.string.move_earlier)) },
                                    leadingIcon = { Icon(Icons.Filled.ChevronLeft, null) },
                                    enabled = index > 0,
                                    onClick = {
                                        menuOpen = false
                                        busy.run(S.str(R.string.moving)) {
                                            repo.movePage(documentId, index, index - 1)
                                            pager.scrollToPage(index - 1)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(S.str(R.string.move_later)) },
                                    leadingIcon = { Icon(Icons.Filled.ChevronRight, null) },
                                    enabled = index < loaded.lastIndex,
                                    onClick = {
                                        menuOpen = false
                                        busy.run(S.str(R.string.moving)) {
                                            repo.movePage(documentId, index, index + 1)
                                            pager.scrollToPage(index + 1)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(S.str(R.string.move_to_another_document)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { moveTargets = repo.getAllDocuments().filter { it.id != documentId } }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(S.str(R.string.print_page)) },
                                    leadingIcon = { Icon(Icons.Filled.Print, null) },
                                    onClick = {
                                        menuOpen = false
                                        busy.run(S.str(R.string.preparing_print)) {
                                            val bitmap = withContext(Dispatchers.Default) { BitmapUtils.decode(File(page.imagePath)) }
                                            PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
                                                .printBitmap(S.str(R.string.paperlens_page), bitmap)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(S.str(R.string.delete_page)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { menuOpen = false; showDelete = true },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        items(ImageFilter.entries) { filter ->
                            FilterChip(
                                selected = page.imageFilter == filter,
                                onClick = { if (page.imageFilter != filter) edit(filter = filter) },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                    BottomAppBar {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            PageAction(S.str(R.string.crop), Icons.Filled.Crop) { openCrop(page.id) }
                            PageAction(S.str(R.string.left), Icons.AutoMirrored.Filled.RotateLeft) { edit(rotation = page.rotation - 90) }
                            PageAction(S.str(R.string.right), Icons.AutoMirrored.Filled.RotateRight) { edit(rotation = page.rotation + 90) }
                            PageAction(S.str(R.string.text), Icons.Filled.TextFields) {
                                val current = page
                                if (current.ocrText != null) {
                                    ocrPage = current
                                } else {
                                    busy.run(S.str(R.string.recognising_text)) {
                                        val text = repo.ocrPage(current)
                                        ocrPage = current.copy(ocrText = text)
                                    }
                                }
                            }
                            PageAction(S.str(R.string.edit_text), Icons.Filled.EditNote) { openEditText(index) }
                            PageAction(S.str(R.string.translate), Icons.Filled.Translate) { openTranslate(index) }
                            PageAction(S.str(R.string.sign), Icons.Filled.Draw) { openSignature(page.id) }
                            PageAction(S.str(R.string.annotate), Icons.Filled.Gesture) { openAnnotate(page.id) }
                            PageAction(S.str(R.string.watermark), Icons.AutoMirrored.Filled.BrandingWatermark) { showWatermark = true }
                        }
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { loaded.getOrNull(it)?.id ?: it },
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color(0xFF303030)),
            ) { i ->
                val p = loaded.getOrNull(i) ?: return@HorizontalPager
                ZoomableImage(
                    file = File(p.imagePath),
                    pdf = p.pdfFile,
                    onZoomChanged = { if (i == pager.currentPage) zoomed = it },
                )
            }
        }
        BusyOverlay(busy)
    }

    ocrPage?.let { target ->
        OcrTextSheet(
            title = S.str(R.string.page_x_text, index + 1),
            text = target.ocrText.orEmpty(),
            onDismiss = { ocrPage = null },
            onSave = { edited ->
                ocrPage = null
                busy.run(S.str(R.string.saving)) {
                    repo.getPage(target.id)?.let { latest -> repo.setOcrText(latest, edited) }
                }
            },
        )
    }

    if (showDelete) {
        ConfirmDialog(S.str(R.string.delete_this_page), S.str(R.string.the_page_is_removed_from_this_document), S.str(R.string.delete), onDismiss = { showDelete = false }) {
            showDelete = false
            busy.run(S.str(R.string.deleting)) { repo.deletePage(page) }
        }
    }

    if (showWatermark) {
        TextInputDialog(S.str(R.string.watermark), "", S.str(R.string.watermark_text), S.str(R.string.apply), onDismiss = { showWatermark = false }) { text ->
            showWatermark = false
            if (text.isNotBlank()) busy.run(S.str(R.string.adding_watermark)) { repo.bakeEdit(page) { PageComposer.watermark(it, text) } }
        }
    }

    moveTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { moveTargets = null },
            title = { Text(S.str(R.string.move_page_to)) },
            text = {
                if (targets.isEmpty()) {
                    Text(S.str(R.string.there_are_no_other_documents))
                } else {
                    LazyColumn {
                        items(targets) { doc ->
                            Text(
                                doc.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        moveTargets = null
                                        busy.run(S.str(R.string.moving)) { repo.movePageToDocument(page, doc.id) }
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveTargets = null }) { Text(S.str(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PageAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Double-tap to zoom, pinch to zoom (about the fingers), drag to pan while zoomed. */
@Composable
private fun ZoomableImage(file: File, pdf: File?, onZoomChanged: (Boolean) -> Unit) {
    val zoom = remember(file) { PanZoom() }
    val zoomed = zoom.zoomed
    LaunchedEffect(zoomed) { onZoomChanged(zoomed) }
    // The page's pixel size, so panning stops at the page edges rather than the screen's.
    var imageSize by remember(file) { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(file) {
        val (w, h) = withContext(Dispatchers.IO) { BitmapUtils.imageSize(file) }
        imageSize = IntSize(w, h)
    }
    val inset = with(LocalDensity.current) { 8.dp.toPx() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .panZoom(zoom, file),
    ) {
        val box = Size(constraints.maxWidth - 2 * inset, constraints.maxHeight - 2 * inset)
        val content = imageSize?.takeIf { it.width > 0 && it.height > 0 }?.let { fitted(it, box) }
        zoom.content = content
        val scale = zoom.scale
        val layer = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            }
        // Screen-sized picture: quick to load and enough until the page is zoomed.
        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
        // Zoomed: the page at full resolution on top, so small print stays sharp instead of a magnified
        // screen-sized copy. Loaded on the first zoom and kept while zoomed; the screen copy shows meanwhile.
        if (scale > 1.05f) {
            val context = LocalContext.current
            val full = remember(file) { ImageRequest.Builder(context).data(file).size(coil.size.Size.ORIGINAL).build() }
            AsyncImage(model = full, contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
        }
        // Imported from a PDF: the visible part redrawn from the PDF itself, crisp at any zoom.
        if (pdf != null) Box(layer) { PdfZoomLayer(pdf, zoom, content, box) }
    }
}

/** Size of an image of [image] pixels shown with ContentScale.Fit in [box]. */
private fun fitted(image: IntSize, box: Size): Size {
    val s = minOf(box.width / image.width, box.height / image.height)
    return Size(image.width * s, image.height * s)
}
