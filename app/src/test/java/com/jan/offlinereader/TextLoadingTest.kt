package com.jan.offlinereader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReaderApplication::class)
class TextLoadingTest {
    @Test fun preloadIncludesBothSidesAndRespectsBookBoundaries() {
        assertEquals((40..60).toList(), textPreloadWindow(50, 100).toList())
        assertEquals((0..10).toList(), textPreloadWindow(0, 100).toList())
        assertEquals((89..99).toList(), textPreloadWindow(99, 100).toList())
        assertEquals((0..2).toList(), textPreloadWindow(-50, 3).toList())
        assertEquals((89..99).toList(), textPreloadWindow(Int.MAX_VALUE, 100).toList())
        assertTrue(textPreloadWindow(0, 0).isEmpty())
    }

    @Test fun placeholderRespondsToLengthWidthFontAndSpacing() {
        val short = Block(length = 20)
        val long = Block(length = 600)
        val baseline = textPlaceholderHeight(long, 300f, 20f, 1.6f)
        assertTrue(baseline > textPlaceholderHeight(short, 300f, 20f, 1.6f))
        assertTrue(textPlaceholderHeight(long, 150f, 20f, 1.6f) > baseline)
        assertTrue(textPlaceholderHeight(long, 300f, 30f, 1.6f) > baseline)
        assertEquals(baseline * 1.25f, textPlaceholderHeight(long, 300f, 20f, 2f), .001f)
        assertTrue(textPlaceholderHeight(long.copy(heading = true), 300f, 20f, 1.6f) > baseline)
    }

    @Test fun unicodeByteCountsAndEmptyPassagesHaveBoundedPlaceholders() {
        assertEquals(32f, textPlaceholderHeight(Block(length = 0), 300f, 20f, 1.6f), .001f)
        assertEquals(textPlaceholderHeight(Block(length = TextChunks.DEFAULT_LIMIT), 300f, 20f, 1.6f),
            textPlaceholderHeight(Block(length = 2800), 300f, 20f, 1.6f), .001f)
        assertTrue(textPlaceholderHeight(Block(length = 700), 0f, 20f, 1.6f).isFinite())
    }

    @Test fun preloadReadsNearestTextFirstAndSkipsImages() = runBlocking {
        val blocks = List(40) { Block(start = it.toLong(), length = 100, image = if (it == 15) "picture.png" else null) }
        val cache = TextBlockCache()
        val reads = mutableListOf<Int>()
        cache.preload(20, blocks) { reads += it.start.toInt(); "Text ${it.start}" }
        assertEquals(20, reads.first())
        assertEquals((10..30).filter { it != 15 }.toSet(), reads.toSet())
        assertNull(cache.cached(15))
        for (index in reads) assertEquals("Text $index", cache.cached(index))
    }

    @Test fun repeatedAndConcurrentRequestsReadOnlyOnce() = runBlocking {
        val cache = TextBlockCache()
        var reads = 0
        val results = listOf(async { cache.load(7) { reads++; delay(20); "Passage" } },
            async { cache.load(7) { reads++; "Duplicate read" } }).awaitAll()
        assertEquals(listOf("Passage", "Passage"), results)
        assertEquals("Passage", cache.load(7) { error("Should be cached") })
        assertEquals(1, reads)
    }

    @Test fun cacheEvictsOldEntriesAndBooksHaveSeparateCaches() = runBlocking {
        val cache = TextBlockCache(2)
        cache.load(0) { "Zero" }; cache.load(1) { "One" }
        assertEquals("Zero", cache.cached(0))
        cache.load(2) { "Two" }
        assertNull(cache.cached(1))
        assertEquals("Zero", cache.cached(0))
        val otherBook = TextBlockCache()
        assertNull(otherBook.cached(0))
        assertEquals("Other book", otherBook.load(0) { "Other book" })
    }

    @Test fun failedNeighborDoesNotBlockOtherPassagesAndCanBeRetried() = runBlocking {
        val cache = TextBlockCache()
        val blocks = List(30) { Block(start = it.toLong(), length = 100) }
        cache.preload(15, blocks) {
            if (it.start == 14L) throw java.io.IOException("Unavailable passage")
            "Readable"
        }
        assertEquals("Readable", cache.cached(15))
        assertEquals("Readable", cache.cached(16))
        assertNull(cache.cached(14))
        assertEquals("Recovered", cache.load(14) { "Recovered" })
    }

    @Test fun cancelledPreloadStopsAndDoesNotCachePartialText() = runBlocking {
        val cache = TextBlockCache()
        try {
            cache.preload(0, listOf(Block(length = 100))) { throw CancellationException("Book closed") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertNull(cache.cached(0))
    }
}
