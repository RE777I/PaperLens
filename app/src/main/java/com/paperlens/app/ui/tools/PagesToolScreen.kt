package com.paperlens.app.ui.tools

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.paperlens.app.app
import com.paperlens.app.data.PageEntity
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.rememberBusy
import java.io.File

/** Extract Pages (pick pages → new document) and Reorder Pages (move pages up / down → save). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesToolScreen(documentId: Long, mode: PagesMode, onBack: () -> Unit, openDocument: (Long) -> Unit) {
    val repo = LocalContext.current.app.repository
    val busy = rememberBusy()
    val doc by remember(documentId) { repo.observeDocument(documentId) }.collectAsStateWithLifecycle(initialValue = null)
    val pages by remember(documentId) { repo.observePages(documentId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var picked by remember { mutableStateOf(setOf<Long>()) }
    var order by remember { mutableStateOf(listOf<PageEntity>()) }
    LaunchedEffect(pages) { if (order.isEmpty() || order.size != pages.size) order = pages }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                    title = { Text(if (mode == PagesMode.EXTRACT) S.str(R.string.extract_pages_2) else S.str(R.string.reorder_pages_2)) },
                    actions = {
                        if (mode == PagesMode.EXTRACT) {
                            TextButton(onClick = { picked = if (picked.size == pages.size) emptySet() else pages.map { it.id }.toSet() }) {
                                Text(if (picked.size == pages.size && pages.isNotEmpty()) S.str(R.string.none) else S.str(R.string.all))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    if (mode == PagesMode.EXTRACT) {
                        Button(
                            enabled = picked.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy.run(S.str(R.string.extracting)) {
                                    val title = "${doc?.title ?: S.str(R.string.document)} (pages)"
                                    openDocument(repo.extractPages(documentId, picked, title))
                                }
                            },
                        ) { Text(S.str(R.string.extract_x_page_s_to_a_new_document, picked.size)) }
                    } else {
                        Button(
                            enabled = order.map { it.id } != pages.map { it.id },
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy.run(S.str(R.string.saving_order)) {
                                    repo.reorderPages(documentId, order.map { it.id })
                                    onBack()
                                }
                            },
                        ) { Text(S.str(R.string.save_order)) }
                    }
                }
            },
        ) { padding ->
            if (mode == PagesMode.EXTRACT) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    items(pages, key = { it.id }) { page ->
                        val on = page.id in picked
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .border(
                                    if (on) 3.dp else 1.dp,
                                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { picked = if (on) picked - page.id else picked + page.id },
                        ) {
                            AsyncImage(File(page.imagePath), S.str(R.string.page_x, page.position + 1), Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                            Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                            Text(
                                "${page.position + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(order, key = { _, p -> p.id }) { index, page ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                            AsyncImage(
                                File(page.imagePath),
                                null,
                                Modifier
                                    .width(64.dp)
                                    .height(84.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Fit,
                            )
                            Text(
                                S.str(R.string.page_x, page.position + 1),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(enabled = index > 0, onClick = { order = order.toMutableList().apply { add(index - 1, removeAt(index)) } }) {
                                Icon(Icons.Filled.KeyboardArrowUp, S.str(R.string.move_up))
                            }
                            IconButton(
                                enabled = index < order.lastIndex,
                                onClick = { order = order.toMutableList().apply { add(index + 1, removeAt(index)) } },
                            ) { Icon(Icons.Filled.KeyboardArrowDown, S.str(R.string.move_down)) }
                        }
                    }
                }
            }
        }
        BusyOverlay(busy)
    }
}
