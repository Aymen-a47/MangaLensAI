package com.mangalens.ai.pipeline

import android.content.Context
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mangalens.ai.capture.ChangeDetector
import com.mangalens.ai.capture.ScreenCapturer
import com.mangalens.ai.data.*
import com.mangalens.ai.ocr.Bubbles
import com.mangalens.ai.ocr.LangDetector
import com.mangalens.ai.ocr.OcrEngine
import com.mangalens.ai.ocr.Sampler
import com.mangalens.ai.overlay.OverlayManager
import com.mangalens.ai.translate.*
import com.mangalens.ai.util.Screen
import com.mangalens.ai.util.sha256
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Watch -> detect page change -> (hide stale overlay) -> wait until settled -> capture clean frame ->
 * OCR -> group bubbles -> detect language -> cache lookup -> translate in parallel chunks -> draw progressively.
 */
class TranslationPipeline(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val repo: SettingsRepository,
    private val db: AppDb,
    private val overlay: OverlayManager,
    private val excluded: () -> List<Rect>,
    private val onPauseChanged: (Boolean) -> Unit,
    private val onProjectionStopped: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val ocr = OcrEngine()
    private val langs = LangDetector()
    private val onDevice = MlKitTranslator()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val detector = ChangeDetector()
    private val pages = PageCache(40)
    private val recent = ArrayDeque<Pair<String, String>>()
    private val glossary = LinkedHashMap<String, String>().apply { putAll(repo.loadGlossary()) }
    private val lock = Any()
    private val capturer: ScreenCapturer
    private val projCallback = object : MediaProjection.Callback() { override fun onStop() { onProjectionStopped() } }
    private var loopJob: Job? = null
    private var workJob: Job? = null
    private var configKey = ""
    @Volatile var paused = false
        private set

    init {
        val m = Screen.metrics(ctx)
        projection.registerCallback(projCallback, main)   // required before createVirtualDisplay on Android 14+
        capturer = ScreenCapturer(projection, m.widthPixels, m.heightPixels, m.densityDpi, main)
    }

    fun start() {
        EngineState.update { it.copy(running = true, paused = false, message = "Watching screen…", pages = 0) }
        scope.launch { db.dao().prune(System.currentTimeMillis() - 30L * 24 * 3600 * 1000) }
        loopJob = scope.launch { loop() }
    }

    fun togglePause() {
        paused = !paused
        if (paused) {
            workJob?.cancel()
            scope.launch { overlay.hide() }
            setStatus("Paused")
        } else {
            detector.reset()
            setStatus("Watching screen…")
        }
        EngineState.update { it.copy(paused = paused) }
        onPauseChanged(paused)
    }

    fun onConfigChanged() {
        val m = Screen.metrics(ctx)
        capturer.resize(m.widthPixels, m.heightPixels)
        main.post { overlay.resize() }
        pages.clear(); detector.reset(); workJob?.cancel()
    }

    fun stop() {
        scope.cancel()
        runCatching { capturer.release() }
        runCatching { projection.unregisterCallback(projCallback); projection.stop() }
        ocr.close(); langs.close(); onDevice.close()
        EngineState.state.value = EngineSnapshot()
    }

    // ------------------------------------------------------------------ loop
    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val s = repo.settings.value
            val key = "${s.targetLang}|${s.sourceLang}|${s.provider}|${s.cloudEnabled}|${s.showSfx}|${s.quality}|${s.batterySaver}"
            if (key != configKey) {
                if (configKey.isNotEmpty()) { pages.clear(); detector.reset(); workJob?.cancel(); overlay.hide() }
                configKey = key
                EngineState.update { it.copy(ocr = "ML Kit · on-device (JA/KO/ZH/Latin)", translator = pickTranslator(s).second) }
            }
            if (paused || !s.autoTranslate) {
                overlay.hide()
                delay(250)
                continue
            }
            val grid = capturer.sampleGrid()
            if (grid != null) {
                when (detector.feed(grid, SystemClock.elapsedRealtime(), settleMs(s))) {
                    ChangeDetector.Event.CHANGED -> { workJob?.cancel(); overlay.hide(); setStatus("Page changed…") }
                    ChangeDetector.Event.SETTLED -> { workJob?.cancel(); workJob = scope.launch { runProcess(grid) } }
                    ChangeDetector.Event.NONE -> {}
                }
            }
            delay(pollMs(s))
        }
    }

    private fun pollMs(s: AppSettings): Long = (when (s.speed) { 0 -> 150L; 1 -> 250L; else -> 450L }) * (if (s.batterySaver) 2 else 1)
    private fun settleMs(s: AppSettings): Long { val b = when (s.speed) { 0 -> 180L; 1 -> 300L; else -> 500L }; return if (s.batterySaver) b * 3 / 2 else b }

    private suspend fun runProcess(grid: IntArray) {
        try { process(grid) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { setStatus("Error: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------ one page
    private suspend fun process(grid: IntArray) {
        val s = repo.settings.value
        pages.find(grid)?.let { cached -> setStatus("Cached page"); render({ cached }, s); return }

        overlay.hide()
        val bmp = capturer.grabBitmap() ?: return
        setStatus("Reading text…")
        val t0 = SystemClock.elapsedRealtime()

        val regions = try {
            val blocks = ocr.detect(bmp, s.quality, if (s.batterySaver) 0.7f else 1f)
            val ex = excluded()
            val kept = blocks.filter { b -> ex.none { Rect.intersects(it, b.rect) } }
            Bubbles.group(kept, bmp.width).mapIndexed { i, b ->
                Region(i, b.rect, b.text, b.script, "und", b.sfx, Sampler.background(bmp, b.rect))
            }
        } finally { bmp.recycle() }

        var list = if (s.showSfx) regions else regions.filter { !it.sfx }
        list = list.map { r ->
            r.copy(lang = if (s.sourceLang != "auto") s.sourceLang else langs.detect(r.text, r.script))
        }.filter { it.lang != s.targetLang }

        if (list.isEmpty()) { pages.put(grid, emptyList()); setStatus("No text to translate on this page"); return }

        val tgt = s.targetLang
        val current = list.toMutableList()
        for (i in current.indices) {
            db.dao().get(cacheKey(current[i].text, tgt))?.let { current[i] = current[i].copy(translated = it.translated) }
        }
        val snapshot = { synchronized(lock) { current.filter { it.translated != null } } }
        if (current.any { it.translated != null }) render(snapshot, s)

        val pending = current.indices.filter { current[it].translated == null }
        if (pending.isNotEmpty()) {
            setStatus("Translating ${pending.size} bubble(s)…")
            val chunk = when (s.quality) { 0 -> 5; 1 -> 10; else -> 16 }
            coroutineScope {
                pending.chunked(chunk).map { idxs ->
                    async {
                        val items = idxs.map { SrcItem(current[it].text, current[it].lang, current[it].sfx) }
                        val res = translateChunk(items, s)
                        synchronized(lock) { idxs.forEachIndexed { k, i -> current[i] = current[i].copy(translated = res.texts[k]) } }
                        mergeGlossary(res.glossary)
                        idxs.forEachIndexed { k, i ->
                            val r = current[i]
                            if (res.texts[k].isNotBlank())
                                db.dao().put(TranslationEntity(cacheKey(r.text, tgt), r.text, res.texts[k], r.lang, tgt, System.currentTimeMillis()))
                        }
                        render(snapshot, s)
                    }
                }.awaitAll()
            }
        }

        val finalList = snapshot()
        pages.put(grid, finalList)
        synchronized(lock) {
            current.filter { it.translated != null }.takeLast(6).forEach { recent.addLast(it.text to it.translated!!) }
            while (recent.size > 6) recent.removeFirst()
        }
        val ms = SystemClock.elapsedRealtime() - t0
        EngineState.update { it.copy(message = "Translated ${finalList.size} bubble(s) in ${ms} ms", pages = it.pages + 1, lastMs = ms) }
    }

    private suspend fun render(snapshot: () -> List<Region>, s: AppSettings) {
        currentCoroutineContext().ensureActive()
        overlay.show(snapshot, s)
        detector.expectOverlayChange(SystemClock.elapsedRealtime() + 250)
    }

    // ------------------------------------------------------------------ translation
    private fun pickTranslator(s: AppSettings): Pair<TranslationProvider, String> {
        if (s.cloudEnabled && s.provider != Provider.ON_DEVICE) {
            val key = repo.apiKey(s.provider)
            if (key.isNotBlank()) return when (s.provider) {
                Provider.OPENAI -> OpenAiTranslator(http, s.openAiBaseUrl, key, s.openAiModel) to "OpenAI-compatible · ${s.openAiModel}"
                else -> AnthropicTranslator(http, key, s.anthropicModel) to "Claude · ${s.anthropicModel}"
            }
            return onDevice to "ML Kit on-device (add API key to use AI)"
        }
        return onDevice to "ML Kit on-device"
    }

    private suspend fun translateChunk(items: List<SrcItem>, s: AppSettings): TResult {
        val tctx = synchronized(lock) { TCtx(s.targetLang, glossary.toMap(), recent.toList(), s.quality) }
        val (prov, _) = pickTranslator(s)
        return try { prov.translate(items, tctx) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) {
            if (prov === onDevice) throw e
            setStatus("Cloud failed (${e.message?.take(50)}), using on-device")
            onDevice.translate(items, tctx)
        }
    }

    private fun mergeGlossary(g: Map<String, String>) {
        if (g.isEmpty()) return
        synchronized(lock) {
            g.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) glossary[k] = v }
            while (glossary.size > 80) glossary.remove(glossary.keys.first())
            repo.saveGlossary(glossary.toMap())
        }
    }

    private fun cacheKey(text: String, tgt: String) = sha256("$tgt|" + text.trim().replace(Regex("\\s+"), " "))
    private fun setStatus(m: String) = EngineState.update { it.copy(message = m) }
}
