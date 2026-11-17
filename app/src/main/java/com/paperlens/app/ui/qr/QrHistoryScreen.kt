package com.paperlens.app.ui.qr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperlens.app.R
import com.paperlens.app.S
import com.paperlens.app.app
import com.paperlens.app.data.QrHistoryEntity
import com.paperlens.app.data.addQr
import com.paperlens.app.data.clearQrHistory
import com.paperlens.app.ui.components.QrResultDialog
import com.paperlens.app.ui.components.barcodeFormatName
import com.paperlens.app.ui.components.readCodesFromImage
import com.paperlens.app.ui.components.startQrScan
import com.paperlens.app.ui.components.toast
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val scope = rememberCoroutineScope()
    val history by remember { repo.observeQrHistory() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var shown by remember { mutableStateOf<QrHistoryEntity?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val codes = runCatching { readCodesFromImage(context, uri) }.getOrElse {
                context.toast(S.str(R.string.could_not_read_the_image_x, it.message))
                return@launch
            }
            if (codes.isEmpty()) {
                context.toast(S.str(R.string.no_qr_code_or_barcode_found_in_this_image))
                return@launch
            }
            codes.asReversed().forEach { repo.addQr(it.rawValue ?: it.displayValue!!, barcodeFormatName(it.format)) }
            val first = codes.first()
            val value = codes.joinToString("\n\n") { it.rawValue ?: it.displayValue!! }
            val format = if (codes.size == 1) barcodeFormatName(first.format) else S.str(R.string.x_codes_found, codes.size)
            shown = QrHistoryEntity(value = value, format = format, scannedAt = System.currentTimeMillis())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                title = { Text(S.str(R.string.qr_barcode_history)) },
                actions = {
                    IconButton(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Filled.PhotoLibrary, S.str(R.string.scan_code_from_image)) }
                    IconButton(onClick = { scope.launch { repo.clearQrHistory() } }, enabled = history.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, S.str(R.string.clear_history))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // M3 hides the text slot from accessibility; label the button itself so TalkBack announces it.
                modifier = Modifier.semantics { contentDescription = S.str(R.string.shortcut_qr_long) },
                onClick = {
                    startQrScan(context) { barcode ->
                        val value = barcode.rawValue ?: barcode.displayValue ?: return@startQrScan
                        val format = barcodeFormatName(barcode.format)
                        scope.launch { repo.addQr(value, format) }
                        shown = QrHistoryEntity(value = value, format = format, scannedAt = System.currentTimeMillis())
                    }
                },
                icon = { Icon(Icons.Filled.QrCodeScanner, null) },
                text = { Text(S.str(R.string.scan_code_2)) },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text(S.str(R.string.no_codes_scanned_yet)) }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(history, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.value, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${entry.format} · " + DateFormat.getDateTimeInstance().format(Date(entry.scannedAt)))
                        },
                        modifier = Modifier.clickable { shown = entry },
                    )
                }
            }
        }
    }

    shown?.let { QrResultDialog(it.value, it.format) { shown = null } }
}
