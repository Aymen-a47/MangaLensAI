package com.mangalens.ai.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler

/**
 * Wraps MediaProjection -> VirtualDisplay -> ImageReader.
 * A static screen produces no new frames, so the latest Image is held open and re-used.
 * Change detection reads a tiny luma grid straight from the buffer (no full-size Bitmap per poll).
 */
class ScreenCapturer(
    projection: MediaProjection,
    private var w: Int,
    private var h: Int,
    private val dpi: Int,
    handler: Handler
) {
    companion object { const val COLS = 40; const val ROWS = 72 }

    private val lock = Any()
    private var reader = newReader()
    private var current: Image? = null
    private val display: VirtualDisplay = projection.createVirtualDisplay(
        "MangaLens", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
    )

    private fun newReader(): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)

    private fun refreshLocked() {
        val img = try { reader.acquireLatestImage() } catch (e: IllegalStateException) { null }
        if (img != null) { current?.close(); current = img }
    }

    /** Luma grid (COLS x ROWS) or null if no frame yet. */
    fun sampleGrid(): IntArray? = synchronized(lock) {
        refreshLocked()
        val img = current ?: return@synchronized null
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val iw = img.width
        val ih = img.height
        val out = IntArray(COLS * ROWS)
        for (gy in 0 until ROWS) {
            val y0 = (gy * ih / ROWS).coerceAtMost(ih - 2)
            for (gx in 0 until COLS) {
                val x0 = (gx * iw / COLS).coerceAtMost(iw - 2)
                var sum = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val i = (y0 + dy) * rs + (x0 + dx) * ps
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    sum += (r * 77 + g * 150 + b * 29) shr 8
                }
                out[gy * COLS + gx] = sum shr 2
            }
        }
        out
    }

    /** Full-resolution copy of the latest frame. Caller must recycle(). Never written to disk. */
    fun grabBitmap(): Bitmap? = synchronized(lock) {
        refreshLocked()
        val img = current ?: return@synchronized null
        val plane = img.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val bmp = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
        val b = plane.buffer.duplicate()
        b.rewind()
        bmp.copyPixelsFromBuffer(b)
        if (rowPixels != img.width) {
            val c = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            bmp.recycle()
            c
        } else bmp
    }

    /** Rotation / size change. Android 14+ forbids a 2nd createVirtualDisplay, so resize in place. */
    fun resize(nw: Int, nh: Int) = synchronized(lock) {
        if (nw == w && nh == h) return@synchronized
        current?.close(); current = null
        reader.close()
        w = nw; h = nh
        reader = newReader()
        display.surface = reader.surface
        display.resize(nw, nh, dpi)
    }

    fun release() = synchronized(lock) {
        runCatching { current?.close() }; current = null
        runCatching { display.release() }
        runCatching { reader.close() }
    }
}
