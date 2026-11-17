package com.paperlens.app.ui.files

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.FolderSummary
import com.paperlens.app.data.createFolder
import com.paperlens.app.data.deleteFolder
import com.paperlens.app.data.moveToFolder
import com.paperlens.app.data.observeFolder
import com.paperlens.app.data.observeFolderDocuments
import com.paperlens.app.data.observeFolders
import com.paperlens.app.data.renameFolder
import com.paperlens.app.ui.components.ConfirmDialog
import com.paperlens.app.ui.components.DocumentRow
import com.paperlens.app.ui.components.DocumentThumbnail
import com.paperlens.app.ui.components.DocumentTile
import com.paperlens.app.ui.components.FolderRow
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.toast
import com.paperlens.app.ui.home.SearchField
import com.paperlens.app.ui.tools.Tool
import com.paperlens.app.ui.tools.ToolRunner

private enum class Show(private val labelRes: Int) { ALL(R.string.all), FOLDERS(R.string.folders), DOCUMENTS(R.string.documents) ;
    val label: String get() = S.str(labelRes)
}
private enum class SortBy(private val labelRes: Int) { MODIFIED(R.string.last_modified), NAME(R.string.name_a_z), PAGES(R.string.most_pages) ;
    val label: String get() = S.str(labelRes)
}

