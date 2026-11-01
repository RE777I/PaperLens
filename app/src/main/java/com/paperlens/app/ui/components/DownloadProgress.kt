package com.paperlens.app.ui.components

import com.paperlens.app.S
import com.paperlens.app.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.ml.DocTranslator
import com.paperlens.app.ml.LanguageDownloads

/**
 * Returns a function that asks for the notification permission (Android 13+) the first time a
 * download starts, then runs the given action whatever the answer: progress also shows in the app.
 */
@Composable
fun rememberNotificationAsk(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending.value?.invoke()
        pending.value = null
    }
    return remember(launcher) {
        { action ->
            val needed = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needed) {
                pending.value = action
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                action()
            }
        }
    }
}

/** A bar with "Chinese · 12.3 of 30.1 MB · Downloading" for each language downloading now. */
@Composable
fun LanguageDownloadBars(modifier: Modifier = Modifier, showCancel: Boolean = false) {
    val active by LanguageDownloads.active.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        active.forEach { (tag, p) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(DocTranslator.displayName(tag), p.sizeText, p.note).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    p.fraction?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge) }
                    if (showCancel) TextButton(onClick = { LanguageDownloads.cancel(tag) }) { Text(S.str(R.string.cancel)) }
                }
                val f = p.fraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}

/** Progress circle (with percentage when the size is known) for a language list row. */
@Composable
fun DownloadCircle(progress: LanguageDownloads.Progress) {
    val f = progress.fraction
    if (f != null) {
        CircularProgressIndicator(progress = { f }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    } else {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}
