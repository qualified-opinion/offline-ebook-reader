package com.jan.offlinereader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStatusTest {
    private val book = Book(id = "b", title = "Quiet", format = "EPUB", directory = "/books/b", sha256 = "0".repeat(64), units = 100)

    @Test fun unopenedBookIsNotStarted() {
        assertEquals("Not started", readingStatus(book))
        assertEquals("Not started", readingStatus(book.copy(progress = .5f)))
    }

    @Test fun progressShowsWholePercentUntilTheEnd() {
        assertEquals("0% read", readingStatus(book.copy(lastRead = 1)))
        assertEquals("42% read", readingStatus(book.copy(lastRead = 1, progress = .42f)))
        assertEquals("99% read", readingStatus(book.copy(lastRead = 1, progress = .99f)))
    }

    @Test fun endOfBookIsFinished() {
        assertEquals("Finished", readingStatus(book.copy(lastRead = 1, progress = .995f)))
        assertEquals("Finished", readingStatus(book.copy(lastRead = 1, progress = 1f)))
    }
}
