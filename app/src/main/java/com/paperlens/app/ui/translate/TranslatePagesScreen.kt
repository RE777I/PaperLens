package com.paperlens.app.ui.translate

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.paperlens.app.app
import com.paperlens.app.data.PageEntity
import com.paperlens.app.export.Sharing
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.TranslationOverlay
import com.paperlens.app.ml.DocTranslator
import com.paperlens.app.ml.LanguageDownloads
import com.paperlens.app.ml.OcrScript
import com.paperlens.app.ml.TextLayout
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.LanguageButton
import com.paperlens.app.ui.components.LanguageDownloadBars
import com.paperlens.app.ui.components.LanguagePicker
import com.paperlens.app.ui.components.copyToClipboard
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.rememberNotificationAsk
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One translated page: the page image with the translation drawn on it, and the translated paragraphs. */
private class PageResult(val image: File?, val texts: List<String>, val note: String? = null)

/**
 * Translate on the page, like a camera translator: every page of the document is shown with each
 * paragraph covered and its translation written in place, in the original's colours and size. Press
 * and hold a page to see the original. From / To at the top; save as a new document, share or copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatePagesScreen(documentId: Long, startIndex: Int, onClose: () -> Unit, openDocument: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val busy = rememberBusy()
    val doc by remember(documentId) { repo.observeDocument(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    val pages by remember(documentId) { repo.observePages(documentId) }.collectAsStateWithLifecycle(initialValue = null)

    var source by rememberSaveable { mutableStateOf<String?>(null) } // null = auto-detect
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var detected by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<String?>(null) }
    var holding by remember { mutableStateOf<Long?>(null) }
    val results = remember { mutableStateMapOf<Long, PageResult>() }
    val askNotifications = rememberNotificationAsk()
    val downloads by LanguageDownloads.active.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex.coerceAtLeast(0))

    LaunchedEffect(Unit) { if (target == null) target = context.app.settings.current().translateTarget }

    // (Re)translate every page whenever the languages change; the page you opened goes first.
    val pageIds = pages?.map { it.id }
    LaunchedEffect(pageIds, source, target, attempt) {
        val list = pages ?: return@LaunchedEffect
        val to = target ?: return@LaunchedEffect
        results.clear()
        error = null
        val script = source?.let(OcrScript::forLanguage) ?: context.app.settings.current().ocrScript
        val order = list.indices.sortedBy { if (it == startIndex) -1 else it }
        try {
            for (i in order) {
                val page = list[i]
                status = S.str(R.string.reading_page_x, i + 1)
                val blocks = withContext(Dispatchers.Default) { TextLayout.blocks(context, File(page.imagePath), script) }
                if (blocks.isEmpty()) {
                    results[page.id] = PageResult(null, emptyList(), S.str(R.string.no_text_found_on_this_page))
                    continue
                }
                val from = source ?: DocTranslator.detect(blocks.joinToString("\n") { it.text })?.also { detected = it }
                if (from == null) {
                    // One unreadable page (handwriting, a picture) should not stop the others.
                    results[page.id] = PageResult(null, emptyList(), S.str(R.string.language_not_recognised_choose_it_under_from))
                    continue
                }
                if (from == to) {
                    results[page.id] = PageResult(null, emptyList(), S.str(R.string.already_in_x, DocTranslator.displayName(to)))
                    continue
                }
                val texts = DocTranslator.translateAll(blocks.map { it.text }, from, to) { status = S.str(R.string.page_x_x_2, i + 1, it) }
                val image = withContext(Dispatchers.Default) {
                    val bitmap = BitmapUtils.decode(File(page.imagePath))
                    val out = File(context.cacheDir, "translate/${page.id}_${to}_${System.nanoTime()}.jpg").apply { parentFile?.mkdirs() }
                    BitmapUtils.saveJpeg(TranslationOverlay.render(bitmap, blocks, texts), out, 92)
                }
                results[page.id] = PageResult(image, texts)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocTranslator.Failure) {
            error = e.message
        } catch (e: Exception) {
            error = S.str(R.string.translation_failed_x, e.message)
        } finally {
            status = null
        }
    }

    fun translatedFiles(list: List<PageEntity>) = list.map { results[it.id]?.image ?: File(it.imagePath) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, S.str(R.string.close)) } },
                    title = { Text(S.str(R.string.translate)) },
                    actions = {
                        val ready = results.values.any { it.image != null } && status == null
                        IconButton(enabled = ready, onClick = {
                            val all = pages.orEmpty().mapNotNull { results[it.id]?.texts?.joinToString("\n\n") }.filter { it.isNotBlank() }
                            context.copyToClipboard(all.joinToString("\n\n"))
                        }) { Icon(Icons.Filled.ContentCopy, S.str(R.string.copy_translated_text)) }
                        IconButton(enabled = ready, onClick = {
                            val list = pages.orEmpty()
                            Sharing.share(context, translatedFiles(list), "image/jpeg", doc?.title ?: S.str(R.string.translation))
                        }) { Icon(Icons.Filled.Share, S.str(R.string.share_translated_pages)) }
                        IconButton(enabled = ready, onClick = {
                            val list = pages.orEmpty()
                            val to = target ?: return@IconButton
                            busy.run(S.str(R.string.saving_translation)) {
                                val bitmaps = withContext(Dispatchers.Default) { translatedFiles(list).map { BitmapUtils.decode(it) } }
                                val id = repo.createFromBitmaps(bitmaps, "${doc?.title ?: S.str(R.string.document)} (${DocTranslator.displayName(to)})", ImageFilter.ORIGINAL)
                                context.toast(S.str(R.string.saved_as_a_new_document))
                                openDocument(id)
                            }
                        }) { Icon(Icons.Filled.Save, S.str(R.string.save_as_a_new_document)) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LanguageButton(
                        S.str(R.string.from),
                        source?.let(DocTranslator::displayName) ?: (S.str(R.string.auto_detect) + (detected?.let { " (${DocTranslator.displayName(it)})" } ?: "")),
                        { picking = "from" },
                        Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = (source ?: detected) != null && status == null,
                        onClick = {
                            val from = source ?: detected
                            if (from != null) {
                                source = target
                                target = from
                            }
                        },
                    ) { Icon(Icons.Filled.SwapHoriz, S.str(R.string.swap_languages)) }
                    LanguageButton(S.str(R.string.to), target?.let(DocTranslator::displayName) ?: "", { picking = "to" }, Modifier.weight(1f))
                }
                if (status != null) {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(status ?: "", style = MaterialTheme.typography.bodyMedium)
                        if (downloads.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) else LanguageDownloadBars(Modifier.fillMaxWidth())
                    }
                }
                error?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                            TextButton(onClick = { attempt++ }) { Text(S.str(R.string.retry)) }
                        }
                    }
                }
                Text(
                    S.str(R.string.press_and_hold_a_page_to_see_the_original),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    itemsIndexed(pages.orEmpty(), key = { _, p -> p.id }) { index, page ->
                        val original = File(page.imagePath)
                        val ratio = remember(page.imagePath) {
                            val (w, h) = BitmapUtils.imageSize(original)
                            if (w > 0 && h > 0) w.toFloat() / h else 0.72f
                        }
                        val result = results[page.id]
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                                .background(Color.White)
                                .pointerInput(page.id) {
                                    detectTapGestures(onPress = {
                                        holding = page.id
                                        tryAwaitRelease()
                                        holding = null
                                    })
                                },
                        ) {
                            val shown = if (holding == page.id) original else result?.image ?: original
                            AsyncImage(shown, S.str(R.string.page_x, index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            when {
                                holding == page.id -> Badge(S.str(R.string.original), Modifier.align(Alignment.TopStart))
                                result == null && status != null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
                                result?.note != null -> Badge(result.note, Modifier.align(Alignment.TopStart))
                            }
                            Badge("${index + 1}", Modifier.align(Alignment.BottomStart))
                        }
                    }
                }
            }
        }
        BusyOverlay(busy)
    }

    picking?.let { which ->
        LanguagePicker(
            title = if (which == "from") S.str(R.string.translate_from) else S.str(R.string.translate_into),
            offerAuto = which == "from",
            selected = if (which == "from") source else target,
            onDismiss = { picking = null },
        ) { tag ->
            picking = null
            // A new language may need downloading: allow its progress notification first.
            askNotifications { if (which == "from") source = tag else if (tag != null) target = tag }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(6.dp), modifier = modifier.padding(6.dp)) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
