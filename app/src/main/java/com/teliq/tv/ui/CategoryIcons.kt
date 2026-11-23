package com.teliq.tv.ui

/**
 * Эмодзи для категорий: флаги стран и тематические значки (⚽ для спорта и т.д.).
 * Только для отображения — сами названия категорий не меняются.
 */
object CategoryIcons {

    private val COUNTRIES: List<Pair<List<String>, String>> = listOf(
        listOf("portugal", "португ") to "PT",
        listOf("azerbaijan", "азербайдж", "azərbaycan") to "AZ",
        listOf("türkiye", "turkiye", "turkey", "турц", "turk") to "TR",
        listOf("russia", "росси", "русск") to "RU",
        listOf("ukrain", "украин") to "UA",
        listOf("germany", "deutsch", "герман") to "DE",
        listOf("france", "франц") to "FR",
        listOf("italy", "итал") to "IT",
        listOf("spain", "españa", "испан") to "ES",
        listOf("united kingdom", "britain", "england", "британ", "англи") to "GB",
        listOf("usa", "united states", "америк", "сша") to "US",
        listOf("armenia", "армен") to "AM",
        listOf("georgia", "грузи") to "GE",
        listOf("kazakh", "казах") to "KZ",
        listOf("poland", "польш") to "PL",
        listOf("romania", "румын") to "RO",
        listOf("bulgaria", "болгар") to "BG",
        listOf("netherlands", "голланд", "нидерл") to "NL",
        listOf("austria", "австри") to "AT",
        listOf("hungary", "венгр") to "HU",
        listOf("lithuania", "литв") to "LT",
        listOf("estonia", "эстон") to "EE",
        listOf("belarus", "беларус") to "BY",
        listOf("uzbek", "узбек") to "UZ",
        listOf("israel", "израил") to "IL",
    )

    private val THEMES: List<Pair<List<String>, String>> = listOf(
        listOf("sport", "спорт") to "⚽",
        listOf("кино", "film", "movie", "cinema") to "🎬",
        listOf("музык", "music") to "🎵",
        listOf("дет", "kid", "cartoon", "мульт") to "🧸",
        listOf("новост", "news") to "📰",
        listOf("познав", "doc", "наук") to "📖",
        listOf("развлек", "entert", "шоу") to "🎭",
        listOf("общ", "general") to "📺",
        listOf("рели", "relig") to "🕊️",
    )

    private val LEADING_NUMBER = Regex("^\\s*\\d+[.)]\\s*")

    private fun flag(code: String): String {
        val a = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val b = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(a) + String(b)
    }

    /** «⚽  1. Sport/Спорт», «🇵🇹  2. Portugal», «Все» → «📺  Все». */
    fun decorate(name: String): String {
        if (name.trim().startsWith("★") || name.trim().startsWith("🕒")) return name
        val n = name.replace(LEADING_NUMBER, "").trim().lowercase()
        if (n == "все" || n == "all") return "📺  $name"
        for ((keys, code) in COUNTRIES) if (keys.any { it in n }) return "${flag(code)}  $name"
        for ((keys, glyph) in THEMES) if (keys.any { it in n }) return "$glyph  $name"
        return name
    }
}
