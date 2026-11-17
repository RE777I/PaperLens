package com.paperlens.app.ui.document

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.paperlens.app.app
import com.paperlens.app.data.PageEntity
import com.paperlens.app.ml.SearchableText
import com.paperlens.app.data.pdfFile
import com.paperlens.app.export.PdfPages
import com.paperlens.app.runLogged
import com.paperlens.app.ui.components.PdfZoomLayer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.paperlens.app.ui.components.PanZoom
import com.paperlens.app.ui.components.panZoom
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.min

/** A match: page index in the document and its index among that page's matches. */
private data class Hit(val page: Int, val index: Int)

/**
 * Find Text: a search bar over the document's pages, like Ctrl+F. Every match is highlighted on the
 * page images; the arrows step through them across pages, turning to the page and zooming onto the
 * match. Back closes the search and reports the page on screen so the document stays there.
 *
 * Stored `ocrText` answers the search straight away. Positions are not stored, so a page is read
 * again with boxes when it is shown with matches on it; pages with no text yet are read up front.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindTextScreen(pages: List<PageEntity>, startPage: Int, onClose: (page: Int) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) { pages.size }

    // Page id → its text: with boxes when positions were stored for the page's current image, otherwise
    // text only, replaced by a version with boxes once the page has been read with positions.
    val texts = remember {
        mutableStateMapOf<Long, SearchableText>().apply {
            pages.forEach { p ->
                val stored = SearchableText.fromLayout(p.ocrLayout, File(p.imagePath).name)
                (stored ?: p.ocrText?.let(SearchableText::fromText))?.let { put(p.id, it) }
            }
        }
    }
    val ocrLock = remember { Mutex() }
    var reading by remember { mutableIntStateOf(0) }
    var readTotal by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    /** Reads [page] with positions unless that is already done. */
    suspend fun readBoxes(page: PageEntity) = ocrLock.withLock {
        if (texts[page.id]?.hasBoxes == true) return@withLock
        try {
            // A page imported from a PDF has its own text with positions: no OCR needed.
            val fromPdf = page.pdfFile?.let { pdf ->
                withContext(Dispatchers.IO) { runLogged("FindText", "Reading the page's PDF text") { PDDocument.load(pdf).use { PdfPages.text(it, 0) } }.getOrNull() }
            }
            if (fromPdf != null) {
                texts[page.id] = fromPdf.searchable
                if (page.ocrText == null) app.repository.storeOcrText(page, fromPdf.plain)
            } else {
                val read = SearchableText.read(context, File(page.imagePath), app.settings.current().ocrScript)
                texts[page.id] = read.searchable
                // Positions are kept with the page, so the next search highlights without reading it again.
                app.repository.storeOcrText(page, read.plain, read.layout)
            }
        } catch (e: Exception) {
            if (!failed) context.toast(e.message ?: S.str(R.string.text_recognition_failed))
            failed = true
            if (texts[page.id] == null) texts[page.id] = SearchableText.fromText("")
        }
    }

    // Pages that were never read: read them now (with boxes, and keep their text for next time).
    LaunchedEffect(Unit) {
        val unread = pages.filter { it.ocrText == null }
        readTotal = unread.size
        unread.forEach { readBoxes(it); reading++ }
    }

    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(input) {
        delay(250)
        query = input
    }

    val found by remember {
        derivedStateOf { pages.map { p -> texts[p.id]?.find(query).orEmpty() } }
    }
    val total = found.sumOf { it.size }
    var hit by remember { mutableStateOf<Hit?>(null) }
    // Bumped on every arrow press, so the view returns to the match even when it is the same one.
    var focusTick by remember { mutableIntStateOf(0) }

    fun firstFrom(page: Int): Hit? {
        for (k in pages.indices) {
            val p = (page + k) % pages.size
            if (found[p].isNotEmpty()) return Hit(p, 0)
        }
        return null
    }

    fun show(h: Hit?) {
        hit = h
        focusTick++
        if (h != null && h.page != pager.currentPage) {
            scope.launch {
                if (kotlin.math.abs(h.page - pager.currentPage) > 2) pager.scrollToPage(h.page) else pager.animateScrollToPage(h.page)
            }
        }
    }

    // A new query starts from the page on screen.
    LaunchedEffect(query) { show(firstFrom(pager.currentPage)) }
    // Counts change as pages are read; keep the current match valid.
    LaunchedEffect(found) {
        val h = hit
        if (h == null) {
            if (query.isNotBlank()) firstFrom(pager.currentPage)?.let(::show)
        } else if (h.index >= found[h.page].size) {
            show(if (found[h.page].isNotEmpty()) Hit(h.page, found[h.page].size - 1) else firstFrom(h.page))
        }
    }

    fun step(forward: Boolean) {
        val h = hit ?: return show(firstFrom(pager.currentPage))
        val n = pages.size
        if (forward) {
            if (h.index + 1 < found[h.page].size) return show(Hit(h.page, h.index + 1))
            for (k in 1..n) {
                val p = (h.page + k) % n
                if (found[p].isNotEmpty()) return show(Hit(p, 0))
            }
        } else {
            if (h.index > 0) return show(Hit(h.page, h.index - 1))
            for (k in 1..n) {
                val p = ((h.page - k) % n + n) % n
                if (found[p].isNotEmpty()) return show(Hit(p, found[p].size - 1))
            }
        }
    }

    // Matches on the page on screen need boxes to be drawn.
    LaunchedEffect(pager.currentPage, query) {
        val page = pages.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        if (query.isNotBlank() && found[pager.currentPage].isNotEmpty()) readBoxes(page)
    }

    BackHandler { onClose(pager.currentPage) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        containerColor = Color(0xFF202124),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { onClose(pager.currentPage) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.close_search)) } },
                    title = {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(20.dp))
                                .padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                if (input.isEmpty()) Text(S.str(R.string.find_text_2), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                BasicTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); query = input }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focus),
                                )
                            }
                            if (input.isNotEmpty()) {
                                Text(
                                    if (total == 0) "0/0" else "${hit?.let { h -> found.take(h.page).sumOf { it.size } + h.index + 1 } ?: 0}/$total",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = { input = ""; query = "" }) { Icon(Icons.Filled.Close, S.str(R.string.clear)) }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { keyboard?.hide(); step(false) }, enabled = total > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, S.str(R.string.previous_match))
                        }
                        IconButton(onClick = { keyboard?.hide(); step(true) }, enabled = total > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, S.str(R.string.next_match))
                        }
                    },
                )
                if (reading < readTotal) {
                    LinearProgressIndicator(progress = { reading.toFloat() / readTotal }, modifier = Modifier.fillMaxWidth())
                    Text(
                        S.str(R.string.reading_text_page_x_of_x, reading + 1, readTotal),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            var zoomed by remember { mutableStateOf(false) }
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, key = { pages[it].id }) { i ->
                val page = pages[i]
                val text = texts[page.id]
                val matches = found.getOrElse(i) { emptyList() }
                val rects = if (text?.hasBoxes == true) matches.map { text.rects(it) } else emptyList()
                val current = hit?.takeIf { it.page == i }?.index
                FindPage(
                    file = File(page.imagePath),
                    pdf = page.pdfFile,
                    aspect = text?.aspect ?: 0f,
                    rects = rects,
                    current = current,
                    focusTick = focusTick,
                    onZoomChanged = { if (i == pager.currentPage) zoomed = it },
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                val label = when {
                    query.isBlank() -> S.str(R.string.page_x_x, pager.currentPage + 1, pages.size)
                    total == 0 && reading < readTotal -> S.str(R.string.searching)
                    total == 0 -> S.str(R.string.no_matches)
                    else -> S.str(R.string.page_x_x_x_here, pager.currentPage + 1, pages.size, found.getOrElse(pager.currentPage) { emptyList() }.size)
                }
                Text(label, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

/**
 * One page with its matches highlighted (the current one stronger). Pinch, double-tap and drag zoom
 * like the page viewer; when [current] changes (or [focusTick] does) the view zooms onto that match.
 */
@Composable
private fun FindPage(
    file: File,
    pdf: File?,
    aspect: Float,
    rects: List<List<android.graphics.RectF>>,
    current: Int?,
    focusTick: Int,
    onZoomChanged: (Boolean) -> Unit,
) {
    val zoom = remember(file) { PanZoom() }
    val zoomed = zoom.zoomed
    LaunchedEffect(zoomed) { onZoomChanged(zoomed) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = with(density) { 8.dp.toPx() }
        val box = Size(constraints.maxWidth - 2 * inset, constraints.maxHeight - 2 * inset)
        // Where the page image sits inside the box (ContentScale.Fit, centred), before zooming.
        val image = if (aspect <= 0f) null else {
            val w = if (box.width / box.height > aspect) box.height * aspect else box.width
            Size(w, w / aspect)
        }
        zoom.content = image

        // Zoom onto the current match: its text about 36 dp tall, at most most of the screen wide.
        val target = current?.let { rects.getOrNull(it) }?.takeIf { it.isNotEmpty() }
        LaunchedEffect(target, focusTick) {
            val img = image ?: return@LaunchedEffect
            val r = android.graphics.RectF(target?.first() ?: return@LaunchedEffect).apply { target.forEach { union(it) } }
            val left = (box.width - img.width) / 2
            val top = (box.height - img.height) / 2
            val mw = r.width() * img.width
            val mh = r.height() * img.height / target.size
            val s = min(with(density) { 36.dp.toPx() } / mh, box.width * 0.9f / mw).coerceIn(1f, 4f)
            val centre = Offset(left + r.centerX() * img.width, top + r.centerY() * img.height)
            val to = zoom.clamp(s, (Offset(box.width / 2, box.height / 2) - centre) * s)
            val fromScale = zoom.scale
            val fromOffset = zoom.offset
            animate(0f, 1f, animationSpec = tween(350)) { t, _ ->
                zoom.set(fromScale + (s - fromScale) * t, fromOffset + (to - fromOffset) * t)
            }
        }

        // Gestures zoom about the fingers; one-finger swipes still reach the pager while not zoomed.
        Box(
            Modifier
                .fillMaxSize()
                .panZoom(zoom, file),
        ) {
            val scale = zoom.scale
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
            ) {
                val context = LocalContext.current
                AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                // Full resolution on top once zoomed in, so small print stays sharp.
                val full = remember(file) { ImageRequest.Builder(context).data(file).size(coil.size.Size.ORIGINAL).build() }
                if (scale > 1.05f) AsyncImage(model = full, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (pdf != null) PdfZoomLayer(pdf, zoom, image, box)
                if (image != null && rects.isNotEmpty()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val left = (size.width - image.width) / 2
                        val top = (size.height - image.height) / 2
                        rects.forEachIndexed { k, match ->
                            val colour = if (k == current) Color(0xFFFF9800).copy(alpha = 0.55f) else Color(0xFFFFEB3B).copy(alpha = 0.45f)
                            match.forEach { r ->
                                val pad = 2f / scale
                                drawRect(
                                    colour,
                                    topLeft = Offset(left + r.left * image.width - pad, top + r.top * image.height - pad),
                                    size = Size(r.width() * image.width + 2 * pad, r.height() * image.height + 2 * pad),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
