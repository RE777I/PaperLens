package com.paperlens.app.ui.edit

import com.paperlens.app.S
import com.paperlens.app.R

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.paperlens.app.app
import com.paperlens.app.data.PageEntity
import com.paperlens.app.data.outline
import com.paperlens.app.imaging.BitmapUtils
import com.paperlens.app.imaging.DocumentDetector
import com.paperlens.app.imaging.PageOutline
import com.paperlens.app.ui.capture.CropReview
import com.paperlens.app.ui.components.BusyOverlay
import com.paperlens.app.ui.components.rememberBusy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Crop for a saved page: shows the whole photo the page was cut from with the current corners, so the
 * crop can be widened as well as narrowed. Pages scanned before photos were kept start from their
 * current image.
 */
@Composable
fun CropPageScreen(pageId: Long, onDone: () -> Unit) {
    val repo = LocalContext.current.app.repository
    val busy = rememberBusy()
    var page by remember { mutableStateOf<PageEntity?>(null) }
    var photo by remember { mutableStateOf<CropPhoto?>(null) }
    var detected by remember { mutableStateOf<PageOutline?>(null) }

    LaunchedEffect(pageId) {
        val p = repo.getPage(pageId) ?: return@LaunchedEffect onDone()
        page = p
        photo = withContext(Dispatchers.Default) {
            val file = repo.cropSource(p)
            val (w, h) = BitmapUtils.imageSize(file)
            CropPhoto(BitmapUtils.scaleDown(BitmapUtils.decode(file), 1600), w, h, if (file.path == p.sourcePath) p.outline else null)
        }
        // Detection on the screen-size copy, scaled up to the photo, for the Auto button.
        val shown = photo ?: return@LaunchedEffect
        detected = withContext(Dispatchers.Default) {
            val f = shown.width.toFloat() / shown.preview.width
            DocumentDetector.detectOutline(shown.preview)?.scaled(f)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val p = page
        val shown = photo
        if (p == null || shown == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            CropReview(
                preview = shown.preview,
                fullWidth = shown.width,
                fullHeight = shown.height,
                detected = detected,
                initial = shown.corners,
                onRetake = onDone,
                onConfirm = { outline ->
                    busy.run(S.str(R.string.cropping)) {
                        repo.recropPage(p, outline)
                        onDone()
                    }
                },
                cancelLabel = S.str(R.string.cancel),
                cancelIcon = Icons.Filled.Close,
                message = if (p.markup && repo.cropSource(p).path == p.sourcePath) {
                    S.str(R.string.this_starts_from_the_original_photo_text_sig)
                } else {
                    S.str(R.string.drag_the_corners_or_sides_to_line_the_crop_u)
                },
            )
        }
        BusyOverlay(busy)
    }
}

private class CropPhoto(val preview: Bitmap, val width: Int, val height: Int, val corners: PageOutline?)
