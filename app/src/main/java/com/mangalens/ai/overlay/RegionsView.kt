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

/** Draws opaque patches + auto-fitted RTL text exactly over the original bubbles. Outside patches it is fully transparent. */
class RegionsView(ctx: Context) : View(ctx) {
    private class Item(val box: RectF, val bg: Int, val layout: StaticLayout, val x: Float, val y: Float)

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
        val out = ArrayList<Item>()
        regions.forEachIndexed { idx, r ->
            val text = r.translated ?: return@forEachIndexed
            val orig = RectF(r.rect)
            val grown = grow(orig, sw, sh)
            val others = regions.filterIndexed { i, _ -> i != idx }.map { RectF(it.rect) }
            val box = if (others.none { RectF.intersects(it, grown) }) grown
            else RectF(orig).also { it.inset(-orig.width() * 0.04f, -orig.height() * 0.04f) }

            val bg = if (s.bgAuto) r.bg else s.bgColor
            val fg = if (luminance(bg) > 0.5f) Color.rgb(17, 17, 17) else Color.WHITE
            val padX = box.width() * 0.07f
            val padY = box.height() * 0.06f
            val availW = (box.width() - 2 * padX).toInt().coerceAtLeast(20)
            val availH = box.height() - 2 * padY
            val minPx = 8f * d
            var lo = minPx
            var hi = max(minPx, min(box.height() * 0.45f, 26f * d) * s.fontScale)
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
            val tx = box.left + (box.width() - layout.width) / 2f
            val ty = box.top + (box.height() - layout.height) / 2f
            out += Item(box, bg, layout, tx, ty)
        }
        items = out
        invalidate()
    }

    private fun grow(o: RectF, sw: Float, sh: Float): RectF {
        var w = o.width() * 1.14f
        val h = o.height() * 1.10f
        val minW = min(o.height() * 0.8f, sw * 0.5f)   // tall, narrow vertical bubbles need room for horizontal Arabic
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
            val rad = min(it.box.width(), it.box.height()) * 0.22f
            c.drawRoundRect(it.box, rad, rad, bgPaint)
            c.save()
            c.translate(it.x, it.y)
            it.layout.draw(c)
            c.restore()
        }
    }
}
