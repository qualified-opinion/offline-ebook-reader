package com.jan.offlinereader

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSummaryTest {
    @Test fun newBooksUseCorrectCounts() {
        assertEquals("1 book added.", importSummary(1, 0, emptyList()))
        assertEquals("2 books added.", importSummary(2, 0, emptyList()))
    }
    @Test fun duplicateOnlyDoesNotClaimBooksWereAdded() {
        assertEquals("1 book was already in your library.", importSummary(0, 1, emptyList()))
        assertEquals("2 books were already in your library.", importSummary(0, 2, emptyList()))
    }
    @Test fun mixedBatchNamesFailuresAndReportsBothSuccessKinds() {
        val failure = "Couldn’t import “notes.docx”: Unsupported format."
        assertEquals("2 books added. 1 book was already in your library. $failure",
            importSummary(2, 1, listOf(failure)))
    }
    @Test fun multipleFailuresShowTwoNamesAndRemainingCount() {
        val failures = listOf("Couldn’t import “one.docx”: Unsupported format.",
            "Couldn’t import “two.epub”: Damaged file.", "three", "four")
        assertEquals("${failures[0]} ${failures[1]} And 2 more failed.", importSummary(0, 0, failures))
        assertEquals("${failures[0]} ${failures[1]}", importSummary(0, 0, failures.take(2)))
    }
    @Test fun emptySelectionHasNoSuccessClaim() {
        assertEquals("No books selected.", importSummary(0, 0, emptyList()))
    }
}
