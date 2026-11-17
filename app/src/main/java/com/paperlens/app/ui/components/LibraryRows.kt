package com.paperlens.app.ui.components

import com.paperlens.app.S
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.paperlens.app.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperlens.app.data.DocumentSummary
import com.paperlens.app.data.FolderSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Created per call so a language change while the app runs is picked up.
fun formatDate(millis: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

/** "16/10/2026 15:23 | ▯ 2" line used under document and folder names. */
@Composable
fun MetaLine(date: Long, count: Int, icon: ImageVector, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatDate(date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  |  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outlineVariant)
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(label ?: "$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small "W" tag on the cover of a Word document. */
@Composable
private fun FileBadge(letter: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(4.dp)
            .background(color, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(letter, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private val WordBlue = Color(0xFF2B579A)
private val ExcelGreen = Color(0xFF217346)

@Composable
fun DocumentThumbnail(summary: DocumentSummary, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            summary.document.locked -> Icon(Icons.Filled.Lock, S.str(R.string.locked), tint = MaterialTheme.colorScheme.primary)
            summary.coverPath != null -> AsyncImage(
                model = File(summary.coverPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (summary.document.isWord) FileBadge("W", WordBlue, Modifier.align(Alignment.BottomStart))
        if (summary.document.isExcel) FileBadge("X", ExcelGreen, Modifier.align(Alignment.BottomStart))
    }
}

/** Document row: thumbnail, title, date | pages, and a selection checkbox, as in the Recents / Files lists. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentRow(
    summary: DocumentSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { onCheckedChange(true) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentThumbnail(
            summary,
            Modifier
                .width(68.dp)
                .height(86.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                summary.document.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (summary.document.isWord) {
                MetaLine(summary.document.updatedAt, 0, Icons.Outlined.Description, label = S.str(R.string.word))
            } else if (summary.document.isExcel) {
                MetaLine(summary.document.updatedAt, 0, Icons.Outlined.TableChart, label = S.str(R.string.excel))
            } else {
                MetaLine(summary.document.updatedAt, summary.pageCount, Icons.AutoMirrored.Outlined.InsertDriveFile)
            }
        }
        Checkbox(
            checked = selected,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(uncheckedColor = MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.semantics { contentDescription = S.str(R.string.select_x, summary.document.title) },
        )
    }
}

/** Grid variant for the Files tab's grid view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentTile(summary: DocumentSummary, selected: Boolean, onClick: () -> Unit, onCheckedChange: (Boolean) -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { onCheckedChange(true) })) {
        Box {
            DocumentThumbnail(
                summary,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f),
            )
            Checkbox(
                checked = selected,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .semantics { contentDescription = S.str(R.string.select_x, summary.document.title) },
            )
        }
        Text(
            summary.document.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(formatDate(summary.document.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Folder card row with a "…" menu (rename / delete). */
@Composable
fun FolderRow(folder: FolderSummary, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 18.dp),
            ) {
                Text(folder.folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                MetaLine(folder.updatedAt, folder.documentCount, Icons.AutoMirrored.Outlined.InsertDriveFile)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, S.str(R.string.folder_options), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(S.str(R.string.rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(S.str(R.string.delete_folder)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Round tinted tool icon with a caption underneath (Home quick tools, Tools tab). */
@Composable
fun ToolButton(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(tint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(28.dp), tint = tint)
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

