package com.teliq.tv.data.vod

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Каталог фильмов и сериалов «VPortal» (OttPlayer и провайдеры на его основе).
 *
 * Ссылка выглядит так: `portal::[key:КЛЮЧ]http://хост/api/v1/`. Протокол: POST JSON на адрес API.
 *  * `{"key":К}` — корень: разделы (`items`, у каждого готовый `request`) и `controls` (поиск);
 *  * `{"key":К,"cmd":"flicks","fid":N,"offset":O,"limit":0}` — страница раздела (30 позиций), в конце элемент
 *    `type:"next"` с `request` следующей страницы; `count` — всего;
 *  * `{"key":К,"cmd":"flick","fid":N}` — карточка: `multistream` со списком серий/файлов;
 *  * `{"key":К,"cmd":"search","query":"…"}` — поиск;
 *  * позиция `type:"stream"` содержит прямую ссылку `url` на HLS (master.m3u8).
 * Ответ при ошибке: `{"type":"error","description":"subscription required"}` (нет подписки/неверный ключ).
 */
data class PortalLink(val key: String, val baseUrl: String) {
    companion object {
        private val FORMAT = Regex("""^portal::\[key:([^\]\s]+)]\s*(https?://\S+)$""", RegexOption.IGNORE_CASE)

        /** `portal::[key:…]http://…/api/v1/` → ссылка; иное (например, M3U) → null. */
        fun parse(raw: String): PortalLink? =
            FORMAT.matchEntire(raw.trim())?.let { PortalLink(it.groupValues[1], it.groupValues[2]) }

        fun isPortal(raw: String) = raw.trim().startsWith("portal::", ignoreCase = true)
    }
}

/** Раздел каталога («фильмы», «сериалы»…). [request] — готовый запрос первой страницы. */
data class VodCategory(val title: String, val request: JSONObject)

/** Позиция каталога: [streamUrl] есть у «stream» (играется сразу), у «multistream» — серии открываются через [VPortal.flick]. */
data class VodItem(
    val title: String,
    val poster: String?,
    val fid: Int,
    val streamUrl: String?,
    val year: Int,
    val description: String,
    val fhd: Boolean,
    val uhd: Boolean,
    val request: JSONObject?,
) {
    val isStream: Boolean get() = streamUrl != null
}

data class VodPage(val items: List<VodItem>, val nextRequest: JSONObject?, val total: Int)

class PortalException(message: String) : Exception(message)

object VPortal {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /** Блокирующий вызов — только из фонового потока. */
    private fun post(link: PortalLink, request: JSONObject): JSONObject {
        val body = JSONObject(request.toString()).put("key", link.key)
        val req = Request.Builder()
            .url(link.baseUrl)
            .header("User-Agent", "TELIQ")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw PortalException("HTTP ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            if (json.optString("type") == "error") {
                throw PortalException(json.optString("description").ifEmpty { "ошибка портала" })
            }
            return json
        }
    }

    /** Разделы каталога. Заодно проверяет ключ и подписку. */
    fun categories(link: PortalLink): List<VodCategory> {
        val items = post(link, JSONObject()).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("type") == "category" && it.has("request") }
            .map { VodCategory(it.optString("title"), it.getJSONObject("request")) }
    }

    /** Страница раздела или результата поиска; [request] — запрос раздела или `next` из прошлой страницы. */
    fun page(link: PortalLink, request: JSONObject): VodPage = parsePage(post(link, request))

    fun search(link: PortalLink, query: String): VodPage =
        parsePage(post(link, JSONObject().put("cmd", "search").put("query", query)))

    /** Серии/файлы карточки (для «multistream»). */
    fun flick(link: PortalLink, item: VodItem): List<VodItem> {
        val response = post(link, item.request ?: JSONObject().put("cmd", "flick").put("fid", item.fid))
        return parseItems(response.optJSONArray("items")).filter { it.isStream }
    }

    private fun parsePage(json: JSONObject): VodPage {
        val arr = json.optJSONArray("items") ?: JSONArray()
        var next: JSONObject? = null
        val items = ArrayList<VodItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "next") next = o.optJSONObject("request") else toItem(o)?.let(items::add)
        }
        return VodPage(items, next, json.optInt("count", items.size))
    }

    private fun parseItems(arr: JSONArray?): List<VodItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { toItem(arr.getJSONObject(it)) }
    }

    private fun toItem(o: JSONObject): VodItem? {
        val type = o.optString("type")
        if (type != "stream" && type != "multistream") return null
        return VodItem(
            title = o.optString("title"),
            poster = o.optString("img").ifEmpty { o.optString("imglr") }.ifEmpty { null },
            fid = o.optInt("fid"),
            streamUrl = o.optString("url").takeIf { type == "stream" && it.isNotEmpty() },
            year = o.optInt("year"),
            description = o.optString("description"),
            fhd = o.optBoolean("fhd"),
            uhd = o.optBoolean("4k"),
            request = o.optJSONObject("request"),
        )
    }
}
