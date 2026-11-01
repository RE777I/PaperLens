package com.paperlens.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.paperlens.app.security.AppLock
import com.paperlens.app.ui.PaperLensNavHost
import com.paperlens.app.ui.theme.PaperLensTheme

/** Requests coming from outside the app: launcher shortcuts and "share to PaperLens". */
sealed interface ExternalRequest {
    data object Scan : ExternalRequest
    data object ScanQr : ExternalRequest
    data class Import(val uris: List<Uri>, val isPdf: Boolean) : ExternalRequest
}

class MainActivity : FragmentActivity() {

    private var externalRequest by mutableStateOf<ExternalRequest?>(null)
    private var unlocked by mutableStateOf(false)
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        unlocked = savedInstanceState?.getBoolean(KEY_UNLOCKED) ?: false
        if (savedInstanceState == null) externalRequest = parse(intent)

        setContent {
            PaperLensTheme {
                val settings by app.settings.settings.collectAsState(initial = null)
                val current = settings
                when {
                    current == null -> Surface(Modifier.fillMaxSize()) {}
                    current.appLock && !unlocked -> LockScreen(onUnlock = ::unlock)
                    else -> PaperLensNavHost(
                        externalRequest = externalRequest,
                        onExternalRequestHandled = { externalRequest = null },
                    )
                }
                LaunchedEffect(current?.appLock) { if (current?.appLock == true && !unlocked) unlock() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        externalRequest = parse(intent)
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Re-lock after two minutes in the background (short trips to the scanner UI don't count).
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) unlocked = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, unlocked)
    }

    private fun unlock() {
        AppLock.authenticate(this, S.str(R.string.unlock_paperlens)) { ok -> if (ok) unlocked = true }
    }

    private fun parse(intent: Intent?): ExternalRequest? {
        intent ?: return null
        return when (intent.action) {
            ACTION_SCAN -> ExternalRequest.Scan
            ACTION_SCAN_QR -> ExternalRequest.ScanQr
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { ExternalRequest.Import(listOf(it), isPdf(intent.type, it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }
                ?.let { ExternalRequest.Import(it, isPdf(intent.type, it.first())) }
            Intent.ACTION_VIEW -> intent.data?.let { ExternalRequest.Import(listOf(it), isPdf(intent.type, it)) }
            else -> null
        }
    }

    private fun isPdf(type: String?, uri: Uri): Boolean =
        type == "application/pdf" || contentResolver.getType(uri) == "application/pdf"

    companion object {
        const val ACTION_SCAN = "com.paperlens.app.action.SCAN"
        const val ACTION_SCAN_QR = "com.paperlens.app.action.SCAN_QR"
        private const val KEY_UNLOCKED = "unlocked"
        private const val RELOCK_AFTER_MS = 2 * 60 * 1000L
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(S.str(R.string.paperlens_is_locked), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text(S.str(R.string.unlock)) }
        }
    }
}
