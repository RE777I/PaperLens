package com.paperlens.app.ui.home

import com.paperlens.app.S
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.paperlens.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.app
import com.paperlens.app.data.DocumentSummary
import com.paperlens.app.ui.components.DocumentRow
import com.paperlens.app.ui.components.ToolButton
import com.paperlens.app.ui.files.SelectionBar
import com.paperlens.app.ui.files.rememberSelectionState
import com.paperlens.app.ui.tools.Tool
import com.paperlens.app.ui.tools.ToolRunner

private const val RECENT_COUNT = 10

/** Home tab: search, quick tools, and recent documents (the newest one with quick actions). */
@Composable
fun HomeTab(runner: ToolRunner, openSettings: () -> Unit, openFiles: () -> Unit) {
    val repo = LocalContext.current.app.repository
    var query by rememberSaveable { mutableStateOf("") }
    val documents by remember(query) { repo.observeDocuments(query.trim(), null) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val selection = rememberSelectionState()
    val searching = query.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (selection.active) {
            SelectionBar(selection, documents.map { it.document }, runner)
        } else {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(query, { query = it }, S.str(R.string.search), Modifier.weight(1f))
                IconButton(onClick = { runner.run(Tool.SCAN_CODE) }) {
                    Icon(Icons.Filled.QrCodeScanner, S.str(R.string.scan_code_2), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = openSettings) {
                    Icon(Icons.Filled.Settings, S.str(R.string.settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (!searching) {
                item {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Tool.quick.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { tool -> ToolButton(tool.label, tool.icon, tool.tint, { runner.run(tool) }, Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (searching) S.str(R.string.results) else S.str(R.string.recents),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (!searching) {
                            TextButton(onClick = openFiles) {
                                Text(S.str(R.string.view_all), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            val shown = if (searching) documents else documents.take(RECENT_COUNT)
            if (shown.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (searching) S.str(R.string.no_matches) else S.str(R.string.no_documents_yet_ntap_the_camera_to_scan_you),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(shown, key = { _, s -> s.document.id }) { index, summary ->
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    DocumentRow(
                        summary = summary,
                        selected = summary.document.id in selection.ids,
                        onClick = {
                            if (selection.active) selection.toggle(summary.document.id) else runner.openDocument(summary.document)
                        },
                        onCheckedChange = { selection.toggle(summary.document.id) },
                    )
                    if (index == 0 && !searching && !selection.active) QuickActions(summary, runner)
                    if (index < shown.lastIndex) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                }
            }
        }
    }
}

/** Share / To Word / View pills under the newest document. */
@Composable
private fun QuickActions(summary: DocumentSummary, runner: ToolRunner) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Pill(S.str(R.string.share_2), null, Modifier.weight(1f)) { runner.sharePdf(summary.document) }
        Pill(S.str(R.string.to_word), Icons.Filled.AutoAwesome, Modifier.weight(1f)) { runner.runOn(Tool.TO_WORD, listOf(summary.document)) }
        Pill(S.str(R.string.view), null, Modifier.weight(1f)) { runner.openDocument(summary.document) }
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector?, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.size(6.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Rounded grey search box used at the top of Home, Files and Tools.
 * It only becomes focusable once tapped: otherwise Compose hands focus to it whenever a dialog
 * closes, which pops the keyboard up uninvited.
 */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) focusRequester.requestFocus() }

    Surface(
        onClick = { editing = true },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Screen readers announce the box as the search field even before it is typed into.
        modifier = modifier
            .height(46.dp)
            .semantics { contentDescription = placeholder },
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = placeholder }
                        .focusRequester(focusRequester)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                focused = true
                            } else if (focused) {
                                focused = false
                                editing = false
                            }
                        },
                )
                // While not editing, a transparent layer takes the tap (the field itself cannot focus).
                if (!editing) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { editing = true },
                    )
                }
            }
            if (value.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    S.str(R.string.clear_search),
                    Modifier
                        .size(20.dp)
                        .clickable { onValueChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
