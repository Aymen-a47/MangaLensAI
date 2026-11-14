package com.mangalens.ai.capture

import kotlin.math.abs

/**
 * Page-change detection on the luma grid.
 *  STABLE   : compare to the last processed page; big difference -> CHANGED
 *  CHANGING : wait until consecutive frames stop moving for [settleMs] -> SETTLED (run OCR once)
 */
class ChangeDetector {
    enum class Event { NONE, CHANGED, SETTLED }
    private enum class St { STABLE, CHANGING }

    companion object {
        private const val CELL_DIFF = 22
        private const val CHANGE_RATIO = 0.025f
        private const val SETTLE_RATIO = 0.008f
        private const val SKIP_TOP = 2      // ignore status-bar rows
        private const val SKIP_BOTTOM = 3   // ignore navigation-bar rows

        /** Fraction of grid cells whose brightness differs noticeably. */
        fun ratio(a: IntArray, b: IntArray): Float {
            val cols = ScreenCapturer.COLS
            var diff = 0
            var total = 0
            for (y in SKIP_TOP until ScreenCapturer.ROWS - SKIP_BOTTOM) {
                for (x in 0 until cols) {
                    val i = y * cols + x
                    total++
                    if (abs(a[i] - b[i]) > CELL_DIFF) diff++
                }
            }
            return diff.toFloat() / total
        }
    }

    @Volatile private var baseline: IntArray? = null
    private var prev: IntArray? = null
    private var st = St.CHANGING
    private var stableSince = -1L
    @Volatile private var ignoreUntil = 0L
    @Volatile private var rebase = false

    fun reset() { baseline = null; prev = null; st = St.CHANGING; stableSince = -1; rebase = false; ignoreUntil = 0 }

    /** Call right after the overlay is drawn: the overlay itself changes the frame and must not count as a page change. */
    fun expectOverlayChange(untilMs: Long) { ignoreUntil = untilMs; rebase = true }

    fun feed(g: IntArray, now: Long, settleMs: Long): Event {
        val p = prev
        prev = g
        if (now < ignoreUntil) return Event.NONE
        if (rebase) { rebase = false; baseline = g; st = St.STABLE; stableSince = -1; return Event.NONE }

        val b = baseline
        if (st == St.STABLE && b != null) {
            if (ratio(g, b) > CHANGE_RATIO) { st = St.CHANGING; stableSince = -1; return Event.CHANGED }
            return Event.NONE
        }
        if (p != null && ratio(g, p) < SETTLE_RATIO) {
            if (stableSince < 0) stableSince = now
            else if (now - stableSince >= settleMs) {
                baseline = g; st = St.STABLE; stableSince = -1
                return Event.SETTLED
            }
        } else stableSince = -1
        return Event.NONE
    }
}
