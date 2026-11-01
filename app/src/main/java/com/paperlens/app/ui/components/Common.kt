package com.paperlens.app.ui.components

import com.paperlens.app.S
import com.paperlens.app.R

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.paperlens.app.app
import com.paperlens.app.data.AppSettings
import com.paperlens.app.data.ScannerMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun Context.findActivity(): FragmentActivity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    error("No FragmentActivity in context chain")
}

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun Context.copyToClipboard(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(S.str(R.string.paperlens), text))
    toast(S.str(R.string.copied))
}

/** Runs one long operation at a time with a blocking progress overlay and error toasts. */
class BusyController(private val scope: CoroutineScope, private val context: Context) {
    var message by mutableStateOf<String?>(null)
        private set

    fun run(label: String, block: suspend () -> Unit) {
        if (message != null) return
        scope.launch {
            message = label
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: S.str(R.string.something_went_wrong), Toast.LENGTH_LONG).show()
            } finally {
                message = null
            }
        }
    }
}

@Composable
fun rememberBusy(): BusyController {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember { BusyController(scope, context) }
}

@Composable
fun BusyOverlay(busy: BusyController) {
    val message = busy.message ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // swallow touches while busy
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(message)
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    confirmLabel: String = S.str(R.string.ok),
    isPassword: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = if (isPassword) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.str(R.string.cancel)) } },
    )
}

/**
 * Launches the Google ML Kit document scanner: live edge detection, auto-capture, crop/perspective
 * correction, rotation, stain/finger cleaning, multi-page and gallery import.
 * [pageLimit] 0 means unlimited pages.
 */
@Composable
fun rememberDocumentScanner(pageLimit: Int = 0, onScanned: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val settings by context.app.settings.settings.collectAsState(initial = AppSettings())
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) onScanned(uris)
        }
    }
    return remember<() -> Unit>(pageLimit, launcher, settings.scannerGallery, settings.scannerMode) {
        {
            val mode = when (settings.scannerMode) {
                ScannerMode.FULL -> GmsDocumentScannerOptions.SCANNER_MODE_FULL
                ScannerMode.FILTERS -> GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER
                ScannerMode.BASIC -> GmsDocumentScannerOptions.SCANNER_MODE_BASE
            }
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(settings.scannerGallery)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(mode)
                .apply { if (pageLimit > 0) setPageLimit(pageLimit) }
                .build()
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(context.findActivity())
                .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { e -> context.toast(S.str(R.string.scanner_unavailable_x, e.message)) }
        }
    }
}

/** Launches the ML Kit code scanner (QR, EAN, UPC, Code 128, Data Matrix, PDF417, Aztec …). */
fun startQrScan(context: Context, onResult: (Barcode) -> Unit) {
    GmsBarcodeScanning.getClient(context).startScan()
        .addOnSuccessListener { barcode -> onResult(barcode) }
        .addOnFailureListener { e -> context.toast(S.str(R.string.code_scanner_unavailable_x, e.message)) }
}

/** Finds every QR code / barcode in a saved image (largest first). */
suspend fun readCodesFromImage(context: Context, uri: Uri): List<Barcode> {
    val scanner = BarcodeScanning.getClient()
    return try {
        scanner.process(InputImage.fromFilePath(context, uri)).await()
            .filter { (it.rawValue ?: it.displayValue) != null }
            .sortedByDescending { it.boundingBox?.let { b -> b.width() * b.height() } ?: 0 }
    } finally {
        scanner.close()
    }
}

fun barcodeFormatName(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> S.str(R.string.qr_code)
    Barcode.FORMAT_AZTEC -> S.str(R.string.aztec)
    Barcode.FORMAT_DATA_MATRIX -> S.str(R.string.data_matrix)
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_CODE_128 -> S.str(R.string.code_128)
    Barcode.FORMAT_CODE_39 -> S.str(R.string.code_39)
    Barcode.FORMAT_CODE_93 -> S.str(R.string.code_93)
    Barcode.FORMAT_CODABAR -> S.str(R.string.codabar)
    Barcode.FORMAT_ITF -> S.str(R.string.itf)
    else -> S.str(R.string.barcode)
}

@Composable
fun QrResultDialog(value: String, format: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isLink = (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) && value.none { it.isWhitespace() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(format) },
        text = { SelectionContainer { Text(value) } },
        confirmButton = {
            if (isLink) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                    onDismiss()
                }) { Text(S.str(R.string.open)) }
            } else {
                TextButton(onClick = { context.copyToClipboard(value); onDismiss() }) { Text(S.str(R.string.copy)) }
            }
        },
        dismissButton = {
            if (isLink) TextButton(onClick = { context.copyToClipboard(value); onDismiss() }) { Text(S.str(R.string.copy)) }
            else TextButton(onClick = onDismiss) { Text(S.str(R.string.close)) }
        },
    )
}
