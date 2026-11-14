package com.mangalens.ai.ocr

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.mangalens.ai.ocr.OcrEngine.Script
import kotlinx.coroutines.tasks.await

/** Automatic source-language detection (script first, ML Kit language-id for ambiguous cases). */
class LangDetector {
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )

    suspend fun detect(text: String, script: Script): String {
        if (script == Script.JA) return "ja"
        if (script == Script.KO) return "ko"
        val id = runCatching { client.identifyLanguage(text).await() }.getOrDefault("und").substringBefore('-')
        return when (script) {
            Script.ZH -> if (id == "ja" || id == "zh") id else "zh"
            Script.LATIN -> if (id != "und" && text.count { it.isLetter() } >= 15) id else "en"
            else -> if (id != "und") id else "en"
        }
    }

    fun close() = client.close()
}
