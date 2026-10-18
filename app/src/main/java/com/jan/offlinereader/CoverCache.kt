package com.jan.offlinereader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

internal fun coverSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    require(width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0)
    val fit = minOf(1.0, targetWidth.toDouble() / width, targetHeight.toDouble() / height)
    val fittedWidth = ceil(width * fit).toInt().coerceAtLeast(1)
    val fittedHeight = ceil(height * fit).toInt().coerceAtLeast(1)
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2 &&
        width / (sample * 2) >= fittedWidth && height / (sample * 2) >= fittedHeight) sample *= 2
    return sample
}

internal class CoverCache(maxBytes: Int = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(1)) {
    // Imported cover files are immutable and live in a unique per-book directory.
    private fun key(file: File, width: Int, height: Int) = "${file.absolutePath}|$width|$height"
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Do not recycle on eviction: Compose may still be drawing that bitmap.
    }
    private val mutex = Mutex()

    fun cached(file: File, width: Int, height: Int): Bitmap? = cache.get(key(file, width, height))

    suspend fun load(file: File, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached(file, width, height)?.let { return@withLock it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
            val options = BitmapFactory.Options().apply {
                inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, width, height)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.also {
                // An unusually large cover should not evict the entire useful cache.
                if (it.allocationByteCount <= cache.maxSize()) cache.put(key(file, width, height), it)
            }
        }
    }
}
