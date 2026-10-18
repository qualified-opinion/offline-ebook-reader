package com.jan.offlinereader

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReaderApplication::class)
class CoverCacheTest {
    @get:Rule val folder = TemporaryFolder()
    @Before fun rejectInvalidImages() { ShadowBitmapFactory.setAllowInvalidImageData(false) }

    private fun cover(name: String): File {
        val file = folder.newFile(name)
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    @Test fun samplingMatchesPortraitLandscapeAndDenseCards() {
        assertEquals(8, coverSampleSize(1200, 1800, 150, 225))
        assertEquals(8, coverSampleSize(2400, 1200, 300, 500))
        assertEquals(2, coverSampleSize(1200, 1800, 450, 675))
    }

    @Test fun smallImagesAreNotUpsampled() {
        assertEquals(1, coverSampleSize(100, 150, 300, 450))
    }

    @Test fun returningAndConcurrentRequestsReuseTheDecodedCover() = runBlocking {
        val cache = CoverCache(2_000_000)
        val file = cover("first.png")
        val results = listOf(async { cache.load(file, 150, 225) }, async { cache.load(file, 150, 225) }).awaitAll()
        val bitmap = results.first()!!
        assertEquals(150, bitmap.width); assertEquals(225, bitmap.height)
        assertSame(bitmap, results.last())
        assertSame(bitmap, cache.cached(file, 150, 225))
        assertSame(bitmap, cache.load(file, 150, 225))
    }

    @Test fun largerCardsReceiveTheirOwnLargerDecode() = runBlocking {
        val cache = CoverCache(4_000_000)
        val file = cover("resize.png")
        val small = cache.load(file, 150, 225)!!
        val large = cache.load(file, 600, 900)!!
        assertNotSame(small, large)
        assertEquals(600, large.width); assertEquals(900, large.height)
        assertSame(small, cache.cached(file, 150, 225))
    }

    @Test fun evictionUsesBitmapBytesAndKeepsDisplayedImagesValid() = runBlocking {
        val cache = CoverCache(200_000)
        val firstFile = cover("one.png")
        val secondFile = cover("two.png")
        val first = cache.load(firstFile, 100, 150)!!
        val second = cache.load(secondFile, 100, 150)!!
        assertTrue(first.allocationByteCount + second.allocationByteCount > 200_000)
        assertNull(cache.cached(firstFile, 100, 150))
        assertSame(second, cache.cached(secondFile, 100, 150))
        assertFalse(first.isRecycled)
    }

    @Test fun oversizedDecodeDoesNotFlushUsefulCachedCovers() = runBlocking {
        val cache = CoverCache(200_000)
        val file = cover("sizes.png")
        val small = cache.load(file, 100, 150)!!
        val large = cache.load(file, 600, 900)!!
        assertTrue(large.allocationByteCount > 200_000)
        assertNull(cache.cached(file, 600, 900))
        assertSame(small, cache.cached(file, 100, 150))
        assertFalse(large.isRecycled)
    }

    @Test fun missingAndUndecodableCoversReturnNoBitmap() = runBlocking {
        val cache = CoverCache(200_000)
        assertNull(cache.load(File(folder.root, "missing.png"), 100, 150))
        val broken = folder.newFile("broken.png").apply { writeText("not an image") }
        assertNull(cache.load(broken, 100, 150))
    }
}
