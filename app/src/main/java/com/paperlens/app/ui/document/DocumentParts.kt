package com.paperlens.app.ui.document

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.data.DocumentEntity
import com.paperlens.app.ui.components.ToolButton
import com.paperlens.app.ui.components.formatDate
import java.io.File

// Building blocks of the document screen: page cards, the unsaved-edits bar, sheets and their rows.

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageCard(
    file: File,
    index: Int,
    aspect: Float?,
    selected: Boolean? = null,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        shadowElevation = 1.dp,
        border = if (selected == true) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.combinedClickable(onLongClick = onLongClick, onLongClickLabel = S.str(R.string.select_page), onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = file,
                contentDescription = S.str(R.string.page_x, index + 1),
                contentScale = ContentScale.Fit,
                modifier = if (aspect != null) Modifier.fillMaxWidth().aspectRatio(aspect) else Modifier.fillMaxWidth(),
            )
            Surface(
                shape = RoundedCornerShape(topEnd = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
            if (selected != null) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    if (selected) S.str(R.string.selected_label) else S.str(R.string.not_selected_label),
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(30.dp)
                        .background(Color.Black.copy(alpha = if (selected) 0f else 0.25f), CircleShape),
                )
            }
        }
    }
}

/** Shown while edits are only a preview: they are kept or thrown away from here. */
@Composable
internal fun UnsavedBar(onDiscard: () -> Unit, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                S.str(R.string.changes_not_saved_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            TextButton(onClick = onDiscard) { Text(S.str(R.string.discard)) }
            androidx.compose.material3.Button(onClick = onSave) { Text(S.str(R.string.save_2)) }
        }
    }
}

/** Where to put the selected pages: start, end, or after a given page. */
@Composable
internal fun MovePagesDialog(pageCount: Int, selectedCount: Int, onDismiss: () -> Unit, onMove: (afterPage: Int) -> Unit) {
    var after by remember { mutableStateOf("") }
    val afterPage = after.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.str(R.string.move_x_page_s, selectedCount)) },
        text = {
            Column {
                TextButton(onClick = { onMove(0) }) { Text(S.str(R.string.to_the_beginning)) }
                TextButton(onClick = { onMove(pageCount) }) { Text(S.str(R.string.to_the_end)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = after,
                        onValueChange = { v -> after = v.filter(Char::isDigit).take(4) },
                        label = { Text(S.str(R.string.after_page_1_x, pageCount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = afterPage != null && afterPage in 1..pageCount,
                        onClick = { afterPage?.let(onMove) },
                    ) { Text(S.str(R.string.move)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) } },
    )
}

@Composable
internal fun BottomAction(label: String, icon: ImageVector, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.onSurface
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color)
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.92f).dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, S.str(R.string.close)) }
            }
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun SheetRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun SheetSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
internal fun ToolGrid(items: List<Triple<String, ImageVector, () -> Unit>>) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, icon, action) -> ToolButton(label, icon, MaterialTheme.colorScheme.onSurface, action, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun BigAction(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .width(84.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(56.dp)) {
            Icon(icon, null, Modifier.padding(14.dp), tint = Color.White)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun MoreHeader(doc: DocumentEntity, coverPath: String?, pageCount: Int, onRename: () -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            coverPath?.let { File(it) },
            null,
            Modifier
                .width(56.dp)
                .height(72.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp)
                .clickable(onClick = onRename),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.Edit, S.str(R.string.rename), Modifier.padding(start = 6.dp).size(18.dp))
            }
            Text(
                S.str(R.string.x_page_s_x, pageCount, formatDate(doc.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
