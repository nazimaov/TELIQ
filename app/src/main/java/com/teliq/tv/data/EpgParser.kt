package com.teliq.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Одна запись передачи. */
data class Programme(val start: Long, val stop: Long, val title: String)

/** Хранилище EPG: id канала → список передач. */
class Epg {
    val byId: MutableMap<String, MutableList<Programme>> = mutableMapOf()
    val nameToId: MutableMap<String, String> = mutableMapOf()

    fun addProg(id: String, p: Programme) {
        byId.getOrPut(id) { mutableListOf() }.add(p)
    }

    fun addName(id: String, displayName: String) {
        val key = norm(displayName)
        if (key.isNotEmpty() && id.isNotEmpty()) nameToId.putIfAbsent(key, id)
    }

    fun finalize() {
        byId.values.forEach { it.sortBy { p -> p.start } }
    }

    fun resolveId(tvgId: String, name: String): String? {
        if (tvgId.isNotEmpty() && byId.containsKey(tvgId)) return tvgId
        if (name.isNotEmpty()) return nameToId[norm(name)]
        return null
    }

    fun current(id: String, now: Long = System.currentTimeMillis()): Programme? {
        val list = byId[id] ?: return null
        for (p in list) {
            if (now in p.start..p.stop) return p
            if (p.start > now) break
        }
        return null
    }

    fun nextAfter(id: String, now: Long = System.currentTimeMillis()): Programme? {
        val list = byId[id] ?: return null
        return list.firstOrNull { it.start > now }
    }

    fun isEmpty() = byId.isEmpty()
    fun channelsCount() = byId.size
    fun programmesCount() = byId.values.sumOf { it.size }

    companion object {
        fun norm(name: String): String {
            var n = name.lowercase(Locale.ROOT)
            listOf(" hd", " fhd", " 4k", " uhd", " plus", " +").forEach {
                if (n.endsWith(it)) n = n.dropLast(it.length)
            }
            return n.filter { it.isLetterOrDigit() }
        }
    }
}

object EpgParser {

    private val http = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val TIME_FMT = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Принимает URL или путь к XMLTV (.xml / .xml.gz). */
    fun parse(source: String): Epg {
        val raw = if (source.startsWith("http://") || source.startsWith("https://")) {
            http.newCall(Request.Builder().url(source).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body!!.bytes()
            }
        } else {
            java.io.File(source).readBytes()
        }
        val stream: InputStream = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw))
        } else {
            ByteArrayInputStream(raw)
        }
        return parseStream(stream)
    }

    private fun parseStream(input: InputStream): Epg {
        val epg = Epg()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val xpp = factory.newPullParser()
        xpp.setInput(input, null)

        var event = xpp.eventType
        var currentChannelId: String? = null
        var inChannel = false
        var inProgramme = false
        var inTitle = false

        var pStart = 0L
        var pStop = 0L
        var pChan = ""
        var pTitle = ""
        var capturedTitle = false

        var displayName = StringBuilder()
        var captureDisplayName = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (xpp.name) {
                    "channel" -> {
                        inChannel = true
                        currentChannelId = xpp.getAttributeValue(null, "id").orEmpty()
                    }
                    "display-name" -> if (inChannel) {
                        captureDisplayName = true
                        displayName = StringBuilder()
                    }
                    "programme" -> {
                        inProgramme = true
                        pChan = xpp.getAttributeValue(null, "channel").orEmpty()
                        pStart = parseTime(xpp.getAttributeValue(null, "start"))
                        pStop = parseTime(xpp.getAttributeValue(null, "stop"))
                        pTitle = ""
                        capturedTitle = false
                    }
                    "title" -> if (inProgramme) inTitle = true
                }
                XmlPullParser.TEXT -> {
                    if (captureDisplayName) displayName.append(xpp.text)
                    else if (inTitle && !capturedTitle) {
                        val t = xpp.text.trim()
                        if (t.isNotEmpty()) { pTitle = t; capturedTitle = true }
                    }
                }
                XmlPullParser.END_TAG -> when (xpp.name) {
                    "channel" -> { inChannel = false; currentChannelId = null }
                    "display-name" -> {
                        if (captureDisplayName && currentChannelId != null) {
                            epg.addName(currentChannelId!!, displayName.toString().trim())
                        }
                        captureDisplayName = false
                    }
                    "programme" -> {
                        if (pChan.isNotEmpty() && pStart > 0 && pStop > 0) {
                            epg.addProg(pChan, Programme(pStart, pStop, pTitle))
                        }
                        inProgramme = false
                    }
                    "title" -> inTitle = false
                }
            }
            event = xpp.next()
        }
        epg.finalize()
        return epg
    }

    /** XMLTV: 20260615120000 +0000  →  миллисекунды UTC. */
    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            val cleaned = if (s.length >= 14 && !s.contains(' ')) "$s +0000" else s
            TIME_FMT.parse(cleaned)?.time ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    fun formatProgramme(p: Programme, locale: Locale = Locale.getDefault()): String {
        val fmt = SimpleDateFormat("HH:mm", locale)
        val start = fmt.format(Date(p.start))
        val stop = fmt.format(Date(p.stop))
        val title = if (p.title.isBlank()) "—" else p.title
        return "$start–$stop  $title"
    }
}
