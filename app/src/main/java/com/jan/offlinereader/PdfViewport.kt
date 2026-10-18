package com.jan.offlinereader

import kotlin.math.sqrt

internal data class PdfZoomRequest(
    val width: Int, val height: Int, val zoom: Float, val panX: Float, val panY: Float
)
internal data class PdfViewport(
    val width: Int, val height: Int, val scaleX: Float, val scaleY: Float,
    val translateX: Float, val translateY: Float
)

/** Maps PDF points to a bounded bitmap of the visible window, regardless of zoom. */
internal fun pdfViewport(pageWidth: Int, pageHeight: Int, request: PdfZoomRequest): PdfViewport {
    require(pageWidth > 0 && pageHeight > 0 && request.width > 0 && request.height > 0)
    require(request.zoom.isFinite() && request.zoom in 1f..5f && request.panX.isFinite() && request.panY.isFinite())
    val sample = minOf(1.0, sqrt(2_000_000.0 / request.width / request.height),
        4096.0 / request.width, 4096.0 / request.height)
    val width = (request.width * sample).toInt().coerceIn(1, 4096)
    val height = (request.height * sample).toInt().coerceIn(1, minOf(4096, 2_000_000 / width))
    val fit = minOf(request.width.toFloat() / pageWidth, request.height.toFloat() / pageHeight)
    val scale = fit * request.zoom
    val sampleX = width.toFloat() / request.width
    val sampleY = height.toFloat() / request.height
    return PdfViewport(width, height, scale * sampleX, scale * sampleY,
        ((request.width - pageWidth * scale) / 2 + request.panX) * sampleX,
        ((request.height - pageHeight * scale) / 2 + request.panY) * sampleY)
}
