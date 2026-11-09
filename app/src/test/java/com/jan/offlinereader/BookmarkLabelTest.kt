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

    @Test fun sliderTargetCoversFirstAndLastPassage() {
        assertEquals(0, sliderTarget(0f, 300))
        assertEquals(299, sliderTarget(1f, 300))
        assertEquals(150, sliderTarget(.5f, 301))
        assertEquals(0, sliderTarget(.7f, 1))
        assertEquals(0, sliderTarget(-1f, 10))
        assertEquals(9, sliderTarget(2f, 10))
    }

    @Test fun sliderPreviewShowsPageForPdfAndChapterForText() {
        assertEquals("Page 1 of 30", sliderPreview("PDF", 0f, 30, emptyList()))
        assertEquals("Page 30 of 30", sliderPreview("PDF", 1f, 30, chapters))
        assertEquals("50% · Chapter 2", sliderPreview("EPUB", .5f, 31, chapters))
        assertEquals("0%", sliderPreview("EPUB", 0f, 31, chapters))
        assertEquals("42%", sliderPreview("TXT", .42f, 10, emptyList()))
    }

    @Test fun currentChapterIndexFindsTheChapterYouAreIn() {
        assertEquals(-1, currentChapterIndex(chapters, 0))
        assertEquals(-1, currentChapterIndex(emptyList(), 5))
        assertEquals(0, currentChapterIndex(chapters, chapters[0].position))
        assertEquals(chapters.lastIndex, currentChapterIndex(chapters, 10_000))
    }

    @Test fun readingLineShowsChapterAndPercentOrPdfPage() {
        assertEquals("Page 12 of 300", readingLine("PDF", 11, 300, .04f, chapters))
        assertEquals("42% read", readingLine("TXT", 4, 10, .42f, emptyList()))
        assertEquals("Chapter 2 · 50% read", readingLine("EPUB", 15, 31, .5f, chapters))
        assertEquals("0% read", readingLine("EPUB", 0, 31, 0f, chapters))
    }
}
