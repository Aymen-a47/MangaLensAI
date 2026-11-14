package com.mangalens.ai.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.mangalens.ai.R
import kotlin.math.abs

/** Draggable floating bubble: tap to expand, then Pause/Resume or Close without leaving the reader. */
class ControlBubble(
    private val ctx: Context,
    private val onToggle: () -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val d = ctx.resources.displayMetrics.density
    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val mainBtn = circle(R.drawable.ic_stat, 0xE66C5CE7.toInt())
    private val pauseBtn = circle(R.drawable.ic_pause, 0xE6334155.toInt())
    private val stopBtn = circle(R.drawable.ic_close, 0xE6B91C1C.toInt())
    private var shown = false
    private var expanded = false

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = (8 * d).toInt(); y = (220 * d).toInt() }

    private fun circle(res: Int, color: Int) = ImageView(ctx).apply {
        setImageResource(res)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        val p = (10 * d).toInt(); setPadding(p, p, p, p)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { topMargin = (6 * d).toInt() }
    }

    init {
        root.addView(mainBtn); root.addView(pauseBtn); root.addView(stopBtn)
        pauseBtn.visibility = View.GONE; stopBtn.visibility = View.GONE
        pauseBtn.setOnClickListener { onToggle() }
        stopBtn.setOnClickListener { onStop() }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        mainBtn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > 12 * d) moved = true
                    if (moved) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); wm.updateViewLayout(root, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expanded = !expanded
                        val v = if (expanded) View.VISIBLE else View.GONE
                        pauseBtn.visibility = v; stopBtn.visibility = v
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun show() { if (!shown) { wm.addView(root, lp); shown = true } }

    fun setPaused(p: Boolean) {
        root.post { pauseBtn.setImageResource(if (p) R.drawable.ic_play else R.drawable.ic_pause); mainBtn.alpha = if (p) 0.55f else 1f }
    }

    /** Screen rect of the bubble so OCR never reads (and translates) our own controls. */
    fun bounds(): Rect {
        val w = if (root.width > 0) root.width else (44 * d).toInt()
        val h = if (root.height > 0) root.height else (44 * d).toInt()
        val m = (8 * d).toInt()
        return Rect(lp.x - m, lp.y - m, lp.x + w + m, lp.y + h + m)
    }

    fun destroy() { if (shown) { runCatching { wm.removeView(root) }; shown = false } }
}
