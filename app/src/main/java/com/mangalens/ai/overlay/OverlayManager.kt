package com.mangalens.ai.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.mangalens.ai.data.AppSettings
import com.mangalens.ai.data.Region
import com.mangalens.ai.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Full-screen, FLAG_NOT_TOUCHABLE overlay: every touch (swipe, scroll, pinch, tap) goes straight through to the reader.
 */
class OverlayManager(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val view = RegionsView(ctx)
    private var attached = false

    private fun params(): WindowManager.LayoutParams {
        val m = Screen.metrics(ctx)
        return WindowManager.LayoutParams(
            m.widthPixels, m.heightPixels, 0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun attach() {
        if (attached) return
        wm.addView(view, params())
        attached = true
    }

    fun resize() { if (attached) wm.updateViewLayout(view, params()) }

    /** Snapshot is taken on the main thread at draw time so concurrent updates never overwrite newer results. */
    suspend fun show(snapshot: () -> List<Region>, s: AppSettings) = withContext(Dispatchers.Main) {
        view.setRegions(snapshot(), s)
        view.visibility = View.VISIBLE
    }

    /** Removes translations (they're stale) and waits one frame if something was visible, so the next capture is clean. */
    suspend fun hide() {
        val was = withContext(Dispatchers.Main) { val h = view.hasItems; if (h) view.clear(); h }
        if (was) delay(90)
    }

    fun destroy() {
        if (attached) { runCatching { wm.removeView(view) }; attached = false }
    }
}
