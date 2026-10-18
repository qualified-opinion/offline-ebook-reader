package com.jan.offlinereader

import org.junit.Assert.*
import org.junit.Test

class TextIndexTest {
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
