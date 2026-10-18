package com.jan.offlinereader

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.ceil

internal fun textPreloadWindow(center: Int, count: Int): IntRange {
    if (count <= 0) return IntRange.EMPTY
    val anchor = center.coerceIn(0, count - 1)
    return (anchor - 10).coerceAtLeast(0)..minOf(count - 1L, anchor.toLong() + 10).toInt()
}

/** Estimates in sp; UTF-8 byte counts are bounded by the importer's passage length. */
internal fun textPlaceholderHeight(block: Block, widthSp: Float, fontSize: Float, spacing: Float): Float {
    val characters = block.length.coerceIn(1, TextChunks.DEFAULT_LIMIT)
    val glyphWidth = fontSize * (if (block.heading) 1.25f else 1f) * .5f
    val lines = ceil(characters * glyphWidth / widthSp.coerceAtLeast(glyphWidth)).coerceAtLeast(1f)
    return lines * fontSize * spacing
}

/** Scoped to one open book; 128 imported passages retain roughly 180 KB of UTF-16 text. */
internal class TextBlockCache(capacity: Int = 128) {
    private val text = LruCache<Int, String>(capacity)
    private val mutex = Mutex()
    fun cached(index: Int): String? = text.get(index)

    suspend fun load(index: Int, read: suspend () -> String): String = mutex.withLock {
        cached(index) ?: read().also { text.put(index, it) }
    }

    suspend fun preload(center: Int, blocks: List<Block>, read: suspend (Block) -> String) {
        if (blocks.isEmpty()) return
        val anchor = center.coerceIn(0, blocks.lastIndex)
        for (index in textPreloadWindow(anchor, blocks.size).sortedBy { abs(it - anchor) }) {
            val block = blocks[index]
            if (block.image != null) continue
            try { load(index) { read(block) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Visible passages report their own read errors. */ }
        }
    }
}
