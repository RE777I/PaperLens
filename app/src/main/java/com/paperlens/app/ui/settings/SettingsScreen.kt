package com.paperlens.app.ui.settings

import com.paperlens.app.S
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import com.paperlens.app.R

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.paperlens.app.imaging.CornerModel
import com.paperlens.app.imaging.CornerOutput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.app
import com.paperlens.app.data.AppSettings
import com.paperlens.app.data.ExportQuality
import com.paperlens.app.data.PdfPageSize
import com.paperlens.app.data.ScannerMode
import com.paperlens.app.export.GallerySaver
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.ui.components.LanguagePicker
import com.paperlens.app.ml.OcrScript
import com.paperlens.app.security.AppLock
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sub-pages of More Settings. */
enum class SettingsSection(private val titleRes: Int) {
    APPEARANCE(R.string.appearance),
    SHARE_EXPORT(R.string.share_export),
    SECURITY(R.string.security),
    STORAGE(R.string.free_up_space),
    TEXT(R.string.extract_text),
    LANGUAGES(R.string.translation_languages),
;
    val title: String get() = S.str(titleRes)
}

/** Me → Scan: how the camera scanner behaves. */
@Composable
fun ScanSettingsScreen(onBack: () -> Unit) {
    SettingsPage(S.str(R.string.scan), onBack) { s, update ->
        SwitchSetting(S.str(R.string.auto_adjust_borders), S.str(R.string.find_and_straighten_the_page_in_photos_impor), s.autoCrop) { v ->
            update { it.copy(autoCrop = v) }
        }
        CornerModelSetting()
        SwitchSetting(
            S.str(R.string.use_google_document_scanner),
            S.str(R.string.instead_of_the_paperlens_camera_with_modes),
            s.useGoogleScanner,
        ) { v -> update { it.copy(useGoogleScanner = v) } }
        if (s.useGoogleScanner) {
            ChoiceSetting(S.str(R.string.google_scanner_mode), s.scannerMode, ScannerMode.entries, { "${it.label}: ${it.description}" }) { v ->
                update { it.copy(scannerMode = v) }
            }
        }
        SwitchSetting(S.str(R.string.start_with_camera), S.str(R.string.open_the_scanner_as_soon_as_paperlens_star), s.startWithCamera) { v ->
            update { it.copy(startWithCamera = v) }
        }
        SwitchSetting(S.str(R.string.import_from_gallery_2), S.str(R.string.show_the_gallery_button_inside_the_scanner), s.scannerGallery) { v ->
            update { it.copy(scannerGallery = v) }
        }
        if (GallerySaver.supported) {
            SwitchSetting(S.str(R.string.save_scans_to_gallery_after_scanning), S.str(R.string.also_keep_a_copy_of_each_page_in_pictures_sn), s.saveToGallery) { v ->
                update { it.copy(saveToGallery = v) }
            }
        }
        ChoiceSetting(S.str(R.string.default_filter), s.defaultFilter, ImageFilter.entries, { it.label }) { v ->
            update { it.copy(defaultFilter = v) }
        }
        SwitchSetting(S.str(R.string.recognise_text_automatically), S.str(R.string.run_ocr_on_every_new_page_so_search_finds_it), s.autoOcr) { v ->
            update { it.copy(autoOcr = v) }
        }
    }
}

/** Me → More Settings: a menu of setting groups, like the original app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingsScreen(onBack: () -> Unit, openSection: (SettingsSection) -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                title = { Text(S.str(R.string.more_settings)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            SettingsSection.entries.forEach { section -> ChevronRow(section.title) { openSection(section) } }
            ChevronRow(S.str(R.string.permission_manager)) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    }
}

@Composable
fun SettingsSectionScreen(section: SettingsSection, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsPage(section.title, onBack) { s, update ->
        when (section) {
            SettingsSection.APPEARANCE -> AppearanceSection(s, update)
            SettingsSection.LANGUAGES -> TranslationLanguagesSection(s, update)
            SettingsSection.SHARE_EXPORT -> {
                ChoiceSetting(S.str(R.string.pdf_page_size), s.pdfPageSize, PdfPageSize.entries, { it.label }) { v -> update { it.copy(pdfPageSize = v) } }
                ChoiceSetting(S.str(R.string.image_quality_file_size), s.exportQuality, ExportQuality.entries, { it.label }) { v ->
                    update { it.copy(exportQuality = v) }
                }
                SwitchSetting(
                    S.str(R.string.searchable_pdf),
                    S.str(R.string.hide_the_recognised_text_behind_each_scan_so),
                    s.searchablePdf,
                ) { v -> update { it.copy(searchablePdf = v) } }
            }
            SettingsSection.SECURITY -> {
                SwitchSetting(S.str(R.string.app_lock), S.str(R.string.require_fingerprint_face_or_device_pin_to_op), s.appLock) { v ->
                    if (v && !AppLock.isAvailable(context)) {
                        context.toast(S.str(R.string.set_up_a_screen_lock_or_fingerprint_on_this))
                    } else {
                        update { it.copy(appLock = v) }
                    }
                }
                Text(
                    S.str(R.string.single_documents_can_be_locked_from_their_me),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            SettingsSection.STORAGE -> StorageSection()
            SettingsSection.TEXT -> {
                ChoiceSetting(S.str(R.string.ocr_language), s.ocrScript, OcrScript.entries, { it.label }) { v ->
                    if (v != s.ocrScript) {
                        update { it.copy(ocrScript = v) }
                        // text read in the old script is mostly wrong for the new one
                        scope.launch { context.app.repository.clearOcrText() }
                    }
                }
                var pickTarget by remember { mutableStateOf(false) }
                TranslateTargetRow(s) { pickTarget = true }
                if (pickTarget) {
                    LanguagePicker(S.str(R.string.translate_into), offerAuto = false, selected = s.translateTarget, onDismiss = { pickTarget = false }) { tag ->
                        pickTarget = false
                        if (tag != null) update { it.copy(translateTarget = tag) }
                    }
                }
                SwitchSetting(S.str(R.string.recognise_text_automatically), S.str(R.string.run_ocr_on_every_new_page_so_search_finds_it), s.autoOcr) { v ->
                    update { it.copy(autoOcr = v) }
                }
            }
        }
    }
}

/**
 * Which model finds the page corners (camera, crop screen and imports): the built-in one, none (line and
 * colour finding only), or an imported `.onnx` file.
 */
