package com.jan.offlinereader

import kotlin.math.roundToInt

/** The chapter that contains [position], or null before the first chapter or for a blank title. */
internal fun chapterAt(contents: List<ContentsEntry>, position: Int): String? =
    contents.lastOrNull { it.position <= position }?.title?.takeIf { it.isNotBlank() }

/** Index in [contents] of the chapter that contains [position], or -1 before the first chapter. */
internal fun currentChapterIndex(contents: List<ContentsEntry>, position: Int): Int =
    contents.indexOfLast { it.position <= position }

/** The line under the page: "Chapter 3 · 42% read", "42% read" or, for PDFs, "Page 12 of 300". */
internal fun readingLine(format: String, position: Int, units: Int, progress: Float,
    contents: List<ContentsEntry>): String {
    if (format == "PDF") return "Page ${position + 1} of $units"
    val percent = "${(progress.coerceIn(0f, 1f) * 100).toInt()}% read"
    return chapterAt(contents, position)?.let { "$it · $percent" } ?: percent
}

internal fun bookmarkLabel(format: String?, position: Int, progress: Float,
    contents: List<ContentsEntry>): String {
    if (format == "PDF") return "Page ${position + 1}"
    val chapter = chapterAt(contents, position) ?: "Reading spot"
    return "$chapter · ${(progress * 100).toInt()}%"
}

/** The page or passage a progress slider value points at. */
internal fun sliderTarget(value: Float, units: Int): Int =
    (value.coerceIn(0f, 1f) * (units - 1)).roundToInt().coerceIn(0, (units - 1).coerceAtLeast(0))

/** Shown above the slider while dragging, so readers see where they will land. */
internal fun sliderPreview(format: String, value: Float, units: Int, contents: List<ContentsEntry>): String {
    val target = sliderTarget(value, units)
    if (format == "PDF") return "Page ${target + 1} of $units"
    val percent = "${(value.coerceIn(0f, 1f) * 100).toInt()}%"
    return chapterAt(contents, target)?.let { "$percent · $it" } ?: percent
}
