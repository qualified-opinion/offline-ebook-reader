package com.jan.offlinereader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import java.io.File

@Composable
internal fun LocalImage(file: File, modifier: Modifier, description: String?, cache: CoverCache,
    scale: ContentScale = ContentScale.Fit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val height = with(density) { maxHeight.roundToPx().coerceAtLeast(1) }
        key(file.absolutePath, width, height, cache) {
            // A return visit can display the book-scoped cache hit on its first frame.
            val bitmap by produceState<Bitmap?>(cache.cached(file, width, height), file.absolutePath, width, height, cache) {
                try { value = cache.load(file, width, height) }
                catch (e: CancellationException) { throw e }
                catch (_: OutOfMemoryError) { value = null }
                catch (_: Exception) { value = null }
            }
            bitmap?.let {
                Image(it.asImageBitmap(), description, Modifier.fillMaxWidth().heightIn(max = maxHeight), contentScale = scale)
            }
        }
    }
}