@Composable
private fun CornerModelSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // The built-in model's download: progress shown in its row; the list is read again when it changes.
    val download by CornerModel.download.collectAsStateWithLifecycle()
    LaunchedEffect(download) { refresh++ }
    val models = remember(refresh) { CornerModel.available(context) }
    val selected = remember(refresh) { CornerModel.selected(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.') ?: S.str(R.string.imported_model)
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { CornerModel.import(context, uri, name) }
            busy = false
            result.onSuccess {
                CornerModel.select(context, it.id)
                context.toast(S.str(R.string.imported_x_and_switched_to_it, it.name))
            }.onFailure { context.toast(S.str(R.string.can_t_use_this_model_x, it.message)) }
            refresh++
        }
    }
    ListItem(
        headlineContent = { Text(S.str(R.string.page_detection_model)) },
        supportingContent = { Text(selected.name) },
        modifier = Modifier.clickable { open = true },
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(S.str(R.string.page_detection_model)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    S.str(R.string.finds_the_page_corners_in_the_camera_the_cro) +
                        S.str(R.string.with_one_image_input_and_either_4_corner_hea),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                models.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { CornerModel.select(context, m.id); refresh++ }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m.id == selected.id, onClick = { CornerModel.select(context, m.id); refresh++ })
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge)
                            val size = Formatter.formatShortFileSize(context, m.bytes)
                            val detail = when {
                                m.id == CornerModel.NONE -> S.str(R.string.lightest_and_fastest_less_reliable_on_busy_b)
                                // The built-in model before it is on the phone: download state instead of its details.
                                !m.available -> when (val d = download) {
                                    is CornerModel.Download.Running -> S.str(
                                        R.string.model_downloading_x_of_x,
                                        Formatter.formatShortFileSize(context, d.done),
                                        Formatter.formatShortFileSize(context, d.total),
                                    )
                                    is CornerModel.Download.Failed -> S.str(R.string.download_failed_x, d.reason)
                                    CornerModel.Download.Idle -> S.str(R.string.model_not_downloaded_x, size)
                                }
                                else -> buildString {
                                    append(size)
                                    append(" · ${m.inputWidth}×${m.inputHeight} · ")
                                    append(if (m.output == CornerOutput.HEATMAPS) S.str(R.string.heatmaps) else S.str(R.string.corner_points))
                                    if (!m.builtIn) append(if (m.rgb) S.str(R.string.rgb) else S.str(R.string.bgr))
                                }
                            }
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!m.available && download !is CornerModel.Download.Running) {
                            TextButton(onClick = { CornerModel.downloadBuiltIn(context) }) { Text(S.str(R.string.download)) }
                        }
                        if (!m.builtIn) {
                            TextButton(onClick = { CornerModel.setRgb(context, m.id, !m.rgb); refresh++ }) { Text(if (m.rgb) S.str(R.string.use_bgr) else S.str(R.string.use_rgb)) }
                            IconButton(onClick = { CornerModel.delete(context, m.id); refresh++ }) { Icon(Icons.Default.Delete, S.str(R.string.delete_x_3, m.name)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(S.str(R.string.done)) } },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }) {
                Text(if (busy) S.str(R.string.importing) else S.str(R.string.import_onnx))
            }
        },
    )
}

@Composable
private fun StorageSection() {
    val context = LocalContext.current
    val app = context.app
    var documentsBytes by remember { mutableLongStateOf(0L) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        documentsBytes = app.repository.storageBytes()
        cacheBytes = withContext(Dispatchers.IO) { context.cacheDir.walkTopDown().filter(File::isFile).sumOf { it.length() } }
    }
    ListItem(
        headlineContent = { Text(S.str(R.string.your_documents)) },
        supportingContent = { Text(Formatter.formatShortFileSize(context, documentsBytes)) },
    )
    ListItem(
        headlineContent = { Text(S.str(R.string.temporary_files)) },
        supportingContent = { Text(S.str(R.string.shared_exports_and_scanner_leftovers_x, Formatter.formatShortFileSize(context, cacheBytes))) },
    )
    Box(Modifier.padding(16.dp)) {
        Button(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                context.toast(S.str(R.string.freed_x, Formatter.formatShortFileSize(context, cacheBytes)))
                refresh++
            }
        }) { Text(S.str(R.string.clear_temporary_files)) }
    }
}

/** Common scaffold for a settings page bound to [AppSettings]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (AppSettings, ((AppSettings) -> AppSettings) -> Unit) -> Unit,
) {
    val store = LocalContext.current.app.settings
    val scope = rememberCoroutineScope()
    val s by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                title = { Text(title) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            content(s) { transform -> scope.launch { store.update(transform) } }
        }
    }
}

@Composable
private fun ChevronRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchSetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        // The whole row is the switch, so screen readers read its title with the on/off state.
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun <T> ChoiceSetting(title: String, value: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(label(value)) },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
