package com.paperlens.app.ui.word

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.loadWord
import com.paperlens.app.data.saveWord
import com.paperlens.app.export.Exporter
import com.paperlens.app.export.Sharing
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.TextInputDialog
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.toast
import com.paperlens.app.word.DocxWriter
import com.paperlens.app.word.WAlign
import com.paperlens.app.word.WordEditing
import com.paperlens.app.word.plainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/**
 * A Word document: shown as its page (pinch to zoom), editable in place. Edit turns on the cursor and
 * the formatting bar; changes are kept until Save, and leaving with unsaved changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordScreen(documentId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val entity by remember(documentId) { repo.observeDocument(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    var editor by remember { mutableStateOf<WordEditor?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = rememberBusy()
    var askLeave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(documentId) {
        runCatching { repo.loadWord(documentId) }
            .onSuccess { editor = WordEditor(it) }
            .onFailure { error = it.message ?: S.str(R.string.this_word_document_could_not_be_opened) }
    }
    val ed = editor
    val title = entity?.title ?: S.str(R.string.word)

    fun save(then: () -> Unit = {}) {
        val e = editor ?: return
        val snapshot = e.doc
        busy.run(S.str(R.string.saving)) {
            repo.saveWord(documentId, snapshot)
            e.reset(snapshot)
            then()
        }
    }

    fun share() {
        val e = editor ?: return
        busy.run(S.str(R.string.preparing)) {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                File(dir, Exporter.safeName(title) + ".docx").also { DocxWriter.write(e.doc, it) }
            }
            Sharing.share(context, listOf(file), DOCX_MIME, title)
        }
    }

    BackHandler(enabled = ed != null && (ed.dirty || ed.editing)) {
        if (ed!!.dirty) askLeave = true else ed.stopEditing()
    }

    Scaffold(
        containerColor = Color(0xFFE9EAEE),
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
                            when {
                                ed == null -> S.str(R.string.word_document)
                                ed.dirty -> S.str(R.string.changes_not_saved_yet)
                                else -> S.str(R.string.word_document)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ed?.dirty == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (ed != null) {
                        if (ed.editing) {
                            IconButton(onClick = { ed.undo() }, enabled = ed.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, S.str(R.string.undo)) }
                            IconButton(onClick = { ed.redo() }, enabled = ed.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, S.str(R.string.redo)) }
                        }
                        if (ed.dirty) TextButton(onClick = { save() }) { Text(S.str(R.string.save_2), fontWeight = FontWeight.SemiBold) }
                        if (ed.editing) {
                            IconButton(onClick = { ed.stopEditing() }) { Icon(Icons.Filled.Check, S.str(R.string.done_editing)) }
                        } else {
                            IconButton(onClick = { ed.startEditing() }) { Icon(Icons.Filled.Edit, S.str(R.string.edit)) }
                            IconButton(onClick = { share() }) { Icon(Icons.Filled.Share, S.str(R.string.share_as_word)) }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, S.str(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(S.str(R.string.share_as_word_docx)) }, onClick = { menu = false; share() })
                                DropdownMenuItem(text = { Text(S.str(R.string.share_as_text)) }, onClick = {
                                    menu = false
                                    Sharing.shareText(context, ed.doc.plainText())
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ed != null -> WordPageView(ed)
                    error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            if (ed != null && ed.editing) EditBar(ed)
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

/** The page, fitted to the screen width; pinch to zoom about the fingers (then scroll sideways). */
@Composable
private fun WordPageView(ed: WordEditor) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val pictures = remember(ed) { PictureCache { ed.doc.media } }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val marginPx = with(LocalDensity.current) { 10.dp.toPx() }
    // Scroll position that keeps the pinched point under the fingers. It can only be reached once the
    // page has been laid out at the new size, so it is applied whenever the scroll range changes.
    var anchor by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(anchor, horizontal.maxValue, vertical.maxValue) }.collect { (target) ->
            target ?: return@collect
            horizontal.scrollTo(target.x.roundToInt().coerceAtLeast(0))
            vertical.scrollTo(target.y.roundToInt().coerceAtLeast(0))
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two fingers zoom; one finger scrolls as usual.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // A new pinch starts from wherever the page has been scrolled to since.
                    anchor = null
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                val next = (zoom * z).coerceIn(1f, 4f)
                                val k = next / zoom
                                val p = event.calculateCentroid(useCurrent = false)
                                val from = anchor ?: Offset(horizontal.value.toFloat(), vertical.value.toFloat())
                                // Content point under the fingers: scroll + p, scaled about the page margin.
                                anchor = Offset((from.x + p.x - marginPx) * k + marginPx - p.x, (from.y + p.y - marginPx) * k + marginPx - p.y)
                                zoom = next
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val density = LocalDensity.current
        val margin = 10.dp
        val sheetWidth = (maxWidth - margin * 2) * zoom
        val pxPerTwip = with(density) { sheetWidth.toPx() } / ed.doc.page.width
        val labels = rememberListLabels(ed.doc)
        val ctx = PageContext(pxPerTwip, density, pictures, ed, labels)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(vertical)
                .horizontalScroll(horizontal)
                .padding(margin),
        ) {
            Surface(shadowElevation = 2.dp, color = Color.White, modifier = Modifier.width(sheetWidth)) {
                Box(Modifier.heightIn(min = ctx.dp(ed.doc.page.height))) {
                    WordSheet(ed.doc, ctx, Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ---- formatting bar ------------------------------------------------------------------------------

private val TEXT_COLORS = listOf(
    null, 0x000000, 0x595959, 0xC00000, 0xE36C09, 0xB8860B, 0x2E7D32, 0x0070C0, 0x1F3864, 0x7030A0,
)

@Composable
private fun EditBar(ed: WordEditor) {
    val context = LocalContext.current
    var colors by remember { mutableStateOf(false) }
    var styles by remember { mutableStateOf(false) }
    var insert by remember { mutableStateOf(false) }
    var tableMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val bitmap = BitmapUtils.decode(context, uri, 2000)
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            ed.insertPicture(bytes, "jpg", bitmap.width, bitmap.height)
        }.onFailure { context.toast(S.str(R.string.that_picture_could_not_be_added)) }
    }
    val hasFocus = ed.focus != null
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (!hasFocus) {
                Text(
                    S.str(R.string.tap_any_text_to_edit_it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    BarButton(Icons.Filled.Title, S.str(R.string.paragraph_style), hasFocus) { styles = true }
                    DropdownMenu(expanded = styles, onDismissRequest = { styles = false }) {
                        WordEditing.ParagraphKind.entries.forEach { k ->
                            DropdownMenuItem(text = { Text(k.label) }, onClick = { styles = false; ed.kind(k) })
                        }
                    }
                }
                BarButton(Icons.Filled.FormatBold, S.str(R.string.bold), hasFocus, ed.selectionIs { it.bold } && hasFocus) { ed.bold() }
                BarButton(Icons.Filled.FormatItalic, S.str(R.string.italic), hasFocus, ed.selectionIs { it.italic } && hasFocus) { ed.italic() }
                BarButton(Icons.Filled.FormatUnderlined, S.str(R.string.underline), hasFocus, ed.selectionIs { it.underline } && hasFocus) { ed.underline() }
                BarButton(Icons.Filled.TextDecrease, S.str(R.string.smaller_text), hasFocus) { ed.resize(bigger = false) }
                Text(
                    ed.currentStyle?.size?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(30.dp),
                )
                BarButton(Icons.Filled.TextIncrease, S.str(R.string.bigger_text), hasFocus) { ed.resize(bigger = true) }
                Box {
                    BarButton(Icons.Filled.FormatColorText, S.str(R.string.text_colour), hasFocus) { colors = true }
                    DropdownMenu(expanded = colors, onDismissRequest = { colors = false }) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TEXT_COLORS.forEach { c ->
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .background(c?.let { Color(0xFF000000.toInt() or it) } ?: Color.White, CircleShape)
                                        .border(1.dp, Color.Gray, CircleShape)
                                        .clickable { colors = false; ed.color(c) },
                                    contentAlignment = Alignment.Center,
                                ) { if (c == null) Text("A", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
                val align = ed.focusedParagraph?.align ?: WAlign.LEFT
                BarButton(
                    when (align) {
                        WAlign.LEFT -> Icons.AutoMirrored.Filled.FormatAlignLeft
                        WAlign.CENTER -> Icons.Filled.FormatAlignCenter
                        WAlign.RIGHT -> Icons.AutoMirrored.Filled.FormatAlignRight
                        WAlign.JUSTIFY -> Icons.Filled.FormatAlignJustify
                    },
                    S.str(R.string.alignment), hasFocus,
                ) { ed.cycleAlign() }
                val list = ed.focusedParagraph?.list
                BarButton(
                    if (list?.numbered == true) Icons.Filled.FormatListNumbered else Icons.AutoMirrored.Filled.FormatListBulleted,
                    S.str(R.string.list), hasFocus, list != null,
                ) { ed.cycleList() }
                if (ed.focusHasPicture) {
                    BarButton(Icons.Filled.PhotoSizeSelectSmall, S.str(R.string.smaller_picture), true) { ed.scalePictures(0.8f) }
                    BarButton(Icons.Filled.PhotoSizeSelectLarge, S.str(R.string.bigger_picture), true) { ed.scalePictures(1.25f) }
                }
                Box {
                    BarButton(Icons.Filled.Add, S.str(R.string.insert), true) { insert = true }
                    DropdownMenu(expanded = insert, onDismissRequest = { insert = false }) {
                        DropdownMenuItem(text = { Text(S.str(R.string.new_paragraph)) }, onClick = { insert = false; ed.newParagraph() })
                        DropdownMenuItem(text = { Text(S.str(R.string.table_3_3)) }, onClick = { insert = false; ed.insertTable(3, 3) })
                        DropdownMenuItem(text = { Text(S.str(R.string.picture)) }, onClick = {
                            insert = false
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text(S.str(R.string.page_break)) }, onClick = { insert = false; ed.insertPageBreak() })
                    }
                }
                if (ed.focusedTable != null) {
                    Box {
                        BarButton(Icons.Filled.TableChart, S.str(R.string.table), true) { tableMenu = true }
                        DropdownMenu(expanded = tableMenu, onDismissRequest = { tableMenu = false }) {
                            DropdownMenuItem(text = { Text(S.str(R.string.row_above)) }, onClick = { tableMenu = false; ed.rowAbove() })
                            DropdownMenuItem(text = { Text(S.str(R.string.row_below)) }, onClick = { tableMenu = false; ed.rowBelow() })
                            DropdownMenuItem(text = { Text(S.str(R.string.column_left)) }, onClick = { tableMenu = false; ed.columnLeft() })
                            DropdownMenuItem(text = { Text(S.str(R.string.column_right)) }, onClick = { tableMenu = false; ed.columnRight() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(S.str(R.string.delete_row)) }, onClick = { tableMenu = false; ed.deleteRow() })
                            DropdownMenuItem(text = { Text(S.str(R.string.delete_column)) }, onClick = { tableMenu = false; ed.deleteColumn() })
                            DropdownMenuItem(text = { Text(S.str(R.string.delete_table)) }, onClick = { tableMenu = false; ed.deleteTable() })
                        }
                    }
                }
                BarButton(Icons.Filled.Delete, S.str(R.string.delete_paragraph), hasFocus) { ed.deleteBlock() }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (active) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else IconButtonDefaults.iconButtonColors(),
        modifier = Modifier.padding(horizontal = 1.dp),
    ) { Icon(icon, label) }
}
