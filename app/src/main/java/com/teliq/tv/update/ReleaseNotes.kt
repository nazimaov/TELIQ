package com.teliq.tv.update

/**
 * Текст «Что нового» из описания релиза GitHub. Описание двуязычное: разделы `## ru` и `## en`; показывается
 * раздел языка интерфейса (иначе английский, иначе первый). Разметка упрощается для экрана ТВ: без `**`, `#`
 * и ссылки «Full Changelog», пункты списка — с «•».
 */
object ReleaseNotes {
    private val section = Regex("""^##\s*([a-zA-Z]{2})\s*$""")

    fun select(raw: String, language: String): String {
        val sections = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        val plain = StringBuilder()
        for (line in raw.lines()) {
            val match = section.matchEntire(line.trim())
            if (match != null) {
                current = StringBuilder().also { sections[match.groupValues[1].lowercase()] = it }
            } else {
                (current ?: plain).appendLine(line)
            }
        }
        val text = if (sections.isEmpty()) plain.toString()
        else (sections[language.lowercase()] ?: sections["en"] ?: sections.values.first()).toString()
        return clean(text)
    }

    private fun clean(text: String): String = text.lines()
        .filterNot { it.contains("Full Changelog", ignoreCase = true) || it.contains("What's Changed", ignoreCase = true) }
        .joinToString("\n") { line ->
            line.trim().trimStart('#').trim().replace("**", "").replace("`", "")
                .let { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
