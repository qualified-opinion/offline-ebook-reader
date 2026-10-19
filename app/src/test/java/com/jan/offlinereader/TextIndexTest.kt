package com.jan.offlinereader

import org.junit.Assert.*
import org.junit.Test

class TextIndexTest {
    @Test fun imageHeightFitsAspectRatioAndCapsTallPictures() {
        assertEquals(180f, imageDisplayHeight(360f, 800, 400)!!, 0f)
        assertEquals(360f, imageDisplayHeight(360f, 400, 400)!!, 0f)
        assertEquals(500f, imageDisplayHeight(360f, 400, 1200)!!, 0f)
        assertEquals(300f, imageDisplayHeight(600f, 800, 400)!!, 0f)
        assertEquals(500f, imageDisplayHeight(Float.MAX_VALUE, 1, Int.MAX_VALUE)!!, 0f)
    }

    @Test fun missingOrInvalidImageDimensionsUseLegacyLayout() {
        assertNull(imageDisplayHeight(360f, 0, 0))
        assertNull(imageDisplayHeight(360f, -1, 400))
        assertNull(imageDisplayHeight(360f, 800, 0))
        assertNull(imageDisplayHeight(0f, 800, 400))
        assertNull(imageDisplayHeight(Float.NaN, 800, 400))
    }
    @Test fun chunksPreserveEveryCharacterOfLongBooks() {
        val text = ("A calm page.  Spaces,\nnew lines and punctuation! " + "x".repeat(1500)).repeat(1000)
        val chunks = TextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 700 && it.isNotEmpty() })
    }
    @Test fun chunksNeverSplitEmojiSurrogatePairs() {
        val text = "abc😀def😃".repeat(1000)
        val chunks = TextChunks.split(text, 7)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
    @Test fun emptyTextHasNoChunks() { assertTrue(TextChunks.split("").isEmpty()) }
    @Test fun resolvesNestedEpubPathsAndEscapedNames() {
        assertEquals("OPS/images/cover one.jpg", resolve("OPS/text/chapter.xhtml", "../images/cover%20one.jpg"))
        assertEquals("OPS/text/chapter.xhtml", resolve("OPS/text/chapter.xhtml", "#section"))
        assertEquals("OPS/text/next.xhtml", resolve("OPS/package.opf", "text/next.xhtml#intro"))
    }
    @Test fun externalResourcesAreRejected() {
        listOf("https://example.com/chapter", "//example.com/image.png", "file:///etc/passwd").forEach {
            try { resolve("OPS/package.opf", it); fail("External path accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
