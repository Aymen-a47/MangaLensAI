package com.mangalens.ai.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import com.mangalens.ai.data.AppSettings
import com.mangalens.ai.data.Langs
import com.mangalens.ai.data.Region
import com.mangalens.ai.util.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * Hides the original text completely (opaque patch that always covers the whole original box + margin)
 * and draws the translation centred inside it. Patches are drawn first, texts second, so a neighbouring
 * patch can never cover someone else's translation.
 */
class RegionsView(ctx: Context) : View(ctx) {
    private class Item(val patch: RectF, val bg: Int, val layout: StaticLayout, val x: Float, val y: Float)

    private var items: List<Item> = emptyList()
    private var opacity = 1f
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val hasItems: Boolean get() = items.isNotEmpty()

    fun clear() { items = emptyList(); invalidate() }

    fun setRegions(regions: List<Region>, s: AppSettings) {
        opacity = s.overlayOpacity
        val d = resources.displayMetrics.density
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        val rtl = Langs.isRtl(s.targetLang)

        // 1) cover rect: ALWAYS bigger than the OCR box, because OCR boxes are tight and miss glyph edges
        val covers = regions.map { r ->
            val o = RectF(r.rect)
            val pad = max(6f * d, 0.12f * min(o.width(), o.height()))
            RectF(o.left - pad, o.top - pad, o.right + pad, o.bottom + pad)
        }

        val out = ArrayList<Item>()
        regions.forEachIndexed { idx, r ->
            val text = r.translated ?: return@forEachIndexed
            val cover = covers[idx]
            val others = covers.filterIndexed { i, _ -> i != idx }

            // 2) text box: try a roomier box for long Arabic, but never into a neighbour
            val grown = grow(cover, sw, sh)
            val box = if (others.none { RectF.intersects(it, grown) }) grown else RectF(cover)

            val bg = if (s.bgAuto) r.bg else s.bgColor
            val fg = if (luminance(bg) > 0.5f) Color.rgb(17, 17, 17) else Color.WHITE
            val padX = box.width() * 0.07f
            val padY = box.height() * 0.06f
            val availW = (box.width() - 2 * padX).toInt().coerceAtLeast(20)
            val availH = box.height() - 2 * padY
            val minPx = 9f * d
            var lo = minPx
            var hi = max(minPx, min(box.height() * 0.42f, 22f * d) * s.fontScale)
            var best: StaticLayout? = null
            repeat(9) {
                val mid = (lo + hi) / 2f
                val l = build(text, mid, availW, fg, rtl)
                if (l.height <= availH) { best = l; lo = mid } else hi = mid
            }
            val layout = best ?: build(text, minPx, availW, fg, rtl)
            val need = layout.height + 2 * padY
            if (need > box.height()) {
                val extra = (need - box.height()) / 2f
                box.inset(0f, -extra)
                if (box.top < 0) box.offset(0f, -box.top)
                if (box.bottom > sh) box.offset(0f, sh - box.bottom)
            }
            val patch = RectF(box).also { it.union(cover) }     // never smaller than the original text area
            val tx = box.left + (box.width() - layout.width) / 2f
            val ty = box.top + (box.height() - layout.height) / 2f
            out += Item(patch, bg, layout, tx, ty)
        }
        items = out
        invalidate()
    }

    private fun grow(o: RectF, sw: Float, sh: Float): RectF {
        var w = o.width() * 1.08f
        val h = o.height() * 1.06f
        val minW = min(o.height() * 0.8f, sw * 0.5f)   // tall narrow vertical bubbles need room for horizontal Arabic
        if (w < minW) w = minW
        w = min(w, sw * 0.96f)
        val r = RectF(o.centerX() - w / 2, o.centerY() - h / 2, o.centerX() + w / 2, o.centerY() + h / 2)
        if (r.left < 0) r.offset(-r.left, 0f)
        if (r.right > sw) r.offset(sw - r.right, 0f)
        if (r.top < 0) r.offset(0f, -r.top)
        if (r.bottom > sh) r.offset(0f, sh - r.bottom)
        return r
    }

    private fun build(text: String, size: Float, w: Int, color: Int, rtl: Boolean): StaticLayout {
        val p = TextPaint(base).apply { textSize = size; this.color = color }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(c: Canvas) {
        for (it in items) {
            bgPaint.color = it.bg
            bgPaint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
            val rad = min(it.patch.width(), it.patch.height()) * 0.12f
            c.drawRoundRect(it.patch, rad, rad, bgPaint)
        }
        for (it in items) {
            c.save()
            c.translate(it.x, it.y)
            it.layout.draw(c)
            c.restore()
        }
    }
}
