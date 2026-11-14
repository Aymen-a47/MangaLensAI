package com.mangalens.ai.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.mangalens.ai.ocr.OcrEngine.Script
import kotlin.math.max
import kotlin.math.min

/** Groups OCR blocks into speech bubbles, orders them for reading, and flags sound effects. */
object Bubbles {
    data class Bubble(val rect: Rect, val text: String, val script: Script, val sfx: Boolean)
    private class Raw(val rect: Rect, val text: String, val script: Script) {
        val cc = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val apc = rect.width().toFloat() * rect.height() / cc
    }

    private fun family(s: Script) = if (s == Script.JA || s == Script.ZH) 0 else s.ordinal + 10
    private fun cjk(s: Script) = s == Script.JA || s == Script.ZH

    fun group(blocks: List<OcrEngine.Block>, screenW: Int): List<Bubble> {
        if (blocks.isEmpty()) return emptyList()
        val base = max(8, (screenW * 0.012f).toInt())
        val parent = IntArray(blocks.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (i in blocks.indices) for (j in i + 1 until blocks.size) {
            val a = blocks[i]; val b = blocks[j]
            if (family(a.script) != family(b.script)) continue
            val m = max(base.toFloat(), 0.6f * min(a.charSize, b.charSize)).toInt()
            val ra = Rect(a.rect).apply { inset(-m, -m) }
            if (Rect.intersects(ra, b.rect)) parent[find(i)] = find(j)
        }
        val raw = blocks.indices.groupBy { find(it) }.values.map { idx ->
            val members = idx.map { blocks[it] }
            val script = members.groupingBy { it.script }.eachCount().maxByOrNull { it.value }!!.key
            val vertical = cjk(script) && members.all { it.rect.height() > it.rect.width() * 1.2f }
            val ordered = if (vertical) members.sortedByDescending { it.rect.centerX() }
            else members.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            val text = ordered.joinToString(if (cjk(script)) "" else " ") { it.text }
            val rect = Rect(ordered[0].rect).also { r -> ordered.forEach { r.union(it.rect) } }
            Raw(rect, text, script)
        }

        // Sound-effect heuristic: short + oversized glyphs, or repeated characters, or short big katakana.
        val apcs = raw.filter { it.cc >= 6 }.map { it.apc }.ifEmpty { raw.map { it.apc } }.sorted()
        val median = apcs[apcs.size / 2]
        val bubbles = raw.map { r ->
            val distinct = r.text.toSet().size
            val sfx = (r.cc <= 8 && median > 0 && r.apc > 2.5f * median) ||
                    (r.cc in 3..8 && distinct <= 2) ||
                    (r.script == Script.JA && r.cc <= 5 && r.text.all { it in '\u30A0'..'\u30FF' } && r.apc > 1.6f * median)
            Bubble(r.rect, r.text, r.script, sfx)
        }

        // Reading order: top->bottom bands; inside a band right->left for Japanese, left->right otherwise.
        val band = max(60f, screenW * 0.15f)
        return bubbles.sortedWith(compareBy<Bubble>(
            { (it.rect.centerY() / band).toInt() },
            { if (it.script == Script.JA) -it.rect.centerX() else it.rect.centerX() }
        ))
    }
}

/** Samples the colour surrounding a text box so the overlay patch matches the bubble. */
object Sampler {
    fun background(bmp: Bitmap, r: Rect): Int {
        val l = (r.left - 4).coerceIn(0, bmp.width - 1)
        val t = (r.top - 4).coerceIn(0, bmp.height - 1)
        val rr = (r.right + 4).coerceIn(0, bmp.width - 1)
        val b = (r.bottom + 4).coerceIn(0, bmp.height - 1)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun add(x: Int, y: Int) { val p = bmp.getPixel(x, y); rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p) }
        val n = 14
        for (i in 0..n) { val x = l + (rr - l) * i / n; add(x, t); add(x, b) }
        for (i in 0..n) { val y = t + (b - t) * i / n; add(l, y); add(rr, y) }
        val cr = rs.sorted()[rs.size / 2]; val cg = gs.sorted()[gs.size / 2]; val cb = bs.sorted()[bs.size / 2]
        if (cr > 235 && cg > 235 && cb > 235) return Color.WHITE
        if (cr < 25 && cg < 25 && cb < 25) return Color.rgb(15, 15, 15)
        return Color.rgb(cr, cg, cb)
    }
}
