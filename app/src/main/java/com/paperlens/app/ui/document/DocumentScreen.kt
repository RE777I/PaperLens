package com.paperlens.app.ui.document

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.AppSettings
import com.paperlens.app.data.moveToFolder
import com.paperlens.app.export.Exporter
import com.paperlens.app.export.GallerySaver
import com.paperlens.app.export.Sharing
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.security.AppLock
import com.paperlens.app.ui.components.ConfirmDialog
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.findActivity
import com.paperlens.app.ui.components.toast
import com.paperlens.app.ui.files.MoveToFolderDialog
import com.paperlens.app.ui.tools.PagesMode
import com.paperlens.app.ui.tools.Tool
import com.paperlens.app.ui.tools.ToolNavigator
import com.paperlens.app.ui.tools.ToolRunnerHost
import com.paperlens.app.ui.tools.rememberToolRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class DocDialog { RENAME, MOVE, DELETE, PDF_PASSWORD, WATERMARK, FILTER_ALL, DELETE_PAGES, MOVE_PAGES, DISCARD_EDITS, LEAVE }
private enum class DocSheet { ADD, SHARE, MORE }

/**
 * An open document: pages full-width in a vertical scroll (or a grid), with Add / Edit / Share /
 * To Word / Sign at the bottom, a Share sheet and a More sheet of everything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    documentId: Long,
    nav: ToolNavigator,
    onBack: () -> Unit,
    openPage: (Int) -> Unit,
    openEditText: (Int) -> Unit,
    openEditPages: (index: Int, selected: List<Long>) -> Unit,
    openSign: (pageId: Long) -> Unit,
    openCrop: (pageId: Long) -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.repository
    val runner = rememberToolRunner(nav)
    val busy = runner.busy

    val doc by remember(documentId) { repo.observeDocument(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    val pages by remember(documentId) { repo.observePages(documentId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    var grid by rememberSaveable { mutableStateOf(false) }
    // Long-press selection of pages (ids, in the order they were picked).
    var selected by remember { mutableStateOf(listOf<Long>()) }
    val selecting = selected.isNotEmpty()
    val selectedPages = pages.filter { it.id in selected }
    var sheet by remember { mutableStateOf<DocSheet?>(null) }
    var dialog by remember { mutableStateOf<DocDialog?>(null) }
    val listState = rememberLazyListState()
    val title = doc?.title ?: ""
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val current = if (grid) 0 else firstVisible.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    var finding by remember { mutableStateOf(false) }
    // Find Text closes on the page it was showing; the list scrolls there once it is on screen.
    var scrollTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(scrollTo, grid, pages.size) {
        val target = scrollTo ?: return@LaunchedEffect
        if (!grid && pages.isNotEmpty()) {
            listState.scrollToItem(target.coerceIn(0, pages.size - 1))
            scrollTo = null
        }
    }

    suspend fun exportPdf(password: String? = null): File =
        app.exporter.pdf(title, repo.getPages(documentId), settings.pdfPageSize, settings.exportQuality, password)

    // The in-app camera keeps every page's original photo, so its crop can be adjusted later.
    val addScan = { runner.scanInto(documentId) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) busy.run(S.str(R.string.adding_pages)) { repo.addUris(documentId, uris, rawPhotos = true) }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            busy.run(S.str(R.string.saving_pdf)) {
                val pdf = exportPdf()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> pdf.inputStream().use { it.copyTo(out) } }
                }
                context.toast(S.str(R.string.pdf_saved))
            }
        }
    }

    /** Closes any sheet, then runs [block] behind the progress overlay (unless the document is empty). */
    fun act(label: String, block: suspend () -> Unit) {
        sheet = null
        if (pages.isEmpty()) {
            context.toast(S.str(R.string.this_document_has_no_pages))
            return
        }
        busy.run(label, block)
    }

    fun runTool(tool: Tool) {
        sheet = null
        doc?.let { runner.runOn(tool, listOf(it)) }
    }

    // Edits made to this document are a preview until Save; leaving asks what to do with them.
    LaunchedEffect(documentId) { repo.openDocument(documentId) }
    val editing by repo.editingDocument.collectAsStateWithLifecycle()
    val unsaved = editing == documentId

    fun leave() {
        repo.closeDocument(documentId)
        onBack()
    }

    fun requestLeave() {
        if (unsaved) dialog = DocDialog.LEAVE else leave()
    }
    BackHandler { requestLeave() }

    // Forget pages that were deleted or moved elsewhere.
    LaunchedEffect(pages) {
        val ids = pages.map { it.id }.toSet()
        if (selected.any { it !in ids }) selected = selected.filter { it in ids }
    }
    BackHandler(enabled = selecting) { selected = emptyList() }

    fun clickPage(index: Int, id: Long) {
        if (selecting) selected = if (id in selected) selected - id else selected + id else openPage(index)
    }

    fun longPressPage(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            topBar = {
                Column {
                if (selecting) {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = { selected = emptyList() }) { Icon(Icons.Filled.Close, S.str(R.string.cancel_selection)) } },
                        title = { Text("${selected.size} selected") },
                        actions = {
                            TextButton(onClick = {
                                selected = if (selected.size == pages.size) emptyList() else pages.map { it.id }
                            }) { Text(if (selected.size == pages.size) S.str(R.string.select_none) else S.str(R.string.select_all)) }
                            IconButton(onClick = {
                                val ids = selected.toSet()
                                selected = emptyList()
                                busy.run(S.str(R.string.copying_pages)) {
                                    repo.extractPages(documentId, ids, S.str(R.string.x_x_pages, title, ids.size))
                                    context.toast(S.str(R.string.copied_x_page_s_to_a_new_document, ids.size))
                                }
                            }) { Icon(Icons.Filled.ContentCopy, S.str(R.string.copy_to_a_new_document)) }
                        },
                    )
                } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = ::requestLeave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                    title = {
                        Row(
                            Modifier.clickable { dialog = DocDialog.RENAME },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Filled.Edit, S.str(R.string.rename), Modifier.padding(start = 6.dp).size(20.dp))
                        }
                    },
                    actions = {
                        IconButton(onClick = { grid = !grid }) {
                            Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, if (grid) S.str(R.string.list_view) else S.str(R.string.grid_view))
                        }
                        IconButton(onClick = { sheet = DocSheet.MORE }) { Icon(Icons.Filled.MoreVert, S.str(R.string.more)) }
                    },
                )
                }
                if (unsaved) {
                    UnsavedBar(
                        onDiscard = { dialog = DocDialog.DISCARD_EDITS },
                        onSave = { busy.run(S.str(R.string.saving)) { repo.commitEdit(documentId) } },
                    )
                }
                }
            },
            bottomBar = {
                if (selecting) {
                    BottomAppBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BottomAction(S.str(R.string.edit), Icons.Filled.Edit) {
                                val first = pages.indexOfFirst { it.id in selected }
                                // Document order, so the editor's "Selected pages" filter covers exactly these.
                                val chosen = pages.filter { it.id in selected }.map { it.id }
                                selected = emptyList()
                                if (first >= 0) openEditPages(first, chosen)
                            }
                            if (selected.size == 1) {
                                BottomAction(S.str(R.string.crop), Icons.Filled.Crop) {
                                    val id = selected.first()
                                    selected = emptyList()
                                    openCrop(id)
                                }
                            }
                            BottomAction(S.str(R.string.rotate), Icons.AutoMirrored.Filled.RotateRight) {
                                val ids = selected.toSet()
                                busy.run(S.str(R.string.rotating)) { repo.rotatePages(documentId, ids, 90) }
                            }
                            BottomAction(S.str(R.string.move), Icons.Filled.SwapVert) { dialog = DocDialog.MOVE_PAGES }
                            BottomAction(S.str(R.string.share_2), Icons.Filled.Share) {
                                val chosen = selectedPages
                                busy.run(S.str(R.string.creating_pdf)) {
                                    val pdf = app.exporter.pdf(title, chosen, settings.pdfPageSize, settings.exportQuality)
                                    Sharing.share(context, listOf(pdf), "application/pdf", title)
                                }
                            }
                            BottomAction(S.str(R.string.delete), Icons.Filled.Delete, tint = Color(0xFFE5533D)) { dialog = DocDialog.DELETE_PAGES }
                        }
                    }
                    return@Scaffold
                }
                BottomAppBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BottomAction(S.str(R.string.add), Icons.Filled.AddAPhoto) { sheet = DocSheet.ADD }
                        BottomAction(S.str(R.string.edit), Icons.Filled.Edit) { if (pages.isEmpty()) context.toast(S.str(R.string.no_pages_to_edit)) else openEditPages(current, emptyList()) }
                        BottomAction(S.str(R.string.share_2), Icons.Filled.Share) { sheet = DocSheet.SHARE }
                        BottomAction(S.str(R.string.to_word), Icons.Filled.Description) { runTool(Tool.TO_WORD) }
                        BottomAction(S.str(R.string.sign), Icons.Filled.Draw) { pages.getOrNull(current)?.let { openSign(it.id) } }
                    }
                }
            },
        ) { padding ->
            when {
                pages.isEmpty() -> Box(
                    Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(S.str(R.string.no_pages_tap_add_to_scan_some)) }
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                        PageCard(
                            File(page.imagePath), index, 0.72f,
                            selected = if (selecting) page.id in selected else null,
                            onLongClick = { longPressPage(page.id) },
                        ) { clickPage(index, page.id) }
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                        PageCard(
                            File(page.imagePath), index, null,
                            selected = if (selecting) page.id in selected else null,
                            onLongClick = { longPressPage(page.id) },
                        ) { clickPage(index, page.id) }
                    }
                }
            }
        }
        if (finding) {
            FindTextScreen(pages, current) { page ->
                finding = false
                grid = false
                scrollTo = page
            }
        }
        ToolRunnerHost(runner)
    }

    when (sheet) {
        DocSheet.ADD -> Sheet(S.str(R.string.add_pages), { sheet = null }) {
            SheetRow(S.str(R.string.scan_more_pages), Icons.Filled.AddAPhoto) { sheet = null; addScan() }
            SheetRow(S.str(R.string.import_from_gallery), Icons.Filled.AddPhotoAlternate) {
                sheet = null
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        DocSheet.SHARE -> Sheet(S.str(R.string.x_page_s_selected, pages.size), { sheet = null }) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { _, p ->
                    AsyncImage(
                        File(p.imagePath),
                        null,
                        Modifier
                            .height(150.dp)
                            .aspectRatio(0.72f)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            HorizontalDivider()
            SheetRow(S.str(R.string.share_as_pdf), Icons.Filled.PictureAsPdf) {
                act(S.str(R.string.creating_pdf)) { Sharing.share(context, listOf(exportPdf()), "application/pdf", title) }
            }
            SheetRow(S.str(R.string.share_as_word), Icons.Filled.Description) { sheet = null; doc?.let { runner.shareAsWord(it) } }
            SheetRow(S.str(R.string.share_as_long_image), Icons.Filled.ViewDay) {
                act(S.str(R.string.stitching_pages)) { Sharing.share(context, listOf(app.exporter.longImage(title, repo.getPages(documentId))), "image/jpeg", title) }
            }
            SheetRow(S.str(R.string.share_as_images), Icons.Filled.Image) {
                act(S.str(R.string.preparing_images)) {
                    Sharing.share(context, app.exporter.jpegs(title, repo.getPages(documentId), settings.exportQuality), "image/jpeg", title)
                }
            }
            SheetRow(S.str(R.string.export_pages_as_separate_pdfs), Icons.Filled.PictureAsPdf) {
                act(S.str(R.string.creating_pdfs)) {
                    val files = app.exporter.separatePdfs(title, repo.getPages(documentId), settings.pdfPageSize, settings.exportQuality)
                    Sharing.share(context, files, "application/pdf", title)
                }
            }
            SheetRow(S.str(R.string.share_pdf_with_password), Icons.Filled.Password) { sheet = null; dialog = DocDialog.PDF_PASSWORD }
            SheetRow(S.str(R.string.share_as_text_txt), Icons.AutoMirrored.Filled.Article) {
                act(S.str(R.string.recognising_text)) { Sharing.share(context, listOf(app.exporter.text(title, repo.ocrDocument(documentId))), "text/plain", title) }
            }
            SheetRow(S.str(R.string.save_pdf_to_device), Icons.Filled.SaveAlt) { sheet = null; savePdf.launch(Exporter.safeName(title) + ".pdf") }
        }
        DocSheet.MORE -> Sheet(S.str(R.string.more), { sheet = null }) {
            doc?.let { d -> MoreHeader(d, pages.firstOrNull()?.imagePath, pages.size) { sheet = null; dialog = DocDialog.RENAME } }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BigAction(S.str(R.string.edit_text), Icons.Filled.EditNote, Color(0xFFE5533D)) { sheet = null; if (pages.isNotEmpty()) openEditText(current) }
                BigAction(S.str(R.string.save_to_gallery), Icons.Filled.Download, Color(0xFF14A38B)) {
                    act(S.str(R.string.saving_to_gallery)) {
                        if (!GallerySaver.supported) {
                            context.toast(S.str(R.string.needs_android_10_or_newer))
                        } else {
                            val n = GallerySaver.save(context, repo.getPages(documentId).map { Uri.fromFile(File(it.imagePath)) }, title)
                            context.toast(S.str(R.string.x_page_s_saved_to_pictures_paperlens, n))
                        }
                    }
                }
                BigAction(S.str(R.string.print), Icons.Filled.Print, Color(0xFF14A38B)) { runTool(Tool.PRINT) }
                BigAction(if (doc?.locked == true) S.str(R.string.unlock) else S.str(R.string.lock), if (doc?.locked == true) Icons.Filled.LockOpen else Icons.Filled.Lock, Color(0xFF2F6FED)) {
                    runTool(Tool.LOCK)
                }
            }
            HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceContainer)
            SheetSection(S.str(R.string.convert))
            ToolGrid(
                listOf(
                    Triple(S.str(R.string.to_word), Icons.Filled.Description) { runTool(Tool.TO_WORD) },
                    Triple(S.str(R.string.to_excel), Icons.Filled.TableChart) { runTool(Tool.TO_EXCEL) },
                    Triple(S.str(R.string.long_image), Icons.Filled.ViewDay) {
                        act(S.str(R.string.stitching_pages)) { Sharing.share(context, listOf(app.exporter.longImage(title, repo.getPages(documentId))), "image/jpeg", title) }
                    },
                ),
            )
            SheetSection(S.str(R.string.smart_tools))
            ToolGrid(
                listOf(
                    Triple(S.str(R.string.find_text), Icons.Filled.Search) {
                        sheet = null
                        if (pages.isEmpty()) context.toast(S.str(R.string.this_document_has_no_pages)) else finding = true
                    },
                    Triple(S.str(R.string.translate), Icons.Filled.Translate) {
                        sheet = null
                        if (pages.isEmpty()) context.toast(S.str(R.string.this_document_has_no_pages)) else nav.openTranslate(documentId, current)
                    },
                    Triple(S.str(R.string.compress), Icons.Filled.Compress) { runTool(Tool.COMPRESS) },
                    Triple(S.str(R.string.timestamp), Icons.Filled.Schedule) { runTool(Tool.TIMESTAMP) },
                    Triple(S.str(R.string.watermark), Icons.AutoMirrored.Filled.BrandingWatermark) { sheet = null; dialog = DocDialog.WATERMARK },
                    Triple(S.str(R.string.filter_all), Icons.Filled.Filter) { sheet = null; dialog = DocDialog.FILTER_ALL },
                ),
            )
            SheetSection(S.str(R.string.manage))
            ToolGrid(
                listOf(
                    Triple(S.str(R.string.manage_pages), Icons.Filled.SwapVert) { sheet = null; nav.openPagesTool(documentId, PagesMode.REORDER) },
                    Triple(S.str(R.string.extract_pages), Icons.Filled.ContentCut) { sheet = null; nav.openPagesTool(documentId, PagesMode.EXTRACT) },
                    Triple(S.str(R.string.merge_files), Icons.AutoMirrored.Filled.MergeType) { sheet = null; runner.run(Tool.MERGE_FILES) },
                    Triple(S.str(R.string.move), Icons.AutoMirrored.Filled.DriveFileMove) { sheet = null; dialog = DocDialog.MOVE },
                    Triple(S.str(R.string.copy), Icons.Filled.ContentCopy) {
                        act(S.str(R.string.copying)) { nav.openDocument(repo.duplicateDocument(documentId)) }
                    },
                ),
            )
            TextButton(
                onClick = { sheet = null; dialog = DocDialog.DELETE },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) { Text(S.str(R.string.delete), color = Color(0xFFE5533D), style = MaterialTheme.typography.titleMedium) }
        }
        null -> Unit
    }

    when (dialog) {
        DocDialog.DISCARD_EDITS -> ConfirmDialog(
            title = S.str(R.string.discard_changes),
            text = S.str(R.string.the_pages_go_back_to_how_they_were_before_yo),
            confirmLabel = S.str(R.string.discard),
            onDismiss = { dialog = null },
        ) {
            dialog = null
            busy.run(S.str(R.string.discarding)) { repo.discardEdit(documentId) }
        }
        DocDialog.LEAVE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(S.str(R.string.save_changes)) },
            text = { Text(S.str(R.string.you_edited_this_document_save_the_changes_or)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    busy.run(S.str(R.string.saving)) {
                        repo.commitEdit(documentId)
                        leave()
                    }
                }) { Text(S.str(R.string.save_2)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { dialog = null }) { Text(S.str(R.string.keep_editing)) }
                    TextButton(onClick = {
                        dialog = null
                        busy.run(S.str(R.string.discarding)) {
                            repo.discardEdit(documentId)
                            leave()
                        }
                    }) { Text(S.str(R.string.discard), color = Color(0xFFE5533D)) }
                }
            },
        )
        DocDialog.DELETE_PAGES -> ConfirmDialog(
            title = S.str(R.string.delete_x_page_s, selected.size),
            text = S.str(R.string.they_are_removed_from_this_document),
            confirmLabel = S.str(R.string.delete),
            onDismiss = { dialog = null },
        ) {
            dialog = null
            val ids = selected.toSet()
            selected = emptyList()
            busy.run(S.str(R.string.deleting)) { repo.deletePages(documentId, ids) }
        }
        DocDialog.MOVE_PAGES -> MovePagesDialog(pages.size, selected.size, onDismiss = { dialog = null }) { afterPage ->
            dialog = null
            val ids = selected.toSet()
            busy.run(S.str(R.string.moving)) { repo.movePages(documentId, ids, afterPage) }
        }
        DocDialog.RENAME -> TextInputDialog(S.str(R.string.rename), title, S.str(R.string.name), onDismiss = { dialog = null }) { name ->
            dialog = null
            busy.run(S.str(R.string.renaming)) { repo.rename(documentId, name) }
        }
        DocDialog.MOVE -> MoveToFolderDialog(onDismiss = { dialog = null }) { folderId ->
            dialog = null
            busy.run(S.str(R.string.moving)) { repo.moveToFolder(listOf(documentId), folderId) }
            context.toast(S.str(R.string.moved))
        }
        DocDialog.WATERMARK -> TextInputDialog(S.str(R.string.watermark_all_pages), "", S.str(R.string.watermark_text), S.str(R.string.apply), onDismiss = { dialog = null }) { text ->
            dialog = null
            if (text.isNotBlank()) {
                busy.run(S.str(R.string.adding_watermark)) {
                    repo.getPages(documentId).forEach { page -> repo.bakeEdit(page) { PageComposer.watermark(it, text) } }
                }
            }
        }
        DocDialog.PDF_PASSWORD -> TextInputDialog(S.str(R.string.pdf_password), "", S.str(R.string.password), S.str(R.string.share_2), isPassword = true, onDismiss = { dialog = null }) { password ->
            dialog = null
            if (password.isBlank()) {
                context.toast(S.str(R.string.password_cannot_be_empty))
            } else {
                act(S.str(R.string.encrypting_pdf)) { Sharing.share(context, listOf(exportPdf(password)), "application/pdf", title) }
            }
        }
        DocDialog.FILTER_ALL -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(S.str(R.string.filter_all_pages)) },
            text = {
                Column {
                    ImageFilter.entries.forEach { filter ->
                        Text(
                            filter.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dialog = null
                                    busy.run(S.str(R.string.applying_x, filter.label)) { repo.applyFilterToAll(documentId, filter) }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(S.str(R.string.cancel)) } },
        )
        DocDialog.DELETE -> ConfirmDialog(
            title = S.str(R.string.delete_x, title),
            text = S.str(R.string.all_x_pages_are_removed_from_this_device, pages.size),
            confirmLabel = S.str(R.string.delete),
            onDismiss = { dialog = null },
        ) {
            dialog = null
            val locked = doc?.locked == true
            val delete = {
                busy.run(S.str(R.string.deleting)) {
                    repo.deleteDocument(documentId)
                    onBack()
                }
            }
            if (locked) {
                AppLock.authenticate(context.findActivity(), S.str(R.string.delete_x_2, title)) { ok -> if (ok) delete() }
            } else {
                delete()
            }
        }
        null -> Unit
    }
}
