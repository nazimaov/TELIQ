package com.teliq.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Парсер M3U / M3U8 — порт с Python-версии.
 * Поддерживает file://, http(s)://, EXTINF с tvg-name/tvg-logo/group-title/tvg-id
 * и одинокий тег #EXTGRP.
 */
object M3uParser {

    private val EXTINF = Regex("""^#EXTINF:(-?\d+(?:\.\d+)?)\s*(.*?),(.*)$""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun parse(source: String): List<Channel> {
        val text = when {
            source.startsWith("http://") || source.startsWith("https://") -> fetch(source)
            source.contains('\n') || source.startsWith("#EXTM3U") -> source
            else -> readFileWithEncoding(File(source))
        }
        return parseText(text)
    }

    private fun fetch(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val bytes = resp.body!!.bytes()
            return decodeAuto(bytes)
        }
    }

    private fun readFileWithEncoding(file: File): String = decodeAuto(file.readBytes())

    private fun decodeAuto(bytes: ByteArray): String {
        listOf("UTF-8", "windows-1251", "ISO-8859-1").forEach { enc ->
            try {
                return String(bytes, Charset.forName(enc))
            } catch (_: Throwable) {}
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun parseText(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var pending: MutableMap<String, String>? = null
        var number = 0
        text.lineSequence().map { it.trim() }.forEach { ln ->
            if (ln.isEmpty()) return@forEach
            if (ln.startsWith("#EXTM3U", ignoreCase = true)) return@forEach
            if (ln.startsWith("#EXTINF", ignoreCase = true)) {
                pending = parseExtinf(ln).toMutableMap()
                return@forEach
            }
            if (ln.startsWith("#EXTGRP:", ignoreCase = true)) {
                val grp = ln.substringAfter(':').trim()
                if (grp.isNotEmpty()) {
                    val p = pending ?: mutableMapOf<String, String>().also { pending = it }
                    if (p["group"].isNullOrEmpty()) p["group"] = grp
                }
                return@forEach
            }
            if (ln.startsWith("#")) return@forEach
            // URL канала
            val attrs = pending ?: emptyMap()
            number += 1
            out += Channel(
                number = number,
                name = attrs["name"]?.ifEmpty { null } ?: "Канал $number",
                url = ln,
                group = attrs["group"]?.ifEmpty { null } ?: "Другие",
                logoUrl = attrs["logo"].orEmpty(),
                tvgId = attrs["tvg_id"].orEmpty(),
            )
            pending = null
        }
        return out
    }

    private fun parseExtinf(line: String): Map<String, String> {
        val m = EXTINF.matchEntire(line) ?: return emptyMap()
        val attrPart = m.groupValues[2]
        val name = m.groupValues[3].trim()
        val attrs = mutableMapOf<String, String>()
        ATTR.findAll(attrPart).forEach { mr ->
            val key = mr.groupValues[1].lowercase()
            val value = mr.groupValues[2].ifEmpty { mr.groupValues[3] }
            attrs[key] = value
        }
        return mapOf(
            "name" to (name.ifEmpty { attrs["tvg-name"].orEmpty() }),
            "group" to attrs["group-title"].orEmpty(),
            "logo" to attrs["tvg-logo"].orEmpty(),
            "tvg_id" to attrs["tvg-id"].orEmpty(),
        )
    }
}
