package com.mangalens.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

class SettingsRepository(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _s = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _s
    val keyVersion = MutableStateFlow(0)

    /** API keys live ONLY in Android-Keystore-backed encrypted storage. Never hardcoded, never logged. */
    private val secure: SharedPreferences? by lazy {
        runCatching {
            val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, "secure_keys", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    val secureAvailable: Boolean get() = secure != null
    fun apiKey(p: Provider): String = secure?.getString("key_${p.name}", "") ?: ""
    fun setApiKey(p: Provider, v: String) {
        secure?.edit()?.putString("key_${p.name}", v.trim())?.apply()
        keyVersion.value = keyVersion.value + 1
    }

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_s.value)
        _s.value = n
        save(n)
    }

    fun loadGlossary(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("glossary", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun saveGlossary(m: Map<String, String>) {
        prefs.edit().putString("glossary", JSONObject(m).toString()).apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            targetLang = prefs.getString("targetLang", d.targetLang) ?: d.targetLang,
            sourceLang = prefs.getString("sourceLang", d.sourceLang) ?: d.sourceLang,
            autoTranslate = prefs.getBoolean("autoTranslate", d.autoTranslate),
            quality = prefs.getInt("quality", d.quality),
            speed = prefs.getInt("speed", d.speed),
            overlayOpacity = prefs.getFloat("opacity2", d.overlayOpacity),
            fontScale = prefs.getFloat("fontScale", d.fontScale),
            bgAuto = prefs.getBoolean("bgAuto", d.bgAuto),
            bgColor = prefs.getInt("bgColor", d.bgColor),
            showSfx = prefs.getBoolean("showSfx", d.showSfx),
            batterySaver = prefs.getBoolean("battery", d.batterySaver),
            cloudEnabled = prefs.getBoolean("cloud", d.cloudEnabled),
            provider = runCatching { Provider.valueOf(prefs.getString("provider", "") ?: "") }.getOrDefault(d.provider),
            openAiBaseUrl = prefs.getString("oaBase", d.openAiBaseUrl) ?: d.openAiBaseUrl,
            openAiModel = prefs.getString("oaModel", d.openAiModel) ?: d.openAiModel,
            anthropicModel = prefs.getString("anModel", d.anthropicModel) ?: d.anthropicModel,
            bubbleEnabled = prefs.getBoolean("bubble", d.bubbleEnabled)
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("targetLang", s.targetLang).putString("sourceLang", s.sourceLang)
            .putBoolean("autoTranslate", s.autoTranslate).putInt("quality", s.quality).putInt("speed", s.speed)
            .putFloat("opacity2", s.overlayOpacity).putFloat("fontScale", s.fontScale)
            .putBoolean("bgAuto", s.bgAuto).putInt("bgColor", s.bgColor).putBoolean("showSfx", s.showSfx)
            .putBoolean("battery", s.batterySaver).putBoolean("cloud", s.cloudEnabled)
            .putString("provider", s.provider.name).putString("oaBase", s.openAiBaseUrl)
            .putString("oaModel", s.openAiModel).putString("anModel", s.anthropicModel)
            .putBoolean("bubble", s.bubbleEnabled)
            .apply()
    }
}
