package com.paperlens.app.ui.me

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.AppSettings
import com.paperlens.app.data.observeDocumentCount
import com.paperlens.app.data.observePageCount
import com.paperlens.app.data.storageBytes
import com.paperlens.app.security.AppLock
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.launch

/** Me tab: library stats and app settings / help (no account: everything is local). */
@Composable
fun MeTab(
    openScanSettings: () -> Unit,
    openMoreSettings: () -> Unit,
    openFiles: () -> Unit,
    openCodeHistory: () -> Unit,
    openAppearance: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val documents by remember { app.repository.observeDocumentCount() }.collectAsStateWithLifecycle(initialValue = 0)
    val pages by remember { app.repository.observePageCount() }.collectAsStateWithLifecycle(initialValue = 0)
    var storage by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pages) { storage = app.repository.storageBytes() }
    var dialog by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Color(0xFFDCE8FF), 0.35f to MaterialTheme.colorScheme.background))
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.DocumentScanner, null, Modifier.padding(12.dp), tint = Color.White)
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(S.str(R.string.paperlens), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF14213D))
                        Text(
                            S.str(R.string.no_account_needed_your_scans_never_leave_thi),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF3A4660),
                        )
                    }
                }
            }
        }
        item {
            Card {
                Row(Modifier.padding(vertical = 16.dp)) {
                    Stat("$documents", S.str(R.string.documents), Icons.Outlined.Folder, Modifier.weight(1f))
                    Stat("$pages", S.str(R.string.pages), Icons.AutoMirrored.Outlined.InsertDriveFile, Modifier.weight(1f))
                    Stat(Formatter.formatShortFileSize(context, storage), S.str(R.string.storage), Icons.Outlined.Tune, Modifier.weight(1f))
                }
            }
        }
        item {
            Card {
                MenuRow(Icons.Outlined.Settings, S.str(R.string.scan), onClick = openScanSettings)
                MenuRow(Icons.Outlined.Folder, S.str(R.string.manage_documents), onClick = openFiles)
                MenuRow(Icons.Outlined.QrCodeScanner, S.str(R.string.code_history), onClick = openCodeHistory)
                val setLock: (Boolean) -> Unit = { on ->
                    if (on && !AppLock.isAvailable(context)) {
                        context.toast(S.str(R.string.set_up_a_screen_lock_or_fingerprint_on_this))
                    } else {
                        scope.launch { app.settings.update { it.copy(appLock = on) } }
                    }
                }
                // The whole row toggles, so screen readers read "App lock" with its on/off state.
                MenuRow(
                    Icons.Outlined.Lock,
                    S.str(R.string.app_lock),
                    trailing = { Switch(checked = settings.appLock, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.appLock, role = Role.Switch, onValueChange = setLock),
                )
                MenuRow(Icons.Outlined.Palette, S.str(R.string.appearance), onClick = openAppearance)
                MenuRow(Icons.Outlined.Tune, S.str(R.string.more_settings), onClick = openMoreSettings)
            }
        }
        item {
            Card {
                MenuRow(Icons.Outlined.Print, S.str(R.string.my_printer)) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                        .onFailure { context.toast(S.str(R.string.print_settings_are_not_available_on_this_dev)) }
                }
                MenuRow(Icons.Outlined.ThumbUp, S.str(R.string.recommend)) {
                    val text = S.str(R.string.i_scan_documents_with_paperlens_offline_oc)
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
                    )
                }
                MenuRow(Icons.AutoMirrored.Filled.HelpOutline, S.str(R.string.help)) { dialog = "help" }
                MenuRow(Icons.Outlined.Info, S.str(R.string.about)) { dialog = "about" }
            }
        }
    }

    when (dialog) {
        "help" -> InfoDialog(
            S.str(R.string.help),
            S.str(R.string.tap_the_camera_button_to_scan_hold_the_phone) +
                S.str(R.string.imported_photos_are_cropped_and_straightened) +
                S.str(R.string.tap_a_page_to_change_its_filter_rotate_it_si) +
                S.str(R.string.search_finds_words_inside_your_scans_once_th) +
                S.str(R.string.long_press_or_tick_documents_to_share_move_m),
        ) { dialog = null }
        "about" -> InfoDialog(
            S.str(R.string.about_paperlens),
            S.str(R.string.version_x_n_n, context.packageManager.getPackageInfo(context.packageName, 0).versionName) +
                S.str(R.string.built_with_google_ml_kit_document_scanner_te) +
                S.str(R.string.jetpack_compose_room_and_pdfbox_android_n_nn),
        ) { dialog = null }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) { Column { content() } }
}

@Composable
private fun Stat(value: String, label: String, icon: ImageVector, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = { Icon(icon, null) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
    )
}


@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.ok)) } },
    )
}

