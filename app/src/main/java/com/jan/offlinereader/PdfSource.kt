package com.jan.offlinereader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File

/** PdfRenderer is serialized; a page and its file descriptor never outlive the renderer. */
class PdfSource(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    private val mutex = Mutex()
    private var closed = false
    suspend fun render(index: Int, requestedWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "The PDF is closed." }
            renderer.openPage(index).use { page ->
                val ratio = page.height.toFloat() / page.width
                // Cap bitmap memory to approximately 8 MB per visible page.
                val width = minOf(requestedWidth.coerceIn(1, 1800), kotlin.math.sqrt(2_000_000.0 / ratio).toInt().coerceAtLeast(1))
                val height = (width * ratio).toInt().coerceAtLeast(1)
                createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
    internal suspend fun renderViewport(index: Int, request: PdfZoomRequest): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "The PDF is closed." }
            renderer.openPage(index).use { page ->
                val view = pdfViewport(page.width, page.height, request)
                val bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    Canvas(bitmap).drawRect(view.translateX, view.translateY,
                        view.translateX + page.width * view.scaleX, view.translateY + page.height * view.scaleY,
                        Paint().apply { color = Color.WHITE })
                    val matrix = Matrix().apply { setValues(floatArrayOf(
                        view.scaleX, 0f, view.translateX, 0f, view.scaleY, view.translateY, 0f, 0f, 1f)) }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    coroutineContext.ensureActive()
                    bitmap
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
            }
        }
    }
    suspend fun release() = withContext(Dispatchers.IO) { mutex.withLock { close() } }
    override fun close() { if (!closed) { closed = true; renderer.close() } }
}
