package com.jan.offlinereader

internal fun bookmarkLabel(format: String?, position: Int, progress: Float,
    contents: List<ContentsEntry>): String {
    if (format == "PDF") return "Page ${position + 1}"
    val chapter = contents.lastOrNull { it.position <= position }?.title
        ?.takeIf { it.isNotBlank() } ?: "Reading spot"
    return "$chapter · ${(progress * 100).toInt()}%"
}
