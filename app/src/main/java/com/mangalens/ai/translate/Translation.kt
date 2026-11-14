package com.mangalens.ai.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.mangalens.ai.data.Langs
import com.mangalens.ai.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class SrcItem(val text: String, val lang: String, val sfx: Boolean)
data class TCtx(
    val target: String,
    val glossary: Map<String, String>,
    val previous: List<Pair<String, String>>,
    val quality: Int
)
data class TResult(val texts: List<String>, val glossary: Map<String, String> = emptyMap())

/** Swap providers by implementing this interface. */
interface TranslationProvider {
    suspend fun translate(items: List<SrcItem>, ctx: TCtx): TResult
    fun close() {}
}

// ---------------------------------------------------------------- on-device
class MlKitTranslator : TranslationProvider {
    private val clients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val ready = ConcurrentHashMap.newKeySet<String>()

    override suspend fun translate(items: List<SrcItem>, ctx: TCtx): TResult {
        val tgt = TranslateLanguage.fromLanguageTag(ctx.target) ?: error("Target language not supported on-device")
        val out = items.map { item ->
            val src = TranslateLanguage.fromLanguageTag(item.lang.substringBefore('-')) ?: TranslateLanguage.ENGLISH
            if (src == tgt) item.text else {
                val key = "$src>$tgt"
                val c = clients.getOrPut(key) {
                    Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
                }
                if (ready.add(key)) {
                    try { c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                    catch (e: Exception) { ready.remove(key); throw e }
                }
                c.translate(item.text).await()
            }
        }
        return TResult(out)
    }

    override fun close() { clients.values.forEach { runCatching { it.close() } }; clients.clear() }
}

// ---------------------------------------------------------------- prompts
object Prompts {
    fun system(target: String): String {
        val t = Langs.name(target)
        return """You are a professional manga / manhwa / manhua / webtoon / comic translator. Translate into natural $t.
Rules:
- Translate meaning, tone, emotion and each character's personality. Never translate word-for-word; the result must read like native $t dialogue.
- Understand slang, jokes, honorifics, wordplay and idioms; use the natural $t equivalent.
- Keep proper names consistent: always use the glossary spelling when a name is listed. Return any NEW character/place/technique names with your chosen $t form in "g".
- Items with sfx=true are sound effects: give a short natural $t equivalent (onomatopoeia), no explanation.
- Keep each translation short enough to fit a speech bubble. Preserve ellipses and emphasis. Use $t punctuation.
- The "lang" field is only a hint and may be wrong. Items are in reading order; "previous" is the preceding dialogue for context.
- Never add notes, romanization or commentary.
Output ONLY compact JSON: {"t":["..."],"g":{"OriginalName":"Translated"}} where "t" has exactly one string per input item, same order."""
    }

    fun user(items: List<SrcItem>, ctx: TCtx): String {
        val o = JSONObject()
        o.put("glossary", JSONObject(ctx.glossary))
        o.put("previous", JSONArray().apply { ctx.previous.forEach { put(JSONObject().put("src", it.first).put("tr", it.second)) } })
        o.put("items", JSONArray().apply {
            items.forEachIndexed { i, it -> put(JSONObject().put("i", i).put("lang", it.lang).put("sfx", it.sfx).put("text", it.text)) }
        })
        return o.toString()
    }

    fun parse(raw: String, n: Int): TResult {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        require(s >= 0 && e > s) { "Unreadable reply" }
        val obj = JSONObject(raw.substring(s, e + 1))
        val arr = obj.getJSONArray("t")
        require(arr.length() == n) { "Expected $n items, got ${arr.length()}" }
        val g = mutableMapOf<String, String>()
        obj.optJSONObject("g")?.let { go -> go.keys().forEach { k -> g[k] = go.optString(k) } }
        return TResult(List(n) { arr.getString(it).trim() }, g)
    }
}

// ---------------------------------------------------------------- cloud base
abstract class CloudTranslator(protected val http: OkHttpClient) : TranslationProvider {
    protected abstract suspend fun complete(system: String, user: String): String

    override suspend fun translate(items: List<SrcItem>, ctx: TCtx): TResult {
        val system = Prompts.system(ctx.target)
        val user = Prompts.user(items, ctx)
        var last: Throwable? = null
        repeat(2) {
            try { return Prompts.parse(complete(system, user), items.size) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { last = e; delay(600) }
        }
        throw last ?: IllegalStateException("Translation failed")
    }

    protected suspend fun post(req: Request): String {
        http.newCall(req).await().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${body.take(160)}")
            return body
        }
    }
}

/** Works with OpenAI and any OpenAI-compatible endpoint (OpenRouter, Gemini compat, DeepSeek, local servers...). */
class OpenAiTranslator(http: OkHttpClient, private val baseUrl: String, private val key: String, private val model: String) :
    CloudTranslator(http) {
    override suspend fun complete(system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = JSONObject(post(req))
        return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}

class AnthropicTranslator(http: OkHttpClient, private val key: String, private val model: String) : CloudTranslator(http) {
    override suspend fun complete(system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val content = JSONObject(post(req)).getJSONArray("content")
        return buildString { for (i in 0 until content.length()) content.getJSONObject(i).optString("text").let { append(it) } }
    }
}
