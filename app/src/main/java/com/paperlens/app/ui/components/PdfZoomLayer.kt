package com.paperlens.app.ui.components

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.paperlens.app.export.PdfPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * For a page imported from a PDF: once zooming settles, the visible part of the page is drawn again
 * from the PDF at screen resolution, so text stays crisp at any zoom instead of showing the page
 * picture's pixels. Goes inside the zoomed (`graphicsLayer`) box, above the picture and below any
 * highlights; [box] is that box's size and [image] the page picture's fitted size in it, both before
 * zooming. While panning, the last render moves with the page; uncovered parts show the picture.
 */
@Composable
fun PdfZoomLayer(pdf: File, zoom: PanZoom, image: Size?, box: Size) {
    var shown by remember(pdf) { mutableStateOf<Pair<Bitmap, RectF>?>(null) }
    LaunchedEffect(pdf, image, box) {
        val img = image ?: return@LaunchedEffect
        snapshotFlow { zoom.scale to zoom.offset }.collectLatest { (s, o) ->
            if (s <= 1.05f) {
                shown = null
                return@collectLatest
            }
            delay(150)
            // The part of the box on screen, in unzoomed box coordinates: p = c + (q - c - o) / s.
            val cx = box.width / 2
            val cy = box.height / 2
            val left = (box.width - img.width) / 2
            val top = (box.height - img.height) / 2
            val vis = RectF(
                max(left, cx + (-cx - o.x) / s),
                max(top, cy + (-cy - o.y) / s),
                min(left + img.width, cx + (box.width - cx - o.x) / s),
                min(top + img.height, cy + (box.height - cy - o.y) / s),
            )
            if (vis.width() <= 1f || vis.height() <= 1f) return@collectLatest
            val region = RectF((vis.left - left) / img.width, (vis.top - top) / img.height, (vis.right - left) / img.width, (vis.bottom - top) / img.height)
            val bitmap = withContext(Dispatchers.IO) {
                PdfPages.render(pdf, region, (vis.width() * s).roundToInt().coerceAtLeast(1), (vis.height() * s).roundToInt().coerceAtLeast(1))
            } ?: return@collectLatest
            shown = bitmap to vis
        }
    }
    val (bitmap, at) = shown ?: return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(at.left.roundToInt(), at.top.roundToInt()) }
            .size(with(density) { at.width().toDp() }, with(density) { at.height().toDp() }),
    ) {
        Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
    }
}
