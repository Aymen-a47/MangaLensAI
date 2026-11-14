package com.mangalens.ai.data

import android.graphics.Rect
import com.mangalens.ai.ocr.OcrEngine

/** One speech bubble / caption on the current page. */
data class Region(
    val id: Int,
    val rect: Rect,
    val text: String,
    val script: OcrEngine.Script,
    val lang: String,
    val sfx: Boolean,
    val bg: Int,
    val translated: String? = null
)

enum class Provider { ON_DEVICE, OPENAI, ANTHROPIC }

data class AppSettings(
    val targetLang: String = "ar",
    val sourceLang: String = "auto",
    val autoTranslate: Boolean = true,
    val quality: Int = 1,            // 0 fast, 1 balanced, 2 best
    val speed: Int = 1,              // 0 fast, 1 balanced, 2 relaxed
    val overlayOpacity: Float = 0.96f,
    val fontScale: Float = 1f,
    val bgAuto: Boolean = true,
    val bgColor: Int = 0xFFFFFFFF.toInt(),
    val showSfx: Boolean = false,
    val batterySaver: Boolean = false,
    val cloudEnabled: Boolean = true,
    val provider: Provider = Provider.ON_DEVICE,
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val openAiModel: String = "gpt-4o-mini",
    val anthropicModel: String = "claude-haiku-5-5",
    val bubbleEnabled: Boolean = true
)

data class EngineSnapshot(
    val running: Boolean = false,
    val paused: Boolean = false,
    val message: String = "Idle",
    val ocr: String = "Idle",
    val translator: String = "Not started",
    val pages: Int = 0,
    val lastMs: Long = 0
)

object EngineState {
    val state = kotlinx.coroutines.flow.MutableStateFlow(EngineSnapshot())
    fun update(f: (EngineSnapshot) -> EngineSnapshot) { state.value = f(state.value) }
}

object Langs {
    val targets = listOf(
        "ar" to "Arabic (العربية)", "en" to "English", "fa" to "Persian (فارسی)", "ur" to "Urdu (اردو)",
        "tr" to "Turkish", "fr" to "French", "es" to "Spanish", "de" to "German", "id" to "Indonesian", "ru" to "Russian"
    )
    fun name(code: String) = targets.firstOrNull { it.first == code }?.second?.substringBefore(" (") ?: code
    fun isRtl(code: String) = code in setOf("ar", "fa", "ur", "he")
}
