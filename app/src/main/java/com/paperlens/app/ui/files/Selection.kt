package com.paperlens.app.ui.files

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperlens.app.app
import com.paperlens.app.data.DocumentEntity
import com.paperlens.app.data.FolderEntity
import com.paperlens.app.ui.components.ConfirmDialog
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.toast
import com.paperlens.app.ui.tools.Tool
import com.paperlens.app.ui.tools.ToolRunner

/** Checkbox selection shared by the Home and Files lists. Order of selection is kept (for merging). */
@Stable
class SelectionState {
    var ids by mutableStateOf(listOf<Long>())
        private set

    /** Selection mode switched on from the toolbar, before anything is ticked. */
    var selecting by mutableStateOf(false)

    val active: Boolean get() = selecting || ids.isNotEmpty()

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun setAll(all: List<Long>) {
        ids = all
    }

    fun clear() {
        ids = emptyList()
        selecting = false
    }
}

@Composable
fun rememberSelectionState(): SelectionState = remember { SelectionState() }

/** Replaces the screen header while documents are selected: select all, share, move, merge, delete. */
@Composable
fun SelectionBar(selection: SelectionState, visible: List<DocumentEntity>, runner: ToolRunner) {
    val context = LocalContext.current
    val repo = context.app.repository
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val chosen = selection.ids.mapNotNull { id -> visible.firstOrNull { it.id == id } }

    // Drop ids of documents that no longer exist (deleted / merged elsewhere).
    LaunchedEffect(visible) {
        val existing = visible.map { it.id }.toSet()
        if (selection.ids.any { it !in existing }) selection.setAll(selection.ids.filter { it in existing })
    }

    Column(
        Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selection.clear() }) { Icon(Icons.Filled.Close, S.str(R.string.cancel_selection)) }
            Text("${selection.ids.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (selection.ids.size == visible.size) selection.setAll(emptyList()) else selection.setAll(visible.map { it.id })
            }) { Text(if (selection.ids.size == visible.size && visible.isNotEmpty()) S.str(R.string.select_none) else S.str(R.string.select_all)) }
        }
        // Labelled actions on their own row so none of them is pushed off narrow screens.
        Row(Modifier.fillMaxWidth()) {
            BarAction(Icons.Filled.Share, S.str(R.string.share_2), chosen.size == 1) { runner.sharePdf(chosen.first()) }
            BarAction(Icons.AutoMirrored.Filled.DriveFileMove, S.str(R.string.move), chosen.isNotEmpty()) { moving = true }
            BarAction(Icons.AutoMirrored.Filled.MergeType, S.str(R.string.merge), chosen.size >= 2) {
                runner.runOn(Tool.MERGE_FILES, chosen)
                selection.clear()
            }
            BarAction(Icons.Filled.Delete, S.str(R.string.delete), chosen.isNotEmpty(), tint = DeleteRed) { deleting = true }
        }
    }

    if (moving) {
        MoveToFolderDialog(onDismiss = { moving = false }) { folderId ->
            moving = false
            val ids = chosen.map { it.id }
            selection.clear()
            runner.busy.run(S.str(R.string.moving)) { repo.moveToFolder(ids, folderId) }
            context.toast(S.str(R.string.moved_x_document_s, ids.size))
        }
    }
    if (deleting) {
        ConfirmDialog(
            title = S.str(R.string.delete_x_document_s, chosen.size),
            text = S.str(R.string.pages_and_their_files_are_removed_from_this),
            confirmLabel = S.str(R.string.delete),
            onDismiss = { deleting = false },
        ) {
            deleting = false
            val ids = chosen.map { it.id }
            selection.clear()
            runner.busy.run(S.str(R.string.deleting)) { ids.forEach { repo.deleteDocument(it) } }
        }
    }
}

private val DeleteRed = Color(0xFFE5533D)

@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, enabled: Boolean, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        tint != Color.Unspecified -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Pick a destination: top level, an existing folder, or a new folder. */
@Composable
fun MoveToFolderDialog(onDismiss: () -> Unit, onMove: (Long?) -> Unit) {
    val repo = LocalContext.current.app.repository
    var folders by remember { mutableStateOf(emptyList<Pair<FolderEntity, String>>()) }
    LaunchedEffect(Unit) { folders = repo.folderPaths() }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(newName) {
        newName?.let { onMove(repo.createFolder(it)) }
    }

    if (creating) {
        TextInputDialog(S.str(R.string.new_folder), "", S.str(R.string.folder_name), S.str(R.string.create), onDismiss = { creating = false }) { name ->
            creating = false
            newName = name
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.str(R.string.move_to)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { DestinationRow(Icons.Filled.Home, S.str(R.string.top_level)) { onMove(null) } }
                items(folders, key = { it.first.id }) { (folder, path) -> DestinationRow(Icons.Filled.Folder, path) { onMove(folder.id) } }
                item { DestinationRow(Icons.Filled.CreateNewFolder, S.str(R.string.new_folder_2)) { creating = true } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) } },
    )
}

@Composable
private fun DestinationRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 16.dp)) { Text(label, style = MaterialTheme.typography.bodyLarge) }
    }
}
