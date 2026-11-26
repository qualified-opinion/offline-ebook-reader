package com.jan.offlinereader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Each book's main colour, for spines and the colour sort. Taken from a tiny copy of the
 * cover, so it is cheap and stays on the device. Books without a cover get a binding colour.
 */
internal object BookColours {
    // Imported covers never change, so a book's colour can be kept for the app's lifetime.
    private val known = ConcurrentHashMap<String, Int>()

    /** The colour if already worked out, else the book's binding colour until [load] finishes. */
    fun now(book: Book): Int = known[book.id] ?: bindingColour(book.id)

    suspend fun load(book: Book): Int {
        known[book.id]?.let { return it }
        val colour = book.cover?.let { coverColour(File(book.directory, it)) } ?: bindingColour(book.id)
        known[book.id] = colour
        return colour
    }
}

internal suspend fun coverColour(file: File): Int? = withContext(Dispatchers.IO) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, 32, 48)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@withContext null
        val small = if (decoded.width * decoded.height > 4096) Bitmap.createScaledBitmap(decoded, 32, 48, true) else decoded
        try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            dominantColour(pixels)
        } finally {
            if (small !== decoded) small.recycle()
            decoded.recycle()
        }
    } catch (e: CancellationException) { throw e }
    catch (_: OutOfMemoryError) { null }
    catch (_: Exception) { null }
}
