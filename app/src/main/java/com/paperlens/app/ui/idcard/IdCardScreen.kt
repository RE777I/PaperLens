package com.paperlens.app.ui.idcard

import com.paperlens.app.S
import com.paperlens.app.R

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperlens.app.app
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.ImageFilter
import com.paperlens.app.imaging.PageComposer
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.rememberBusy
import com.paperlens.app.ui.components.rememberDocumentScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Scan both sides of an ID / bank card and lay them out at real size on one A4 page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardScreen(onBack: () -> Unit, openDocument: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repository
    val busy = rememberBusy()
    var front by rememberSaveable { mutableStateOf<Uri?>(null) }
    var back by rememberSaveable { mutableStateOf<Uri?>(null) }

    val scanFront = rememberDocumentScanner(pageLimit = 1) { front = it.first() }
    val scanBack = rememberDocumentScanner(pageLimit = 1) { back = it.first() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.str(R.string.back)) } },
                    title = { Text(S.str(R.string.id_card_2)) },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    S.str(R.string.scan_the_front_and_optionally_the_back_both),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CardSlot(S.str(R.string.front), front, scanFront)
                CardSlot(S.str(R.string.back_optional), back, scanBack)
                Button(
                    enabled = front != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val f = front ?: return@Button
                        val b = back
                        busy.run(S.str(R.string.creating_id_card_page)) {
                            val sheet = withContext(Dispatchers.Default) {
                                PageComposer.composeIdCard(
                                    BitmapUtils.decode(context, f, 1600),
                                    b?.let { BitmapUtils.decode(context, it, 1600) },
                                )
                            }
                            openDocument(repo.createFromBitmaps(listOf(sheet), repo.defaultTitle(S.str(R.string.id_card)), ImageFilter.ORIGINAL))
                        }
                    },
                ) { Text(S.str(R.string.create_document)) }
            }
        }
        BusyOverlay(busy)
    }
}

@Composable
private fun CardSlot(label: String, uri: Uri?, onScan: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(85.6f / 54f)
                .border(2.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .clickable(onClick = onScan),
            contentAlignment = Alignment.Center,
        ) {
            if (uri == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.AddAPhoto, null)
                    Text(S.str(R.string.tap_to_scan))
                }
            } else {
                AsyncImage(model = uri, contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
