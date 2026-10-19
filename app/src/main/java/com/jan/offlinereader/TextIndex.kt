package com.jan.offlinereader

/** Small, stable text units keep lazy rendering and bookmarks independent of pagination. */
object TextChunks {
    const val DEFAULT_LIMIT = 700
    fun split(text: String, limit: Int = DEFAULT_LIMIT): List<String> {
        require(limit > 1)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start + limit / 2) end = space
                // Do not cut a UTF-16 surrogate pair in half.
                if (end > start && Character.isHighSurrogate(text[end - 1])) end--
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}
data class Block(val start: Long = 0, val length: Int = 0, val heading: Boolean = false,
    val image: String? = null, val imageWidth: Int = 0, val imageHeight: Int = 0)

/** Unknown dimensions retain the legacy loading layout; known pictures reserve their final height. */
internal fun imageDisplayHeight(widthDp: Float, imageWidth: Int, imageHeight: Int): Float? {
    if (!widthDp.isFinite() || widthDp <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    return minOf(500.0, widthDp.toDouble() * imageHeight / imageWidth).toFloat()
}
data class ContentsEntry(val title: String, val position: Int)
data class TextDocument(val blocks: List<Block>, val contents: List<ContentsEntry>)
