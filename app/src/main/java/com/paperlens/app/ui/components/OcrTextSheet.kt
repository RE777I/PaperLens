package com.paperlens.app.ui.components

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paperlens.app.app
import com.paperlens.app.export.Sharing
import com.paperlens.app.ml.DocTranslator
import com.paperlens.app.ml.LanguageDownloads
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Recognised text, full screen (so scrolling can never swipe it away): a Text tab to read, edit, copy
 * or share it, and a Translate tab with From / To languages, download progress and the result.
 * [onSave] is null when the text is not editable (e.g. whole-document text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrTextSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null,
    /** Photo translation: open on the Translate tab and translate into the default language at once. */
    translateOnOpen: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by rememberSaveable(text) { mutableStateOf(text) }
    var tab by rememberSaveable { mutableIntStateOf(if (translateOnOpen) 1 else 0) }

    // Translate state
    var source by rememberSaveable { mutableStateOf<String?>(null) } // null = auto-detect
    var detected by remember { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var translation by rememberSaveable(text) { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) } // "from" / "to"

    LaunchedEffect(Unit) { if (target == null) target = context.app.settings.current().translateTarget }
    val askNotifications = rememberNotificationAsk()

    fun translate() {
        val to = target ?: return
        if (status != null) return
        error = null
        translation = null
        scope.launch {
            try {
                val from = source ?: run {
                    status = S.str(R.string.detecting_the_language)
                    DocTranslator.detect(value).also { detected = it }
                        ?: throw DocTranslator.Failure(S.str(R.string.couldn_t_tell_which_language_this_is_choose))
                }
                translation = DocTranslator.translate(value, from, to) { status = it }
            } catch (e: DocTranslator.Failure) {
                error = e.message
            } catch (e: Exception) {
                error = S.str(R.string.translation_failed_x, e.message)
            } finally {
                status = null
            }
        }
    }

    LaunchedEffect(translateOnOpen, target) {
        if (translateOnOpen && target != null && translation == null && error == null && text.isNotBlank()) translate()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, S.str(R.string.close)) } },
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        actions = {
                            if (onSave != null && tab == 0) {
                                TextButton(onClick = { onSave(value) }) {
                                    Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                                    Text(S.str(R.string.save))
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
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(S.str(R.string.text)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(S.str(R.string.translate)) })
                    }
                    if (tab == 0) {
                        TextTab(text.isBlank(), value, editable = onSave != null, onChange = { value = it })
                    } else {
                        TranslateTab(
                            sourceLabel = source?.let(DocTranslator::displayName)
                                ?: (S.str(R.string.auto_detect) + (detected?.let { " (${DocTranslator.displayName(it)})" } ?: "")),
                            targetLabel = target?.let(DocTranslator::displayName) ?: "",
                            onPickSource = { picking = "from" },
                            onPickTarget = { picking = "to" },
                            onSwap = {
                                val from = source ?: detected
                                if (from != null && target != null) {
                                    source = target
                                    target = from
                                    translation = null
                                }
                            },
                            canSwap = (source ?: detected) != null,
                            busy = status != null,
                            status = status,
                            error = error,
                            translation = translation,
                            onTranslate = { askNotifications { translate() } },
                            noText = value.isBlank(),
                        )
                    }
                }
            }
        }
    }

    picking?.let { which ->
        LanguagePicker(
            title = if (which == "from") S.str(R.string.translate_from) else S.str(R.string.translate_into),
            offerAuto = which == "from",
            selected = if (which == "from") source else target,
            onDismiss = { picking = null },
        ) { tag ->
            picking = null
            if (which == "from") source = tag else target = tag ?: target
            translation = null
            error = null
        }
    }
}

@Composable
private fun TextTab(empty: Boolean, value: String, editable: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (empty) Text(S.str(R.string.no_text_was_recognised_try_another_ocr_langu), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            readOnly = !editable,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { context.copyToClipboard(value) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Text(S.str(R.string.copy_2)) }
            TextButton(onClick = { Sharing.shareText(context, value) }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(S.str(R.string.share)) }
        }
    }
}

@Composable
private fun TranslateTab(
    sourceLabel: String,
    targetLabel: String,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    onSwap: () -> Unit,
    canSwap: Boolean,
    busy: Boolean,
    status: String?,
    error: String?,
    translation: String?,
    onTranslate: () -> Unit,
    noText: Boolean,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LanguageButton(S.str(R.string.from), sourceLabel, onPickSource, Modifier.weight(1f))
            IconButton(onClick = onSwap, enabled = canSwap && !busy) { Icon(Icons.Filled.SwapHoriz, S.str(R.string.swap_languages)) }
            LanguageButton(S.str(R.string.to), targetLabel, onPickTarget, Modifier.weight(1f))
        }
        Button(onClick = onTranslate, enabled = !busy && !noText, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Translate, null, Modifier.size(18.dp))
            Text(S.str(R.string.translate_2))
        }
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            val downloads by LanguageDownloads.active.collectAsStateWithLifecycle()
            if (downloads.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) else LanguageDownloadBars(Modifier.fillMaxWidth())
        }
        if (error != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
        if (translation != null) {
            OutlinedTextField(
                value = translation,
                onValueChange = {},
                readOnly = true,
                label = { Text(S.str(R.string.translation)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.copyToClipboard(translation) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Text(S.str(R.string.copy_2)) }
                TextButton(onClick = { Sharing.shareText(context, translation) }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(S.str(R.string.share)) }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text(
                S.str(R.string.translation_runs_on_the_phone_each_language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LanguageButton(caption: String, label: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
        }
    }
}

/** Searchable language list; downloaded languages first. [offerAuto] adds "Auto-detect" (picked as null). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePicker(title: String, offerAuto: Boolean, selected: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var downloaded by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) { downloaded = runCatching { DocTranslator.downloaded() }.getOrDefault(emptySet()) }
    val matches = DocTranslator.languages.filter { it.second.contains(query.trim(), ignoreCase = true) }
    val (have, rest) = matches.partition { it.first in downloaded }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                        title = { Text(title) },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(S.str(R.string.search_languages)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn {
                        if (offerAuto && query.isBlank()) {
                            item { LanguageRow(S.str(R.string.auto_detect), selected == null, downloaded = false) { onPick(null) } }
                        }
                        if (have.isNotEmpty()) item { SectionHeader(S.str(R.string.on_this_phone)) }
                        items(have, key = { "d" + it.first }) { (tag, name) -> LanguageRow(name, tag == selected, downloaded = true) { onPick(tag) } }
                        if (rest.isNotEmpty()) item { SectionHeader(S.str(R.string.download_when_used_30_50_mb_each)) }
                        items(rest, key = { "r" + it.first }) { (tag, name) -> LanguageRow(name, tag == selected, downloaded = false) { onPick(tag) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, downloaded: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (downloaded) ({ Text(S.str(R.string.downloaded)) }) else null,
        trailingContent = if (selected) ({ Icon(Icons.Filled.CheckCircle, S.str(R.string.selected_label), tint = MaterialTheme.colorScheme.primary) }) else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
