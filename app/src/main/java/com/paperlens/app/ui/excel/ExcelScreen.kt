package com.paperlens.app.ui.excel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.loadExcel
import com.paperlens.app.data.saveExcelEdits
import com.paperlens.app.excel.XlsxWriter
import com.paperlens.app.excel.cellName
import com.paperlens.app.excel.editText
import com.paperlens.app.excel.plainText
import com.paperlens.app.export.Exporter
import com.paperlens.app.export.Sharing
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * An Excel document: its sheets as a grid (sheet tabs at the bottom, pinch to zoom). Tapping a cell shows
 * its content above the grid; tapping that, or double-tapping the cell, edits it. Edits are kept until
 * Save, which writes just those cells into the file; leaving with unsaved edits asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcelScreen(documentId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val entity by remember(documentId) { repo.observeDocument(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    var editor by remember { mutableStateOf<SheetEditor?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = rememberBusy()
    var askLeave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(documentId) {
        try {
            editor = SheetEditor(repo.loadExcel(documentId))
        } catch (e: Exception) {
            error = e.message ?: S.str(R.string.this_excel_file_could_not_be_opened)
        }
    }
    val ed = editor
    val title = entity?.title ?: S.str(R.string.excel)

    fun save(then: () -> Unit = {}) {
        val e = editor ?: return
        val book = e.saved
        val edits = e.edits
        busy.run(S.str(R.string.saving)) {
            e.reset(repo.saveExcelEdits(documentId, book, edits))
            then()
        }
    }

    fun share() {
        val e = editor ?: return
        if (e.dirty) {
            // Share what is on screen: the edits written into a copy, the library file left as it was.
            busy.run(S.str(R.string.preparing)) {
                val file = withContext(Dispatchers.IO) {
                    val source = File(entity?.excelPath ?: return@withContext null)
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(dir, Exporter.safeName(title) + ".xlsx").apply {
                        writeBytes(com.paperlens.app.excel.XlsxEditor.apply(source.readBytes(), e.saved, e.edits))
                    }
                } ?: return@run
                Sharing.share(context, listOf(file), XlsxWriter.MIME, title)
            }
        } else {
            busy.run(S.str(R.string.preparing)) {
                val file = withContext(Dispatchers.IO) {
                    val source = File(entity?.excelPath ?: return@withContext null)
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    source.copyTo(File(dir, Exporter.safeName(title) + ".xlsx"), overwrite = true)
                } ?: return@run
                Sharing.share(context, listOf(file), XlsxWriter.MIME, title)
            }
        }
    }

    BackHandler(enabled = ed != null && (ed.dirty || editing)) {
        if (editing) editing = false else askLeave = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (ed?.dirty == true) askLeave = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back))
                    }
                },
                title = {
                    Column(Modifier.clickable { renaming = true }) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (ed?.dirty == true) S.str(R.string.changes_not_saved_yet) else S.str(R.string.excel_document),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ed?.dirty == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (ed != null) {
                        if (ed.dirty) TextButton(onClick = { save() }) { Text(S.str(R.string.save_2), fontWeight = FontWeight.SemiBold) }
                        IconButton(onClick = { share() }) { Icon(Icons.Filled.Share, S.str(R.string.share_as_excel)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, S.str(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(S.str(R.string.share_as_excel_xlsx)) }, onClick = { menu = false; share() })
                                DropdownMenuItem(text = { Text(S.str(R.string.share_as_text)) }, onClick = {
                                    menu = false
                                    Sharing.shareText(context, ed.book.plainText())
                                })
                                DropdownMenuItem(text = { Text(S.str(R.string.rename)) }, onClick = { menu = false; renaming = true })
                                if (ed.dirty) DropdownMenuItem(text = { Text(S.str(R.string.discard_changes_2)) }, onClick = { menu = false; ed.reset(ed.saved) })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            when {
                ed != null -> {
                    CellBar(ed, editing, onEdit = { editing = it }, onFormula = { context.toast(S.str(R.string.formula_results_appear_in_excel)) })
                    HorizontalDivider()
                    SheetGrid(ed, onEdit = { editing = true }, modifier = Modifier.weight(1f).fillMaxWidth())
                    SheetTabs(ed)
                }
                error != null -> Box(Modifier.fillMaxSize()) {
                    Text(error!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                }
                else -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(S.str(R.string.save_changes)) },
            text = { Text(S.str(R.string.you_changed_this_document_save_the_changes_b)) },
            confirmButton = { TextButton(onClick = { askLeave = false; save(onBack) }) { Text(S.str(R.string.save_2)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askLeave = false; editor?.let { it.reset(it.saved) }; onBack() }) { Text(S.str(R.string.discard)) }
                    TextButton(onClick = { askLeave = false }) { Text(S.str(R.string.cancel)) }
                }
            },
        )
    }
    if (renaming) {
        TextInputDialog(S.str(R.string.rename), title, S.str(R.string.name), S.str(R.string.rename), onDismiss = { renaming = false }) { name ->
            renaming = false
            if (name.isNotBlank()) busy.run(S.str(R.string.renaming)) { repo.rename(documentId, name.trim()) }
        }
    }
    BusyOverlay(busy)
}

/** The selected cell's address and content; while editing, a text field with Apply / Cancel. */
@Composable
private fun CellBar(ed: SheetEditor, editing: Boolean, onEdit: (Boolean) -> Unit, onFormula: () -> Unit) {
    val sel = ed.selected
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sel == null) {
                Text(S.str(R.string.tap_a_cell_to_see_or_edit_it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Row
            }
            Text(
                cellName(sel.first, sel.second),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(52.dp),
            )
            val current = ed.sheet[sel.first, sel.second]?.editText().orEmpty()
            if (editing) {
                var value by remember(sel, ed.sheetIndex) { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(sel) { focus.requestFocus() }
                fun apply() {
                    if (value.text != current) {
                        val cell = ed.set(sel.first, sel.second, value.text)
                        if (cell?.formula != null) onFormula()
                    }
                    onEdit(false)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(S.str(R.string.cell_content)) },
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton(onClick = { apply() }) { Icon(Icons.Filled.Check, S.str(R.string.apply_cell_edit)) }
                IconButton(onClick = { onEdit(false) }) { Icon(Icons.Filled.Close, S.str(R.string.cancel_cell_edit)) }
            } else {
                Text(
                    current,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).clickable { onEdit(true) }.padding(vertical = 8.dp),
                )
                IconButton(onClick = { onEdit(true) }) { Icon(Icons.Filled.Edit, S.str(R.string.edit_cell)) }
            }
        }
    }
}

/** One tab per sheet, as at the bottom of Excel. */
@Composable
private fun SheetTabs(ed: SheetEditor) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ed.book.sheets.forEachIndexed { i, s ->
                val active = i == ed.sheetIndex
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .background(if (active) Color.White else Color.Transparent)
                        .clickable { ed.sheetIndex = i; ed.selected = null }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        s.name,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color(0xFF217346) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
