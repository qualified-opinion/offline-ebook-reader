package com.jan.offlinereader

/** Small, stable text units keep lazy rendering and bookmarks independent of pagination. */
object TextChunks {
    const val DEFAULT_LIMIT = 700
    private const val SENTENCE_ENDS = ".!?…。！？"
    private const val CLOSERS = "\"'”’»)]"

    /** Pieces join back to [text] exactly; later pieces start after whitespace, never with it. */
    fun split(text: String, limit: Int = DEFAULT_LIMIT): List<String> {
        require(limit > 1)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                end = breakPoint(text, start, end, start + limit / 2)
                // Do not cut a UTF-16 surrogate pair in half.
                if (end > start + 1 && Character.isHighSurrogate(text[end - 1])) end--
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    /** Prefer a sentence end, then any whitespace, in the second half of the piece; keep the whitespace. */
    private fun breakPoint(text: String, start: Int, end: Int, earliest: Int): Int {
        fun afterWhitespace(space: Int): Int {
            var next = space + 1
            while (next < end && text[next].isWhitespace()) next++
            return next
        }
        for (space in end - 1 downTo earliest + 1) {
            if (!text[space].isWhitespace()) continue
            var before = space - 1
            while (before > start && text[before] in CLOSERS) before--
            if (text[before] in SENTENCE_ENDS) return afterWhitespace(space)
        }
        for (space in end - 1 downTo earliest + 1) {
            if (text[space].isWhitespace()) return afterWhitespace(space)
        }
        return end
    }
}
/** [joined] marks a later piece of the same paragraph, shown without a paragraph gap. */
data class Block(val start: Long = 0, val length: Int = 0, val heading: Boolean = false,
    val image: String? = null, val imageWidth: Int = 0, val imageHeight: Int = 0, val joined: Boolean = false)

/** Unknown dimensions retain the legacy loading layout; known pictures reserve their final height. */
internal fun imageDisplayHeight(widthDp: Float, imageWidth: Int, imageHeight: Int): Float? {
    if (!widthDp.isFinite() || widthDp <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    return minOf(500.0, widthDp.toDouble() * imageHeight / imageWidth).toFloat()
}
data class ContentsEntry(val title: String, val position: Int)
data class TextDocument(val blocks: List<Block>, val contents: List<ContentsEntry>)
