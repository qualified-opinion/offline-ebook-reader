package com.jan.offlinereader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

@OptIn(FlowPreview::class)
@Composable
internal fun PdfZoom(source: PdfSource, index: Int, ratio: Float, theme: String, onDone: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF151715)).clipToBounds()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val height = with(density) { maxHeight.roundToPx().coerceAtLeast(1) }
        val pageWidth = minOf(width.toFloat(), height / ratio)
        val pageHeight = pageWidth * ratio
        var scale by remember(source, index, width, height) { mutableFloatStateOf(1f) }
        var pan by remember(source, index, width, height) { mutableStateOf(Offset.Zero) }
        var rendered by remember(source, index, width, height) { mutableStateOf<Pair<PdfZoomRequest, Bitmap>?>(null) }
        var failed by remember(source, index, width, height) { mutableStateOf(false) }
        val request by rememberUpdatedState(PdfZoomRequest(width, height, scale, pan.x, pan.y))
        LaunchedEffect(source, index, width, height) {
            snapshotFlow { request }.debounce(250).collectLatest { settled ->
                if (settled.zoom <= 1.2f) { rendered = null; failed = false; return@collectLatest }
                try {
                    val bitmap = source.renderViewport(index, settled)
                    if (request == settled) {
                        // Replace the only retained detail bitmap; Compose/GC releases the old one safely.
                        rendered = settled to bitmap
                        failed = false
                    } else bitmap.recycle() // Never displayed: safe to release immediately.
                } catch (e: CancellationException) { throw e }
                catch (_: OutOfMemoryError) { failed = true }
                catch (_: Exception) { failed = true }
            }
        }
        Box(Modifier.fillMaxSize().pointerInput(source, index, width, height) {
            detectTransformGestures { centroid, delta, zoom, _ ->
                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                val factor = nextScale / scale
                val anchor = centroid - Offset(width / 2f, height / 2f)
                val moved = pan * factor + anchor * (1 - factor) + delta
                val maxX = ((pageWidth * nextScale - width) / 2).coerceAtLeast(0f)
                val maxY = ((pageHeight * nextScale - height) / 2).coerceAtLeast(0f)
                pan = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                scale = nextScale
            }
        }, contentAlignment = Alignment.Center) {
            PdfPage(source, index, ratio, theme, Modifier
                .size(with(density) { pageWidth.toDp() }, with(density) { pageHeight.toDp() })
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y))
            // The low-resolution page stays responsive during gestures; stale detail is never shown.
            rendered?.takeIf { it.first == request }?.let { (_, bitmap) ->
                Image(bitmap.asImageBitmap(), "Zoomed page ${index + 1}", Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds, colorFilter = pdfColorFilter(theme))
            }
        }
        FilledTonalButton(onClick = onDone, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Done") }
        Text(if (failed) "Sharper view unavailable. Pinch to try again." else "Pinch to zoom · Drag to move",
            Modifier.align(Alignment.BottomCenter).padding(24.dp), color = Color.White)
    }
}
