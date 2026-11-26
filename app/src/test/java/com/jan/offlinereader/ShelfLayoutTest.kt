package com.jan.offlinereader

import org.junit.Assert.*
import org.junit.Test

class ShelfLayoutTest {
    private fun book(id: String, title: String, author: String = "", lastRead: Long = 0, progress: Float = 0f, units: Int = 100) =
        Book(id = id, title = title, author = author, format = "EPUB", directory = "/books/$id", sha256 = id.padEnd(64, '0'),
            units = units, lastRead = lastRead, progress = progress)

    // Database order: most recently read first, then newest imports.
    private val odyssey = book("o", "The Odyssey", "Homer", lastRead = 30, progress = .67f)
    private val pride = book("p", "Pride and Prejudice", "Jane Austen", lastRead = 20, progress = .42f)
    private val dracula = book("d", "Dracula", "Bram Stoker", lastRead = 10, progress = 1f)
    private val emma = book("e", "Emma", "Jane Austen")
    private val anon = book("a", "A Book of Verse")
    private val library = listOf(odyssey, pride, dracula, emma, anon)
    private val noColour: (Book) -> Int = { 0 }

    @Test fun recentKeepsTheDatabaseOrderOnOneRun() {
        val groups = arrangeShelves(library, ShelfSort.Recent, noColour)
        assertEquals(listOf(ShelfGroup(null, library)), groups)
    }

    @Test fun statusGroupsReadingThenUpNextThenFinished() {
        val groups = arrangeShelves(library, ShelfSort.Status, noColour)
        assertEquals(listOf("Reading now", "Up next", "Finished"), groups.map { it.label })
        assertEquals(listOf(odyssey, pride), groups[0].books)
        assertEquals(listOf(emma, anon), groups[1].books)
        assertEquals(listOf(dracula), groups[2].books)
    }

    @Test fun statusLeavesOutEmptyShelves() {
        val groups = arrangeShelves(listOf(emma), ShelfSort.Status, noColour)
        assertEquals(listOf("Up next"), groups.map { it.label })
    }

    @Test fun neverOpenedBookIsUpNextWhateverItsProgress() {
        assertEquals(ReadingState.NotStarted, readingState(emma.copy(progress = .5f)))
        assertEquals(ReadingState.Reading, readingState(emma.copy(lastRead = 1)))
        assertEquals(ReadingState.Finished, readingState(emma.copy(lastRead = 1, progress = .995f)))
    }

    @Test fun authorSortsBySurnameWithUnknownAuthorsLast() {
        val books = arrangeShelves(library, ShelfSort.Author, noColour).single().books
        assertEquals(listOf("Emma", "Pride and Prejudice", "The Odyssey", "Dracula", "A Book of Verse"), books.map { it.title })
    }

    @Test fun titleSortIgnoresLeadingArticles() {
        val books = arrangeShelves(library, ShelfSort.Title, noColour).single().books
        assertEquals(listOf("A Book of Verse", "Dracula", "Emma", "The Odyssey", "Pride and Prejudice"), books.map { it.title })
        assertEquals("odyssey", titleSortKey("  The Odyssey"))
        assertEquals("theatre", titleSortKey("Theatre"))
    }

    @Test fun colourSortRunsLikeARainbowWithGreysLast() {
        val colours = mapOf("o" to 0xFF1F4E6B.toInt(), "p" to 0xFF8B1E1E.toInt(), "d" to 0xFF2B2B2B.toInt(),
            "e" to 0xFFC9A24A.toInt(), "a" to 0xFFEEEEEE.toInt())
        val books = arrangeShelves(library, ShelfSort.Colour) { colours.getValue(it.id) }.single().books
        // red, yellow, blue, then light grey before charcoal
        assertEquals(listOf("p", "e", "o", "a", "d"), books.map { it.id })
    }

    @Test fun dominantColourPicksTheStrongHueOverBackground() {
        val cream = 0xFFF4EDDD.toInt()
        val red = 0xFF8B1E1E.toInt()
        val pixels = IntArray(100) { if (it < 30) red else cream }
        assertEquals(red, dominantColour(pixels))
    }

    @Test fun dominantColourOfAGreyCoverIsItsAverage() {
        val pixels = IntArray(10) { if (it % 2 == 0) 0xFF000000.toInt() else 0xFF646464.toInt() }
        assertEquals(0xFF323232.toInt(), dominantColour(pixels))
        assertNull(dominantColour(IntArray(4)))  // fully transparent
        assertNull(dominantColour(IntArray(0)))
    }

    @Test fun bindingColourIsStablePerBook() {
        assertEquals(bindingColour("abc"), bindingColour("abc"))
        assertTrue(bindingColour("abc") in bindingColours)
    }

    @Test fun longerBooksHaveThickerSpinesWithinLimits() {
        assertEquals(18f, spineThickness(0), 0f)
        assertEquals(18f, spineThickness(20), .01f)
        assertEquals(40f, spineThickness(2000), .01f)
        assertEquals(40f, spineThickness(1_000_000), 0f)
        assertTrue(spineThickness(300) > spineThickness(100))
    }

    @Test fun spineHeightsVaryWithinTheShelf() {
        listOf("a", "b", "book-1", "zzzz", "").forEach { assertTrue(spineHeightShare(it) in .7399f..0.9401f) }
    }

    @Test fun shelvesFillLeftToRight() {
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), packShelves(listOf(40f, 50f, 30f, 200f), 100f, 2f))
        assertEquals(listOf(listOf(0, 1)), packShelves(listOf(49f, 49f), 100f, 2f))
        assertEquals(listOf(listOf(0)), packShelves(listOf(300f), 100f, 2f))
        assertEquals(emptyList<List<Int>>(), packShelves(emptyList(), 100f, 2f))
    }

    @Test fun phonesGetThreeCoversPerShelfAndTabletsMore() {
        assertEquals(3, coversPerShelf(328f))
        assertEquals(3, coversPerShelf(200f))
        assertEquals(6, coversPerShelf(700f))
    }

    @Test fun inkContrastsWithTheBinding() {
        assertTrue(luminance(0xFFFFFFFF.toInt()) > .99f)
        assertTrue(luminance(0xFF000000.toInt()) < .01f)
    }
}