/**
 * Files tab. At the top level ([folderId] null) it shows the import / create-folder cards; inside a
 * folder it shows a back bar with the folder menu, and a "+" button for the Add sheet
 * (scan, create sub-folder, import, move documents in). Both list sub-folders, then documents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesTab(
    runner: ToolRunner,
    folderId: Long?,
    openFolder: (Long) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val repo = context.app.repository
    val documents by remember(folderId) { repo.observeFolderDocuments(folderId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val folders by remember(folderId) { repo.observeFolders(folderId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val folder by remember(folderId) { repo.observeFolder(folderId ?: -1) }.collectAsStateWithLifecycle(initialValue = null)

    var query by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(Show.ALL) }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.MODIFIED) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FolderSummary?>(null) }
    var deletingFolder by remember { mutableStateOf<FolderSummary?>(null) }
    var renamingThis by remember { mutableStateOf(false) }
    var deletingThis by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var movingIn by remember { mutableStateOf(false) }
    val selection = rememberSelectionState()

    val q = query.trim()
    val shownFolders = if (show == Show.DOCUMENTS) emptyList() else folders
        .filter { q.isEmpty() || it.folder.name.contains(q, ignoreCase = true) }
        .let { list -> if (sortBy == SortBy.MODIFIED) list.sortedByDescending { it.updatedAt } else list }
    val shownDocs = if (show == Show.FOLDERS) emptyList() else documents
        .filter { q.isEmpty() || it.document.title.contains(q, ignoreCase = true) }
        .let { list ->
            when (sortBy) {
                SortBy.MODIFIED -> list
                SortBy.NAME -> list.sortedBy { it.document.title.lowercase() }
                SortBy.PAGES -> list.sortedByDescending { it.pageCount }
            }
        }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(if (folderId == null) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface),
        ) {
            when {
                selection.active -> SelectionBar(selection, documents.map { it.document }, runner)
                onBack != null -> Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) }
                        Text(
                            folder?.name ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        Box {
                            IconButton(onClick = { folderMenu = true }) { Icon(Icons.Filled.MoreVert, S.str(R.string.folder_options)) }
                            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                                DropdownMenuItem(text = { Text(S.str(R.string.new_folder_here)) }, onClick = { folderMenu = false; creatingFolder = true })
                                DropdownMenuItem(text = { Text(S.str(R.string.rename_folder)) }, onClick = { folderMenu = false; renamingThis = true })
                                DropdownMenuItem(text = { Text(S.str(R.string.select)) }, onClick = { folderMenu = false; selection.selecting = true })
                                DropdownMenuItem(text = { Text(S.str(R.string.delete_folder)) }, onClick = { folderMenu = false; deletingThis = true })
                            }
                        }
                    }
                    SearchField(query, { query = it }, S.str(R.string.search), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
                else -> Row(
                    Modifier
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                ) { SearchField(query, { query = it }, S.str(R.string.search), Modifier.fillMaxWidth()) }
            }

            LazyVerticalGrid(
                columns = if (grid) GridCells.Adaptive(110.dp) else GridCells.Fixed(1),
                contentPadding = PaddingValues(bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 0.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (folderId == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ActionCard(S.str(R.string.import_files), Icons.Filled.DriveFolderUpload, Color(0xFF2F6FED), Modifier.weight(1f)) { runner.run(Tool.IMPORT_FILES) }
                            ActionCard(S.str(R.string.import_images), Icons.Filled.Image, Color(0xFF14A38B), Modifier.weight(1f)) { runner.run(Tool.IMPORT_IMAGES) }
                            ActionCard(S.str(R.string.create_folder), Icons.Filled.CreateNewFolder, Color(0xFF2F6FED), Modifier.weight(1f)) { creatingFolder = true }
                        }
                    }
                }
                // Toolbar: All (n) ▾   sort  grid/list  select
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = if (folderId == null) RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp) else RoundedCornerShape(0.dp),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { showMenu = true }) {
                                    Text(show.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        " (${shownFolders.size + shownDocs.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurface)
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    Show.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { show = s; showMenu = false }) }
                                }
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, S.str(R.string.sort)) }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    SortBy.entries.forEach { s ->
                                        DropdownMenuItem(
                                            text = { Text(s.label, fontWeight = if (s == sortBy) FontWeight.Bold else FontWeight.Normal) },
                                            onClick = { sortBy = s; sortMenu = false },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { grid = !grid }) {
                                Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, if (grid) S.str(R.string.list_view) else S.str(R.string.grid_view))
                            }
                            IconButton(onClick = { selection.selecting = !selection.selecting }) { Icon(Icons.Filled.CheckBox, S.str(R.string.select)) }
                        }
                    }
                }
                items(shownFolders, key = { "f${it.folder.id}" }, span = { GridItemSpan(maxLineSpan) }) { f ->
                    Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        FolderRow(f, onClick = { openFolder(f.folder.id) }, onRename = { renaming = f }, onDelete = { deletingFolder = f })
                    }
                }
                items(shownDocs, key = { "d${it.document.id}" }) { summary ->
                    val id = summary.document.id
                    val onClick = { if (selection.active) selection.toggle(id) else runner.openDocument(summary.document) }
                    Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        if (grid) {
                            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                                DocumentTile(summary, id in selection.ids, onClick, { selection.toggle(id) })
                            }
                        } else {
                            DocumentRow(summary, id in selection.ids, onClick, { selection.toggle(id) })
                        }
                    }
                }
                if (shownFolders.isEmpty() && shownDocs.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (folderId != null) S.str(R.string.this_folder_is_empty_ntap_to_scan_import_or) else S.str(R.string.nothing_here_yet),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (folderId != null && !selection.active) {
            FloatingActionButton(
                onClick = { showAdd = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .size(64.dp)
                    .semantics { contentDescription = S.str(R.string.add_to_this_folder) },
            ) { Icon(Icons.Filled.Add, null, Modifier.size(32.dp)) }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp)) {
                Text(S.str(R.string.add), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                SheetSection(S.str(R.string.create))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AddOption(S.str(R.string.scan), Icons.Filled.CameraAlt, Color(0xFF14A38B), Modifier.weight(1f)) { showAdd = false; runner.run(Tool.SMART_SCAN) }
                    AddOption(S.str(R.string.create_folder), Icons.Filled.CreateNewFolder, Color(0xFF14A38B), Modifier.weight(1f)) { showAdd = false; creatingFolder = true }
                }
                SheetSection(S.str(R.string.import_text))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AddOption(S.str(R.string.gallery), Icons.Filled.Image, Color(0xFF2F6FED), Modifier.weight(1f)) { showAdd = false; runner.run(Tool.IMPORT_IMAGES) }
                    AddOption(S.str(R.string.device), Icons.Filled.PhoneAndroid, Color(0xFF2F6FED), Modifier.weight(1f)) { showAdd = false; runner.run(Tool.IMPORT_FILES) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AddOption(S.str(R.string.from_this_app), Icons.AutoMirrored.Filled.DriveFileMove, Color(0xFF14A38B), Modifier.weight(1f)) { showAdd = false; movingIn = true }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (movingIn && folderId != null) {
        MoveInDialog(folderId, onDismiss = { movingIn = false }) { ids ->
            movingIn = false
            runner.busy.run(S.str(R.string.moving)) { repo.moveToFolder(ids, folderId) }
            context.toast(S.str(R.string.moved_x_document_s_here, ids.size))
        }
    }
    if (creatingFolder) {
        TextInputDialog(if (folderId == null) S.str(R.string.create_folder_2) else S.str(R.string.new_folder_in_x, folder?.name), "", S.str(R.string.folder_name), S.str(R.string.create), onDismiss = { creatingFolder = false }) { name ->
            creatingFolder = false
            runner.busy.run(S.str(R.string.creating_folder)) { repo.createFolder(name, folderId) }
        }
    }
    renaming?.let { f ->
        TextInputDialog(S.str(R.string.rename_folder), f.folder.name, S.str(R.string.folder_name), S.str(R.string.rename), onDismiss = { renaming = null }) { name ->
            renaming = null
            runner.busy.run(S.str(R.string.renaming)) { repo.renameFolder(f.folder.id, name) }
        }
    }
    if (renamingThis && folderId != null) {
        TextInputDialog(S.str(R.string.rename_folder), folder?.name.orEmpty(), S.str(R.string.folder_name), S.str(R.string.rename), onDismiss = { renamingThis = false }) { name ->
            renamingThis = false
            runner.busy.run(S.str(R.string.renaming)) { repo.renameFolder(folderId, name) }
        }
    }
    deletingFolder?.let { f ->
        ConfirmDialog(
            title = S.str(R.string.delete_folder_x, f.folder.name),
            text = S.str(R.string.its_x_item_s_are_kept_and_moved_up_one_level, f.documentCount),
            confirmLabel = S.str(R.string.delete_folder),
            onDismiss = { deletingFolder = null },
        ) {
            deletingFolder = null
            runner.busy.run(S.str(R.string.deleting_folder)) { repo.deleteFolder(f.folder.id) }
        }
    }
    if (deletingThis && folderId != null) {
        ConfirmDialog(
            title = S.str(R.string.delete_folder_x, folder?.name),
            text = S.str(R.string.everything_inside_is_kept_and_moved_up_one_l),
            confirmLabel = S.str(R.string.delete_folder),
            onDismiss = { deletingThis = false },
        ) {
            deletingThis = false
            runner.busy.run(S.str(R.string.deleting_folder)) {
                repo.deleteFolder(folderId)
                onBack?.invoke()
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
}

@Composable
private fun AddOption(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint)
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

/** "From This App": tick documents from elsewhere in the library and move them into this folder. */
@Composable
private fun MoveInDialog(folderId: Long, onDismiss: () -> Unit, onMove: (List<Long>) -> Unit) {
    val repo = LocalContext.current.app.repository
    val all by remember { repo.observeDocuments("", null) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val candidates = all.filter { it.document.folderId != folderId }
    var chosen by remember { mutableStateOf(setOf<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.str(R.string.move_documents_here)) },
        text = {
            if (candidates.isEmpty()) {
                Text(S.str(R.string.all_documents_are_already_in_this_folder))
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(candidates, key = { it.document.id }) { s ->
                        val id = s.document.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = if (id in chosen) chosen - id else chosen + id }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DocumentThumbnail(s, Modifier.width(40.dp).height(52.dp))
                            Text(
                                s.document.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            )
                            Checkbox(checked = id in chosen, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onMove(chosen.toList()) }) { Text(S.str(R.string.move_x, chosen.size)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) } },
    )
}

/** White card with a coloured icon, as in the Files header (Import Files / Import Images / Create Folder). */
@Composable
private fun ActionCard(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(36.dp), tint = tint)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
