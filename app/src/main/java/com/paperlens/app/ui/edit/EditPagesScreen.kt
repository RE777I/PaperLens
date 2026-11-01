package com.paperlens.app.ui.edit

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.paperlens.app.app
import com.paperlens.app.ui.tools.rememberToolRunner
import com.paperlens.app.ui.tools.ToolRunnerHost
import com.paperlens.app.ui.tools.ToolNavigator
import com.paperlens.app.data.AppSettings
import com.paperlens.app.data.imageFilter
import com.paperlens.app.export.Sharing
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.ConfirmDialog
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.rememberDocumentScanner
import com.paperlens.app.ui.tools.PagesMode
import kotlinx.coroutines.launch
import java.io.File

private enum class EditTab(private val labelRes: Int) { IMAGES(R.string.images), MARKUP(R.string.markup), PAGE(R.string.page) ;
    val label: String get() = S.str(labelRes)
}

private val Accent = Color(0xFF14A38B)

/** Which pages a filter change applies to. */
private enum class FilterScope { PAGE, SELECTED, ALL }

/**
 * Edit: page pager with Images / Markup / Page tool tabs, Share and Done. [selectedPages] are the pages
 * long-pressed in the document; the filter applies to all of them (or to this page, or every page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPagesScreen(
    documentId: Long,
    startIndex: Int,
    onClose: () -> Unit,
    selectedPages: List<Long> = emptyList(),
    openEditText: (index: Int) -> Unit,
    openRegion: (pageId: Long, tool: RegionTool) -> Unit,
    openSign: (pageId: Long) -> Unit,
    openAnnotate: (pageId: Long) -> Unit,
    openPagesTool: (PagesMode) -> Unit,
    nav: ToolNavigator,
) {
    val runner = rememberToolRunner(nav)
    val context = LocalContext.current
    val app = context.app
    val repo = app.repository
    val busy = rememberBusy()
    val scope = rememberCoroutineScope()
    val doc by remember(documentId) { repo.observeDocument(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    val pages by remember(documentId) { repo.observePages(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var tab by rememberSaveable { mutableStateOf(EditTab.IMAGES) }
    var showFilters by remember { mutableStateOf(false) }
    var filterScope by rememberSaveable { mutableStateOf(if (selectedPages.size > 1) FilterScope.SELECTED else FilterScope.PAGE) }
    var watermark by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var retakeIndex by remember { mutableIntStateOf(-1) }
    // Changes here are a preview: the document asks to Save or Discard when you leave it.
    LaunchedEffect(documentId) { repo.openDocument(documentId) }
    BackHandler(onBack = onClose)

    val list = pages ?: return
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, list.lastIndex)) { list.size }
    val index = pager.currentPage.coerceIn(0, list.lastIndex)
    val page = list[index]


    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, S.str(R.string.close)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    busy.run(S.str(R.string.creating_pdf)) {
                        val title = doc?.title ?: S.str(R.string.document)
                        Sharing.share(
                            context,
                            listOf(app.exporter.pdf(title, repo.getPages(documentId), settings.pdfPageSize, settings.exportQuality)),
                            "application/pdf",
                            title,
                        )
                    }
                }, shape = RoundedCornerShape(10.dp)) { Text(S.str(R.string.share_2)) }
                Spacer(Modifier.width(10.dp))
                Button(onClick = onClose, shape = RoundedCornerShape(10.dp)) { Text(S.str(R.string.done)) }
            }

            HorizontalPager(
                state = pager,
                key = { list.getOrNull(it)?.id ?: it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { i ->
                val p = list.getOrNull(i) ?: return@HorizontalPager
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(File(p.imagePath), S.str(R.string.page_x, i + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Surface(
                        onClick = { deleting = true },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(44.dp),
                    ) { Icon(Icons.Filled.Delete, S.str(R.string.delete_page), Modifier.padding(10.dp), tint = Color.White) }
                }
            }

            // ‹ 1/2 ›
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = index > 0, onClick = { scope.launch { pager.animateScrollToPage(index - 1) } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowLeft, S.str(R.string.previous_page))
                    }
                    Text("${index + 1}/${list.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp))
                    IconButton(enabled = index < list.lastIndex, onClick = { scope.launch { pager.animateScrollToPage(index + 1) } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowRight, S.str(R.string.next_page))
                    }
                }
            }

            // Tabs + tools
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Column(Modifier.navigationBarsPadding()) {
                    Row(Modifier.padding(start = 12.dp, top = 8.dp)) {
                        EditTab.entries.forEach { t ->
                            TextButton(onClick = { tab = t }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        t.label,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = if (t == tab) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Box(
                                        Modifier
                                            .padding(top = 4.dp)
                                            .size(width = 28.dp, height = 3.dp)
                                            .background(if (t == tab) Accent else Color.Transparent, RoundedCornerShape(2.dp)),
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        when (tab) {
                            EditTab.IMAGES -> {
                                EditTool(S.str(R.string.crop), Icons.Filled.Crop) { openRegion(page.id, RegionTool.CROP) }
                                EditTool(S.str(R.string.filter), Icons.Filled.Filter) { showFilters = true }
                                EditTool(S.str(R.string.edit_text), Icons.Filled.EditNote) { openEditText(index) }
                                EditTool(S.str(R.string.smart_erase), Icons.Filled.AutoFixHigh) { openRegion(page.id, RegionTool.ERASE) }
                                EditTool(S.str(R.string.rotate_left), Icons.AutoMirrored.Filled.RotateLeft) {
                                    busy.run(S.str(R.string.rotating)) { repo.editPage(page, page.imageFilter, page.rotation - 90) }
                                }
                                EditTool(S.str(R.string.rotate_right), Icons.AutoMirrored.Filled.RotateRight) {
                                    busy.run(S.str(R.string.rotating)) { repo.editPage(page, page.imageFilter, page.rotation + 90) }
                                }
                                EditTool(S.str(R.string.retake), Icons.Filled.CameraAlt) { runner.retake(page) }
                            }
                            EditTab.MARKUP -> {
                                EditTool(S.str(R.string.sign), Icons.Filled.Draw) { openSign(page.id) }
                                EditTool(S.str(R.string.add_text), Icons.Filled.TextFields) { openRegion(page.id, RegionTool.TEXT) }
                                EditTool(S.str(R.string.brush), Icons.Filled.Gesture) { openAnnotate(page.id) }
                                EditTool(S.str(R.string.mosaic), Icons.Filled.GridOn) { openRegion(page.id, RegionTool.MOSAIC) }
                                EditTool(S.str(R.string.add_watermark), Icons.AutoMirrored.Filled.BrandingWatermark) { watermark = true }
                            }
                            EditTab.PAGE -> {
                                EditTool(S.str(R.string.reorder_pages), Icons.Filled.SwapVert) { openPagesTool(PagesMode.REORDER) }
                                EditTool(S.str(R.string.extract_pages), Icons.Filled.ContentCut) { openPagesTool(PagesMode.EXTRACT) }
                                EditTool(S.str(R.string.delete_page_2), Icons.Filled.Delete) { deleting = true }
                            }
                        }
                    }
                }
            }
        }
        BusyOverlay(busy)
        ToolRunnerHost(runner)
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            Text(S.str(R.string.filter), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            // Selected pages that still exist, in document order.
            val selected = list.filter { it.id in selectedPages }
            val scopes = buildList {
                add(FilterScope.PAGE to S.str(R.string.this_page))
                if (selected.size > 1) add(FilterScope.SELECTED to S.str(R.string.selected_pages_x, selected.size))
                add(FilterScope.ALL to S.str(R.string.all_pages_x, list.size))
            }
            val scope = filterScope.takeIf { s -> scopes.any { it.first == s } } ?: FilterScope.PAGE
            val targets = when (scope) {
                FilterScope.PAGE -> listOf(page)
                FilterScope.SELECTED -> selected
                FilterScope.ALL -> list
            }
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                scopes.forEach { (value, label) ->
                    FilterChip(
                        selected = scope == value,
                        onClick = { filterScope = value },
                        label = { Text(label) },
                        leadingIcon = if (scope == value) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                items(ImageFilter.entries) { f ->
                    FilterChip(
                        selected = targets.isNotEmpty() && targets.all { it.imageFilter == f },
                        onClick = {
                            val chosen = targets
                            val label = if (chosen.size > 1) S.str(R.string.applying_x_to_x_pages, f.label, chosen.size) else S.str(R.string.applying_x, f.label)
                            busy.run(label) { chosen.forEach { repo.editPage(it, f, it.rotation) } }
                        },
                        label = { Text(f.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (watermark) {
        TextInputDialog(S.str(R.string.add_watermark_2), "", S.str(R.string.watermark_text), S.str(R.string.apply), onDismiss = { watermark = false }) { text ->
            watermark = false
            if (text.isNotBlank()) busy.run(S.str(R.string.adding_watermark)) { repo.bakeEdit(page) { PageComposer.watermark(it, text) } }
        }
    }
    if (deleting) {
        ConfirmDialog(S.str(R.string.delete_page_x, index + 1), S.str(R.string.the_page_is_removed_from_this_document), S.str(R.string.delete), onDismiss = { deleting = false }) {
            deleting = false
            busy.run(S.str(R.string.deleting)) { repo.deletePage(page) }
        }
    }
}

@Composable
private fun EditTool(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.width(92.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
