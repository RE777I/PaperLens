package com.paperlens.app.ui.settings

import com.paperlens.app.S
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import com.paperlens.app.R

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paperlens.app.data.AccentColor
import com.paperlens.app.data.AppSettings
import com.paperlens.app.data.ThemeMode
import com.paperlens.app.ml.DocTranslator
import com.paperlens.app.ml.LanguageDownloads
import com.paperlens.app.ui.components.DownloadCircle
import com.paperlens.app.ui.components.rememberNotificationAsk
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.ui.components.ConfirmDialog
import com.paperlens.app.ui.components.LanguagePicker
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.launch

/** Me → Appearance: light / dark, AMOLED black, wallpaper colours and the accent colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Heading(S.str(R.string.theme))
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        ThemeMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = s.themeMode == mode,
                onClick = { update { it.copy(themeMode = mode) } },
                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
            ) { Text(if (mode == ThemeMode.SYSTEM) S.str(R.string.system) else mode.label) }
        }
    }
    Spacer8()
    ToggleRow(S.str(R.string.amoled_black), S.str(R.string.pure_black_backgrounds_in_the_dark_theme_sav), s.pureBlack) { v ->
        update { it.copy(pureBlack = v) }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ToggleRow(S.str(R.string.wallpaper_colours), S.str(R.string.use_colours_from_your_wallpaper_material_you), s.dynamicColor) { v ->
            update { it.copy(dynamicColor = v) }
        }
    }

    Heading(S.str(R.string.accent_colour))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .alpha(if (s.dynamicColor) 0.4f else 1f),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AccentColor.entries.forEach { accent ->
            val on = s.accent == accent
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(Color(accent.light), CircleShape)
                        .border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable(enabled = !s.dynamicColor) { update { it.copy(accent = accent) } },
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.Filled.Check, "${accent.label} selected", tint = Color.White) }
                Text(accent.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (s.dynamicColor) {
        Text(
            S.str(R.string.turn_off_wallpaper_colours_to_choose_an_acce),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * More Settings → Translation Languages: which language models are on the phone (with their delete
 * buttons), downloading another one, and the default "translate into" language.
 */
@Composable
fun TranslationLanguagesSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var downloaded by remember { mutableStateOf<Set<String>?>(null) }
    val downloads by LanguageDownloads.active.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationAsk()
    var picking by remember { mutableStateOf<String?>(null) } // "add" / "target"
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        downloaded = runCatching { DocTranslator.downloaded() }.getOrElse {
            context.toast(S.str(R.string.couldn_t_list_languages_x, it.message))
            emptySet()
        }
    }

    TranslateTargetRow(s) { picking = "target" }
    Text(
        S.str(R.string.each_language_is_a_separate_download_of_30_5) +
            S.str(R.string.english_is_built_in_delete_languages_you_no),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
    Heading(S.str(R.string.on_this_phone))
    val list = downloaded
    if (list == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
    } else {
        list.map { it to DocTranslator.displayName(it) }.sortedBy { it.second }.forEach { (tag, name) ->
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text(if (tag == "en") S.str(R.string.built_in) else S.str(R.string.downloaded)) },
                trailingContent = if (tag == "en") null else ({
                    IconButton(onClick = { deleting = tag }) { Icon(Icons.Outlined.DeleteOutline, S.str(R.string.delete_x_3, name)) }
                }),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
    if (downloads.isNotEmpty()) Heading(S.str(R.string.downloading))
    downloads.forEach { (tag, p) ->
        ListItem(
            leadingContent = { DownloadCircle(p) },
            headlineContent = { Text(DocTranslator.displayName(tag) + (p.fraction?.let { "  ${(it * 100).toInt()}%" } ?: "")) },
            supportingContent = { Text(listOf(p.sizeText, p.note).filter { it.isNotEmpty() }.joinToString(" · ")) },
            trailingContent = { TextButton(onClick = { LanguageDownloads.cancel(tag) }) { Text(S.str(R.string.cancel)) } },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
    Button(
        onClick = { picking = "add" },
        modifier = Modifier.padding(16.dp),
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
        Text(S.str(R.string.download_a_language))
    }

    picking?.let { which ->
        LanguagePicker(
            title = if (which == "add") S.str(R.string.download_a_language_2) else S.str(R.string.translate_into),
            offerAuto = false,
            selected = if (which == "target") s.translateTarget else null,
            onDismiss = { picking = null },
        ) { tag ->
            picking = null
            if (tag == null) return@LanguagePicker
            if (which == "target") {
                update { it.copy(translateTarget = tag) }
            } else if (downloaded?.contains(tag) != true) {
                askNotifications {
                    // Runs in the background (and keeps going if you leave this page); progress shows above.
                    LanguageDownloads.start(tag) { error ->
                        when (error) {
                            null -> context.toast("${DocTranslator.displayName(tag)} downloaded")
                            is kotlinx.coroutines.CancellationException -> Unit
                            else -> context.toast(S.str(R.string.download_failed_x, error.message))
                        }
                        refresh++
                    }
                }
            }
        }
    }
    deleting?.let { tag ->
        ConfirmDialog(
            title = S.str(R.string.delete_x_4, DocTranslator.displayName(tag)),
            text = S.str(R.string.it_downloads_again_the_next_time_you_transla),
            confirmLabel = S.str(R.string.delete),
            onDismiss = { deleting = null },
        ) {
            deleting = null
            scope.launch {
                runCatching { DocTranslator.delete(tag) }.onFailure { context.toast(S.str(R.string.couldn_t_delete_x, it.message)) }
                refresh++
            }
        }
    }
}

/** "Translate into" with the searchable language list (instead of a 60-item dropdown). */
@Composable
fun TranslateTargetRow(s: AppSettings, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(S.str(R.string.translate_into)) },
        supportingContent = { Text(DocTranslator.displayName(s.translateTarget)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Spacer8() = Box(Modifier.size(8.dp))

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        // The whole row is the switch, so screen readers read its title with the on/off state.
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}
