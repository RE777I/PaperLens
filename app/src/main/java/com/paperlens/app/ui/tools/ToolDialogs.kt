package com.paperlens.app.ui.tools

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.DocumentEntity
import com.paperlens.app.data.ExportQuality
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.imaging.QrCodes
import com.paperlens.app.runLogged
import com.paperlens.app.ui.components.DocumentThumbnail
import com.paperlens.app.ui.components.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The document a tool works on: the library's documents the tool can use ([accepts]), a search box once
 * the list is long, and shortcuts to scan or import a new one and carry straight on with the tool.
 */
@Composable
internal fun DocumentPickerDialog(
    title: String,
    multiple: Boolean,
    accepts: (DocumentEntity) -> Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<DocumentEntity>) -> Unit,
    onScanNew: (() -> Unit)?,
    onImport: (() -> Unit)?,
) {
    val repo = LocalContext.current.app.repository
    val all by remember { repo.observeDocuments("", null) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val usable = all.filter { accepts(it.document) }
    var query by rememberSaveable { mutableStateOf("") }
    val docs = if (query.isBlank()) usable else usable.filter { it.document.title.contains(query.trim(), ignoreCase = true) }
    var chosen by remember { mutableStateOf(listOf<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (onScanNew != null && onImport != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShortcutButton(S.str(R.string.scan_new), Icons.Filled.DocumentScanner, onScanNew, Modifier.weight(1f))
                        ShortcutButton(S.str(R.string.import_file), Icons.Filled.DriveFolderUpload, onImport, Modifier.weight(1f))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                if (usable.size > SEARCH_FROM) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(S.str(R.string.search_documents)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                when {
                    usable.isEmpty() -> Text(
                        if (all.isEmpty()) S.str(R.string.no_documents_yet_scan_or_import_something_fi) else S.str(R.string.no_documents_this_tool_can_use),
                        Modifier.padding(vertical = 8.dp),
                    )
                    docs.isEmpty() -> Text(S.str(R.string.no_documents_match), Modifier.padding(vertical = 8.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(docs, key = { it.document.id }) { summary ->
                            val id = summary.document.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (multiple) chosen = if (id in chosen) chosen - id else chosen + id
                                        else onPicked(listOf(summary.document))
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                DocumentThumbnail(summary, Modifier.width(40.dp).height(52.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(summary.document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        S.str(R.string.x_x_p, formatDate(summary.document.updatedAt), summary.pageCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (multiple) {
                                    val order = chosen.indexOf(id)
                                    if (order >= 0) Text("${order + 1}", color = MaterialTheme.colorScheme.primary)
                                    Checkbox(checked = order >= 0, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) {
                TextButton(
                    enabled = chosen.size >= 2,
                    onClick = { onPicked(chosen.mapNotNull { id -> all.firstOrNull { it.document.id == id }?.document }) },
                ) { Text(S.str(R.string.merge_x, chosen.size)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) } },
    )
}

/** The picker shows its search box from this many documents. */
private const val SEARCH_FROM = 6

@Composable
private fun ShortcutButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Compress: the PDF's size at each quality, then Share or Save to device. */
@Composable
internal fun CompressDialog(doc: DocumentEntity, runner: ToolRunner) {
    val context = LocalContext.current
    val sizes = remember(doc.id) { mutableStateMapOf<ExportQuality, Long>() }
    var choice by rememberSaveable(doc.id) { mutableStateOf(ExportQuality.MEDIUM) }
    LaunchedEffect(doc.id) {
        // Original first: the others are shown against it.
        for (q in ExportQuality.entries) {
            runLogged("Compress", "Measuring ${q.name}") { runner.compressedSize(doc, q) }.getOrNull()?.let { sizes[q] = it }
        }
    }
    val original = sizes[ExportQuality.HIGH]
    AlertDialog(
        onDismissRequest = { runner.compressFor = null },
        title = { Text(S.str(R.string.compress_x, doc.title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                ExportQuality.entries.forEach { q ->
                    val bytes = sizes[q]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = q == choice, role = Role.RadioButton) { choice = q }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = q == choice, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(q.label, Modifier.weight(1f))
                        if (bytes == null) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            val saved = if (original != null && original > 0 && q != ExportQuality.HIGH) 100 - (bytes * 100 / original).toInt() else 0
                            Text(
                                if (saved > 0) S.str(R.string.about_x_y_smaller, Formatter.formatShortFileSize(context, bytes), saved)
                                else S.str(R.string.about_x, Formatter.formatShortFileSize(context, bytes)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    S.str(R.string.compress_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { runner.compress(doc, choice, save = true) }) { Text(S.str(R.string.save_to_device_2)) }
                TextButton(onClick = { runner.compress(doc, choice, save = false) }) { Text(S.str(R.string.share_2)) }
            }
        },
        dismissButton = { TextButton(onClick = { runner.compressFor = null }) { Text(S.str(R.string.cancel)) } },
    )
}

/** Page Numbers: where, how they are written, and the first number. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageNumbersDialog(doc: DocumentEntity, runner: ToolRunner) {
    var place by rememberSaveable { mutableStateOf(PageComposer.NumberPlace.BOTTOM_CENTER) }
    var style by rememberSaveable { mutableStateOf(NumberStyle.PLAIN) }
    var start by rememberSaveable { mutableStateOf("1") }
    val first = start.toIntOrNull()?.takeIf { it in 0..99_999 }
    AlertDialog(
        onDismissRequest = { runner.numbersFor = null },
        title = { Text(S.str(R.string.page_numbers)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(S.str(R.string.position), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageComposer.NumberPlace.entries.forEach { p ->
                        FilterChip(selected = p == place, onClick = { place = p }, label = { Text(p.label) })
                    }
                }
                Text(S.str(R.string.number_style), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberStyle.entries.forEach { st ->
                        // The chip shows what page 1 of 5 would look like.
                        FilterChip(selected = st == style, onClick = { style = st }, label = { Text(st.text(1, 5)) })
                    }
                }
                OutlinedTextField(
                    value = start,
                    onValueChange = { v -> start = v.filter(Char::isDigit).take(5) },
                    label = { Text(S.str(R.string.first_number)) },
                    singleLine = true,
                    isError = first == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    S.str(R.string.page_numbers_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = first != null, onClick = { runner.pageNumbers(doc, first ?: 1, place, style) }) { Text(S.str(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = { runner.numbersFor = null }) { Text(S.str(R.string.cancel)) } },
    )
}

private val PageComposer.NumberPlace.label: String
    get() = S.str(
        when (this) {
            PageComposer.NumberPlace.BOTTOM_CENTER -> R.string.bottom_centre
            PageComposer.NumberPlace.BOTTOM_RIGHT -> R.string.bottom_right
            PageComposer.NumberPlace.BOTTOM_LEFT -> R.string.bottom_left
            PageComposer.NumberPlace.TOP_RIGHT -> R.string.top_right
        },
    )

/** Create Code: text or a link, or a Wi-Fi network to join. */
@Composable
internal fun CreateCodeDialog(runner: ToolRunner) {
    var wifi by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var network by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val value = if (wifi) network.trim().takeIf { it.isNotEmpty() }?.let { QrCodes.wifi(it, password) } else text.trim().takeIf { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = { runner.creatingCode = false },
        title = { Text(S.str(R.string.create_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !wifi, onClick = { wifi = false }, label = { Text(S.str(R.string.text_or_link)) })
                    FilterChip(selected = wifi, onClick = { wifi = true }, label = { Text(S.str(R.string.wi_fi)) })
                }
                if (wifi) {
                    OutlinedTextField(network, { network = it.take(64) }, label = { Text(S.str(R.string.network_name)) }, singleLine = true)
                    OutlinedTextField(
                        password,
                        { password = it.take(64) },
                        label = { Text(S.str(R.string.password)) },
                        supportingText = { Text(S.str(R.string.leave_empty_for_an_open_network)) },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        text,
                        { text = it.take(QrCodes.MAX_LENGTH) },
                        label = { Text(S.str(R.string.text_or_link)) },
                        minLines = 2,
                        maxLines = 5,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = value != null, onClick = { runner.createCode(value.orEmpty()) }) { Text(S.str(R.string.create)) } },
        dismissButton = { TextButton(onClick = { runner.creatingCode = false }) { Text(S.str(R.string.cancel)) } },
    )
}

/** The created QR code, to show to another phone or share as a picture. */
@Composable
internal fun CodeImageDialog(value: String, runner: ToolRunner) {
    var done by remember(value) { mutableStateOf(false) }
    var code by remember(value) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(value) {
        code = withContext(Dispatchers.Default) { QrCodes.bitmap(value, 720) }
        done = true
    }
    val image = code
    AlertDialog(
        onDismissRequest = { runner.codeShown = null },
        title = { Text(S.str(R.string.qr_code)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    image != null -> Image(
                        image.asImageBitmap(),
                        S.str(R.string.qr_code),
                        Modifier.size(240.dp),
                        // Sharp squares, not a smoothed picture.
                        filterQuality = FilterQuality.None,
                    )
                    done -> Text(S.str(R.string.too_long_for_a_qr_code))
                    else -> CircularProgressIndicator(Modifier.padding(40.dp))
                }
                SelectionContainer {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = image != null, onClick = { runner.shareCode(value) }) { Text(S.str(R.string.share_2)) } },
        dismissButton = { TextButton(onClick = { runner.codeShown = null }) { Text(S.str(R.string.close)) } },
    )
}
