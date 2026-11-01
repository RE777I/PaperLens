package com.paperlens.app.ui.textedit

import com.paperlens.app.S
import com.paperlens.app.R

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperlens.app.app
import com.paperlens.app.data.PageEntity
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.TextEdit
import com.paperlens.app.imaging.TextEdits
import com.paperlens.app.ml.TextLayout
import com.paperlens.app.ml.TextLine
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.ConfirmDialog
import com.paperlens.app.ui.components.copyToClipboard
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val Accent = Color(0xFF14A38B)
private val Hint = Color(0xFFEF8A17)

private data class LineKey(val pageId: Long, val index: Int)

/** One applied change; [newText] null means the line was deleted. */
private data class Op(val key: LineKey, val edit: TextEdit, val newText: String?)

private class PageInfo(val page: PageEntity, val width: Int, val height: Int)

private data class Selection(val pageId: Long, val indices: Set<Int>)

/**
 * Edit the text printed on the scanned pages themselves: scroll through the document, tap a line,
 * then copy, delete, select all or rewrite it. Deleted text is painted out with the paper colour;
 * rewritten text is drawn in the same size, width, weight and ink colour. Save bakes it into the pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditScreen(documentId: Long, startIndex: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val busy = rememberBusy()

    var pages by remember { mutableStateOf<List<PageInfo>?>(null) }
    val lines = remember { mutableStateMapOf<Long, List<TextLine>>() }
    val history = remember { mutableStateListOf<Op>() }
    val redo = remember { mutableStateListOf<Op>() }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var editing by remember { mutableStateOf<LineKey?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    // One full-resolution page kept for colour sampling.
    var sampled by remember { mutableStateOf<Pair<Long, Bitmap>?>(null) }

    LaunchedEffect(documentId) {
        pages = withContext(Dispatchers.IO) {
            repo.getPages(documentId).map { p -> BitmapUtils.imageSize(File(p.imagePath)).let { (w, h) -> PageInfo(p, w, h) } }
        }
    }

    fun currentText(key: LineKey): String? {
        history.lastOrNull { it.key == key }?.let { return it.newText }
        return lines[key.pageId]?.getOrNull(key.index)?.text
    }

    val characters = lines.entries.sumOf { (pageId, list) -> list.indices.sumOf { currentText(LineKey(pageId, it))?.length ?: 0 } }

    suspend fun bitmapFor(page: PageEntity): Bitmap {
        sampled?.takeIf { it.first == page.id }?.let { return it.second }
        val bmp = withContext(Dispatchers.Default) { BitmapUtils.decode(File(page.imagePath)) }
        sampled = page.id to bmp
        return bmp
    }

    fun push(ops: List<Op>) {
        history.addAll(ops)
        redo.clear()
    }

    fun delete(sel: Selection) {
        val info = pages?.firstOrNull { it.page.id == sel.pageId } ?: return
        val pageLines = lines[sel.pageId] ?: return
        selection = null
        busy.run(S.str(R.string.erasing)) {
            val bmp = bitmapFor(info.page)
            val ops = sel.indices.mapNotNull { i ->
                val key = LineKey(sel.pageId, i)
                if (currentText(key) == null) null else Op(key, TextEdits.erase(bmp, pageLines[i].box), null)
            }
            push(ops)
        }
    }

    fun replace(key: LineKey, text: String) {
        val info = pages?.firstOrNull { it.page.id == key.pageId } ?: return
        val line = lines[key.pageId]?.getOrNull(key.index) ?: return
        editing = null
        selection = null
        busy.run(S.str(R.string.updating_text)) {
            val bmp = bitmapFor(info.page)
            push(listOf(Op(key, TextEdits.replace(bmp, line.box, line.text, text), text)))
        }
    }

    fun save() {
        val all = pages ?: return
        busy.run(S.str(R.string.saving)) {
            for (info in all) {
                val ops = history.filter { it.key.pageId == info.page.id }
                if (ops.isEmpty()) continue
                val page = repo.getPage(info.page.id) ?: continue
                val updated = repo.bakeEdit(page) { bmp -> TextEdits.render(bmp, ops.map { it.edit }, bmp.width.toFloat() / info.width) }
                val text = lines[info.page.id].orEmpty().indices.mapNotNull { currentText(LineKey(info.page.id, it)) }.joinToString("\n")
                repo.setOcrText(updated, text)
            }
            context.toast(S.str(R.string.saved))
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: close, Save
            Row(
                Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (history.isEmpty()) onClose() else confirmClose = true }) { Icon(Icons.Filled.Close, S.str(R.string.close)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::save, enabled = history.isNotEmpty(), shape = RoundedCornerShape(10.dp)) { Text(S.str(R.string.save_2)) }
            }
            // Second bar: hint or undo / redo, character count
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (history.isEmpty() && redo.isEmpty()) {
                    Text(S.str(R.string.tap_text_to_edit), color = Hint, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 14.dp))
                } else {
                    IconButton(enabled = history.isNotEmpty(), onClick = { redo.add(history.removeAt(history.lastIndex)) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, S.str(R.string.undo))
                    }
                    IconButton(enabled = redo.isNotEmpty(), onClick = { history.add(redo.removeAt(redo.lastIndex)) }) {
                        Icon(Icons.AutoMirrored.Filled.Redo, S.str(R.string.redo))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("$characters characters", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            }

            val all = pages
            if (all != null) {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex.coerceIn(0, (all.size - 1).coerceAtLeast(0)))
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    items(all, key = { it.page.id }) { info ->
                        LaunchedEffect(info.page.id) {
                            if (lines[info.page.id] == null) {
                                val script = context.app.settings.current().ocrScript
                                lines[info.page.id] = runCatching { TextLayout.lines(context, File(info.page.imagePath), script) }
                                    .getOrElse { e ->
                                        context.toast(S.str(R.string.text_recognition_failed_x, e.message))
                                        emptyList()
                                    }
                            }
                        }
                        EditablePage(
                            info = info,
                            lines = lines[info.page.id],
                            ops = history.filter { it.key.pageId == info.page.id },
                            selection = selection?.takeIf { it.pageId == info.page.id },
                            isDeleted = { i -> currentText(LineKey(info.page.id, i)) == null },
                            onTap = { index -> selection = index?.let { Selection(info.page.id, setOf(it)) } },
                            onCopy = { sel ->
                                context.copyToClipboard(sel.indices.sorted().mapNotNull { currentText(LineKey(sel.pageId, it)) }.joinToString("\n"))
                                selection = null
                            },
                            onDelete = { delete(it) },
                            onSelectAll = {
                                val count = lines[info.page.id]?.size ?: 0
                                selection = Selection(info.page.id, (0 until count).filter { currentText(LineKey(info.page.id, it)) != null }.toSet())
                            },
                            onEdit = { sel -> editing = LineKey(sel.pageId, sel.indices.first()) },
                        )
                    }
                }
            }
        }
        BusyOverlay(busy)
    }

    editing?.let { key ->
        EditTextSheet(initial = currentText(key).orEmpty(), onDismiss = { editing = null }) { replace(key, it) }
    }
    if (confirmClose) {
        ConfirmDialog(S.str(R.string.discard_changes), S.str(R.string.your_text_edits_have_not_been_saved), S.str(R.string.discard), onDismiss = { confirmClose = false }) {
            confirmClose = false
            onClose()
        }
    }
}

@Composable
private fun EditablePage(
    info: PageInfo,
    lines: List<TextLine>?,
    ops: List<Op>,
    selection: Selection?,
    isDeleted: (Int) -> Boolean,
    onTap: (Int?) -> Unit,
    onCopy: (Selection) -> Unit,
    onDelete: (Selection) -> Unit,
    onSelectAll: () -> Unit,
    onEdit: (Selection) -> Unit,
) {
    val density = LocalDensity.current
    val ratio = if (info.width > 0 && info.height > 0) info.width.toFloat() / info.height else 0.707f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(Color.White),
    ) {
        val pxWidth = with(density) { maxWidth.toPx() }
        val pxHeight = with(density) { maxHeight.toPx() }
        val scale = pxWidth / info.width

        AsyncImage(File(info.page.imagePath), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(lines, ops.size) {
                    detectTapGestures { pos ->
                        val x = pos.x / scale
                        val y = pos.y / scale
                        val hit = lines?.withIndex()
                            ?.filter { (i, l) -> !isDeleted(i) && RectF(l.box).apply { inset(-8f, -8f) }.contains(x, y) }
                            ?.minByOrNull { (_, l) -> l.box.width() * l.box.height() }
                        onTap(hit?.index)
                    }
                },
        ) {
            // committed edits, drawn in page-image coordinates
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(scale, scale)
                ops.forEach { TextEdits.draw(native, it.edit) }
                native.restore()
            }
            // selection highlight with handles
            if (selection != null && lines != null) {
                val boxes = selection.indices.mapNotNull { lines.getOrNull(it)?.box }
                boxes.forEach { b ->
                    drawRect(Accent.copy(alpha = 0.22f), Offset(b.left * scale, b.top * scale), Size(b.width() * scale, b.height() * scale))
                }
                if (boxes.isNotEmpty()) {
                    val first = boxes.minBy { it.top }
                    val last = boxes.maxBy { it.bottom }
                    drawCircle(Accent, 7.dp.toPx(), Offset(first.left * scale, first.top * scale))
                    drawCircle(Accent, 7.dp.toPx(), Offset(last.right * scale, last.bottom * scale))
                }
            }
        }

        if (lines == null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            ) { Text(S.str(R.string.recognising_text), color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
        }

        // Context menu under (or above) the selection
        if (selection != null && lines != null && selection.indices.isNotEmpty()) {
            val boxes = selection.indices.mapNotNull { lines.getOrNull(it)?.box }
            val top = boxes.minOf { it.top } * scale
            val bottom = boxes.maxOf { it.bottom } * scale
            val left = boxes.minOf { it.left } * scale
            val menuW = with(density) { 220.dp.toPx() }
            val menuH = with(density) { (if (selection.indices.size == 1) 4 * 52 else 3 * 52).dp.toPx() }
            val gap = with(density) { 14.dp.toPx() }
            val y = if (bottom + gap + menuH <= pxHeight) bottom + gap else (top - gap - menuH).coerceAtLeast(0f)
            val x = left.coerceIn(0f, (pxWidth - menuW).coerceAtLeast(0f))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2B2D31),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(220.dp),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    MenuEntry(Icons.Filled.ContentCopy, S.str(R.string.copy)) { onCopy(selection) }
                    MenuEntry(Icons.Filled.Delete, S.str(R.string.delete)) { onDelete(selection) }
                    MenuEntry(Icons.Filled.SelectAll, S.str(R.string.select_all_2), onClick = onSelectAll)
                    if (selection.indices.size == 1) MenuEntry(Icons.Filled.EditNote, S.str(R.string.edit_text), tint = Accent) { onEdit(selection) }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint)
            Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTextSheet(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text(S.str(R.string.edit_text), style = MaterialTheme.typography.headlineSmall, color = Accent)
            Text(
                S.str(R.string.the_new_text_is_drawn_in_the_same_size_weigh),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(
                Modifier.padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp)) { Text(S.str(R.string.cancel)) }
                Button(
                    onClick = { onConfirm(value.trim()) },
                    enabled = value.isNotBlank() && value != initial,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(S.str(R.string.confirm)) }
            }
        }
    }
}
