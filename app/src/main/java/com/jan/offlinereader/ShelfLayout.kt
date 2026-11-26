package com.jan.offlinereader

import kotlin.math.abs
import kotlin.math.ln

/** How the library draws books: face-out covers on wooden shelves, or spines side by side. */
enum class ShelfStyle { Covers, Spines }

/** How books are ordered on the shelves. */
enum class ShelfSort(val label: String) {
    Recent("Recently read"), Status("Reading status"), Author("Author"), Title("Title"), Colour("Colour")
}

internal enum class ReadingState { Reading, NotStarted, Finished }

/** Same rules as [readingStatus]: a book never opened is "not started" whatever its progress. */
internal fun readingState(book: Book): ReadingState = when {
    book.lastRead == 0L -> ReadingState.NotStarted
    book.progress >= .995f -> ReadingState.Finished
    else -> ReadingState.Reading
}

/** One labelled run of shelves. A null label means the books are one continuous run. */
internal data class ShelfGroup(val label: String?, val books: List<Book>)

private val leadingArticle = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE)
/** "The Odyssey" files under O, as on a real shelf. */
internal fun titleSortKey(title: String) = title.trim().replace(leadingArticle, "").lowercase()
/** "Jane Austen" files under Austen. Books without an author go last. */
internal fun authorSortKey(author: String) =
    author.trim().split(Regex("\\s+")).lastOrNull()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "￿"

/**
 * Puts the books on shelves. [books] arrive most recently read first (the database order).
 * [colour] gives each book's spine or cover colour as ARGB, used by [ShelfSort.Colour].
 */
internal fun arrangeShelves(books: List<Book>, sort: ShelfSort, colour: (Book) -> Int): List<ShelfGroup> = when (sort) {
    ShelfSort.Recent -> listOf(ShelfGroup(null, books))
    ShelfSort.Status -> listOf(
        "Reading now" to ReadingState.Reading, "Up next" to ReadingState.NotStarted, "Finished" to ReadingState.Finished
    ).map { (label, state) -> ShelfGroup(label, books.filter { readingState(it) == state }) }.filter { it.books.isNotEmpty() }
    ShelfSort.Author -> listOf(ShelfGroup(null, books.sortedWith(
        compareBy<Book>({ authorSortKey(it.author) }, { it.author.lowercase() }, { titleSortKey(it.title) }))))
    ShelfSort.Title -> listOf(ShelfGroup(null, books.sortedBy { titleSortKey(it.title) }))
    ShelfSort.Colour -> listOf(ShelfGroup(null, books.sortedBy { colourSortKey(colour(it)) }))
}

/** Hue (0..360), saturation and value (0..1) of an ARGB colour. */
internal fun hsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
}

/** Rainbow order starting at red; greys, blacks and creams go after all the colours, light to dark. */
internal fun colourSortKey(argb: Int): Float {
    val (h, s, v) = hsv(argb)
    return if (s < .22f || v < .16f) 1000f + (1f - v) else (h + 15f) % 360f
}

/** Relative brightness, 0 (black) to 1 (white). */
internal fun luminance(argb: Int): Float {
    val (r, g, b) = listOf(16, 8, 0).map { (argb shr it and 0xFF) / 255f }
    return .2126f * r + .7152f * g + .0722f * b
}

/** Calm cloth-binding colours for books without a cover image. */
internal val bindingColours = listOf(
    0xFF7A2E3A, 0xFFB5652B, 0xFF1F4E6B, 0xFF8B1E1E, 0xFF4A3B6B, 0xFF2F6F7A,
    0xFF6B7A3A, 0xFF3F6B4F, 0xFFC9A24A, 0xFF5A4632, 0xFF9C3B5A, 0xFF3B4A5C
).map { it.toInt() }

/** A stable binding colour for a book: the same book always gets the same colour. */
internal fun bindingColour(bookId: String): Int = bindingColours[abs(bookId.hashCode() % bindingColours.size)]

/**
 * The main colour of a cover, from its pixels (ARGB). Picks the most common strong hue;
 * a mostly grey, black or white cover gets its average colour. Null when there are no pixels.
 */
internal fun dominantColour(pixels: IntArray): Int? {
    val opaque = pixels.filter { it ushr 24 >= 0x80 }
    if (opaque.isEmpty()) return null
    val buckets = Array(12) { mutableListOf<Int>() }
    for (p in opaque) {
        val (h, s, v) = hsv(p)
        if (s >= .3f && v in .2f..0.97f) buckets[(h / 30f).toInt().coerceIn(0, 11)] += p
    }
    val best = buckets.maxBy { it.size }
    return average(if (best.size * 6 >= opaque.size) best else opaque)
}

private fun average(pixels: List<Int>): Int {
    var r = 0L; var g = 0L; var b = 0L
    for (p in pixels) { r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF }
    val n = pixels.size
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

/**
 * Spine thickness in dp. Longer books are thicker, on a log scale so a 2,000-page PDF
 * doesn't push a short story off the shelf. [units] is the book's passage or page count.
 */
internal fun spineThickness(units: Int): Float {
    val t = ((ln(units.coerceAtLeast(1).toFloat()) - ln(20f)) / (ln(2000f) - ln(20f))).coerceIn(0f, 1f)
    return 18f + 22f * t
}

/** Spine height as a share of the shelf: varied per book, but stable between visits. */
internal fun spineHeightShare(bookId: String): Float = .74f + (abs(bookId.hashCode() / 7 % 21)) / 100f

/**
 * Fills shelves left to right. Returns the indexes of [widths] on each shelf; every shelf holds
 * at least one book, even one wider than [available].
 */
internal fun packShelves(widths: List<Float>, available: Float, gap: Float): List<List<Int>> {
    val shelves = mutableListOf<MutableList<Int>>()
    var used = 0f
    widths.forEachIndexed { i, w ->
        val shelf = shelves.lastOrNull()
        if (shelf == null || used + gap + w > available) { shelves += mutableListOf(i); used = w }
        else { shelf += i; used += gap + w }
    }
    return shelves
}

/** How many covers stand on one shelf: at least three, more on wide screens. */
internal fun coversPerShelf(availableDp: Float, minCoverDp: Float = 96f, gapDp: Float = 16f): Int =
    maxOf(3, ((availableDp + gapDp) / (minCoverDp + gapDp)).toInt())
