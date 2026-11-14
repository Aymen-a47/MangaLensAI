package com.mangalens.ai.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlin.math.min

/**
 * Multi-script on-device OCR (ML Kit): Japanese, Korean, Chinese, Latin.
 * Recognizers run in parallel; each block is kept only if its characters match that recognizer's script.
 * Fast path: try the script seen on the previous page first.
 */
class OcrEngine {
    enum class Script { JA, KO, ZH, LATIN, OTHER }
    data class Block(val rect: Rect, val text: String, val script: Script, val lines: Int, val charSize: Float)

    private val recognizers = mutableMapOf<Script, TextRecognizer>()
    @Volatile private var last: Script? = null

    private fun rec(s: Script): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(s) {
            when (s) {
                Script.JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Script.KO -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                Script.ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    suspend fun detect(src: Bitmap, quality: Int, scale: Float): List<Block> = coroutineScope {
        val bmp = if (scale < 0.99f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        try {
            val image = InputImage.fromBitmap(bmp, 0)
            val all = listOf(Script.JA, Script.KO, Script.ZH, Script.LATIN)
            val primary = last
            val batches = if (quality >= 2 || primary == null) listOf(all) else listOf(listOf(primary), all - primary)
            var found = emptyList<Block>()
            for (batch in batches) {
                found = batch.map { s -> async { runOne(s, image) } }.awaitAll().flatten()
                if (found.isNotEmpty()) break
            }
            val merged = dedupe(found)
            merged.groupingBy { it.script }.eachCount().maxByOrNull { it.value }?.key?.let { last = it }
            if (scale < 0.99f) merged.map {
                val r = it.rect
                it.copy(
                    rect = Rect((r.left / scale).toInt(), (r.top / scale).toInt(), (r.right / scale).toInt(), (r.bottom / scale).toInt()),
                    charSize = it.charSize / scale
                )
            } else merged
        } finally {
            if (bmp !== src) bmp.recycle()
        }
    }

    private suspend fun runOne(s: Script, image: InputImage): List<Block> {
        val result = try { rec(s).process(image).await() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return emptyList() }
        return result.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            if (r.width() < 10 || r.height() < 10) return@mapNotNull null
            val sc = classify(b.text)
            if (!accepts(s, sc)) return@mapNotNull null
            val lines = b.lines.size.coerceAtLeast(1)
            val horizontal = r.width() >= r.height() * 0.9f
            val cs = if (horizontal) r.height().toFloat() / lines else r.width().toFloat() / lines
            val text = clean(b.text, sc)
            if (text.isBlank()) null else Block(Rect(r), text, sc, lines, cs)
        }
    }

    private fun accepts(rec: Script, sc: Script) = when (rec) {
        Script.JA -> sc == Script.JA || sc == Script.ZH
        Script.ZH -> sc == Script.ZH
        Script.KO -> sc == Script.KO
        else -> sc == Script.LATIN
    }

    private fun clean(t: String, sc: Script): String = when (sc) {
        Script.JA, Script.ZH -> t.replace(Regex("\\s+"), "")
        Script.LATIN -> t.replace(Regex("-\\s*\\n"), "").replace(Regex("\\s+"), " ").trim()
        else -> t.replace(Regex("\\s+"), " ").trim()
    }

    private fun dedupe(list: List<Block>): List<Block> {
        val kept = mutableListOf<Block>()
        for (b in list.sortedByDescending { it.text.length }) {
            if (kept.none { overlap(it.rect, b.rect) > 0.5f }) kept += b
        }
        return kept
    }

    private fun overlap(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val minA = min(a.width() * a.height(), b.width() * b.height()).toFloat()
        return if (minA <= 0f) 0f else (i.width() * i.height()) / minA
    }

    fun close() = synchronized(recognizers) { recognizers.values.forEach { runCatching { it.close() } }; recognizers.clear() }

    companion object {
        fun classify(t: String): Script {
            var kana = 0; var hangul = 0; var han = 0; var latin = 0
            for (ch in t) when {
                ch in '\u3040'..'\u30FF' -> kana++
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' -> hangul++
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> han++
                ch.isLetter() && ch.code < 0x250 -> latin++
            }
            return when {
                kana > 0 -> Script.JA
                hangul > 0 -> Script.KO
                han > 0 -> Script.ZH
                latin >= 2 -> Script.LATIN
                else -> Script.OTHER
            }
        }
    }
}
