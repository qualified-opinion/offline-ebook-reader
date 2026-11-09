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
    @Test fun longParagraphsBreakAtSentenceEndsWithoutLeadingSpaces() {
        val text = "This is a calm sentence about reading, with a few more words. ".repeat(40) + "Last words here"
        val chunks = TextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 700 && it.isNotEmpty() })
        assertTrue(chunks.drop(1).none { it.first().isWhitespace() })
        assertTrue(chunks.dropLast(1).all { it.trimEnd().endsWith(".") })
    }
    @Test fun sentenceEndsInsideQuotesArePreferredOverLaterSpaces() {
        val text = "a".repeat(400) + " “Is it far?” " + "word ".repeat(100)
        val chunks = TextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.first().endsWith("far?” "))
    }
    @Test fun withoutSentenceEndsPiecesStillStartAfterTheSpace() {
        val text = "word  ".repeat(300)
        val chunks = TextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 700 })
        assertTrue(chunks.drop(1).none { it.first().isWhitespace() })
    }
    private fun paragraphs(vararg reads: String, max: Int = 64 * 1024): List<String> {
        val out = mutableListOf<String>()
        PlainTextParagraphs(max) { out += it }.apply { reads.forEach { add(it) }; finish() }
        return out
    }
    @Test fun blankLinesSeparateParagraphsAndWindowsLineEndsAreHandled() {
        assertEquals(listOf("One.", "Two.", "Three."), paragraphs("One.\r\n\r\nTwo.\n \n\n\nThree.\n"))
    }
    @Test fun wordsAndLinesSplitAcrossReadsStayWhole() {
        assertEquals(listOf("Hello world", "Next"), paragraphs("Hel", "lo wo", "rld\n", "\nNe", "xt"))
        assertEquals(listOf("abc😀def"), paragraphs("abc\uD83D", "\uDE00def"))
    }
    @Test fun wrappedProseIsJoinedButPoetryKeepsItsLines() {
        val prose = listOf("It was a quiet evening, and the lamps along the river were lit one",
            "by one while the boats came slowly home through the falling mist, and",
            "the town went still.")
        assertEquals(prose.joinToString(" "), joinParagraphLines(prose))
        val poem = listOf("The river moves", "  and the lamps go out,", "one by one.")
        assertEquals(poem.joinToString("\n"), joinParagraphLines(poem))
        assertEquals("Single line", joinParagraphLines(listOf("Single line  ")))
    }
    @Test fun hugeParagraphsAndLinesStayWithinTheMemoryLimit() {
        val longLine = "word ".repeat(1000)
        val out = paragraphs(longLine, max = 1000)
        assertTrue(out.size > 1)
        assertTrue(out.all { it.length <= 1001 })
        assertEquals(longLine.trimEnd(), out.joinToString(" ") { it.trim() })
        val manyLines = paragraphs("short line\n".repeat(500), max = 1000)
        assertTrue(manyLines.size > 1)
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
