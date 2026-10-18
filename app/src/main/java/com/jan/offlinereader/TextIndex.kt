package com.jan.offlinereader

/** Small, stable text units keep lazy rendering and bookmarks independent of pagination. */
object TextChunks {
    fun split(text: String, limit: Int = 700): List<String> {
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
    val image: String? = null)
data class ContentsEntry(val title: String, val position: Int)
data class TextDocument(val blocks: List<Block>, val contents: List<ContentsEntry>)
