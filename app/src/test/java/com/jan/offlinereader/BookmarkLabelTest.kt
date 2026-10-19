package com.jan.offlinereader

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkLabelTest {
    private val chapters = listOf(ContentsEntry("Chapter 1", 2), ContentsEntry("Chapter 2", 10),
        ContentsEntry("Chapter 3", 20))

    @Test fun picksChapterAtStartInsideAndAfterLastChapter() {
        assertEquals("Reading spot · 23%", bookmarkLabel("EPUB", 0, .23f, chapters))
        assertEquals("Chapter 1 · 23%", bookmarkLabel("EPUB", 2, .23f, chapters))
        assertEquals("Chapter 1 · 23%", bookmarkLabel("EPUB", 9, .23f, chapters))
        assertEquals("Chapter 2 · 23%", bookmarkLabel("EPUB", 10, .23f, chapters))
        assertEquals("Chapter 3 · 100%", bookmarkLabel("EPUB", 99, 1f, chapters))
    }

    @Test fun missingOrBlankChapterUsesExistingFallback() {
        assertEquals("Reading spot · 0%", bookmarkLabel("TXT", 0, 0f, emptyList()))
        val contents = chapters + ContentsEntry(" \t", 21)
        assertEquals("Reading spot · 23%", bookmarkLabel("EPUB", 22, .23f, contents))
    }

    @Test fun lastMatchingEntryWinsForNestedContentsAtSamePosition() {
        val contents = listOf(ContentsEntry("Part 1", 0), ContentsEntry("Chapter 1", 0),
            ContentsEntry("Chapter 2", 10))
        assertEquals("Chapter 1 · 5%", bookmarkLabel("EPUB", 1, .05f, contents))
    }

    @Test fun otherTextFormatsUseContentsAndPdfKeepsPageLabel() {
        listOf("TXT", "MD", "HTML", "HTM", "FB2").forEach { format ->
            assertEquals("Chapter 2 · 50%", bookmarkLabel(format, 15, .5f, chapters))
        }
        assertEquals("Page 16", bookmarkLabel("PDF", 15, .5f, chapters))
        assertEquals("Page 1", bookmarkLabel("PDF", 0, 0f, emptyList()))
    }
}
