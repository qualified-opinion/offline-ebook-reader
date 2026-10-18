package com.jan.offlinereader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import java.io.File

private val libraryCovers = CoverCache()

@Composable
internal fun CoverImage(file: File, modifier: Modifier, description: String) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val height = with(density) { maxHeight.roundToPx().coerceAtLeast(1) }
        key(file.absolutePath, width, height) {
            // A memory hit is available on the first frame when a card returns.
            val bitmap by produceState<Bitmap?>(libraryCovers.cached(file, width, height), file.absolutePath, width, height) {
                try { value = libraryCovers.load(file, width, height) }
                catch (e: CancellationException) { throw e }
                catch (_: OutOfMemoryError) { value = null }
                catch (_: Exception) { value = null }
            }
            bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                ?: Icon(Icons.AutoMirrored.Filled.MenuBook, description)
        }
    }
}
