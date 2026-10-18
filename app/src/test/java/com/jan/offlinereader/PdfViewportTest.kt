package com.jan.offlinereader

import org.junit.Assert.*
import org.junit.Test

class PdfViewportTest {
    @Test fun portraitPageFitsAndCentersBeforeZoom() {
        val view = pdfViewport(600, 800, PdfZoomRequest(1200, 1800, 1f, 0f, 0f))
        assertEquals(0f, view.translateX, .001f)
        assertEquals(100f * view.height / 1800, view.translateY, .001f)
        assertEquals(view.width / 2f, 300 * view.scaleX + view.translateX, .001f)
        assertEquals(view.height / 2f, 400 * view.scaleY + view.translateY, .001f)
    }

    @Test fun threeAndFiveTimesZoomIncreaseDetailWithoutLargerBitmaps() {
        val normal = pdfViewport(600, 800, PdfZoomRequest(1080, 1920, 1f, 0f, 0f))
        listOf(3f, 5f).forEach { zoom ->
            val view = pdfViewport(600, 800, PdfZoomRequest(1080, 1920, zoom, 0f, 0f))
            assertEquals(normal.width, view.width); assertEquals(normal.height, view.height)
            assertEquals(normal.scaleX * zoom, view.scaleX, .001f)
            assertEquals(view.width / 2f, 300 * view.scaleX + view.translateX, .001f)
            assertEquals(view.height / 2f, 400 * view.scaleY + view.translateY, .001f)
        }
    }

    @Test fun panningMovesTheRenderedRegionInScreenPixels() {
        val centered = pdfViewport(600, 800, PdfZoomRequest(1000, 1400, 3f, 0f, 0f))
        val panned = pdfViewport(600, 800, PdfZoomRequest(1000, 1400, 3f, 120f, -80f))
        assertEquals(centered.translateX + 120f, panned.translateX, .001f)
        assertEquals(centered.translateY - 80f, panned.translateY, .001f)
    }

    @Test fun landscapePageIsCenteredWithCorrectAspectRatio() {
        val view = pdfViewport(800, 400, PdfZoomRequest(1000, 1400, 1f, 0f, 0f))
        assertEquals(1.25f, view.scaleX, .001f)
        assertEquals(1.25f, view.scaleY, .001f)
        assertEquals(450f, view.translateY, .001f)
    }

    @Test fun largeAndExtremeWindowsStayWithinMemoryAndTextureLimits() {
        listOf(8000 to 6000, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1).forEach { (width, height) ->
            val view = pdfViewport(600, 800, PdfZoomRequest(width, height, 5f, 0f, 0f))
            assertTrue(view.width in 1..4096 && view.height in 1..4096)
            assertTrue(view.width.toLong() * view.height <= 2_000_000)
        }
    }
}
