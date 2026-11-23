package com.teliq.tv.data

/** Один IPTV-канал. */
data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val group: String = "Другие",
    val logoUrl: String = "",
    val tvgId: String = "",
    var currentProgram: String = "",
    var nextProgram: String = "",
    var isFavorite: Boolean = false,
    /** Начало и конец текущей передачи (мс, UTC) — 0, если расписания нет. */
    var progStart: Long = 0L,
    var progStop: Long = 0L,
) {
    /**
     * Постоянный ключ канала: адрес потока без параметра `token`. Провайдеры меняют токен при каждой загрузке
     * плейлиста, поэтому избранное, история, блокировки и порядок хранятся по ключу, а не по полному адресу
     * (иначе после обновления плейлиста они «пропадали»).
     */
    val key: String = channelKey(url)

    /** Короткое обозначение для логотипа-заглушки (как в десктопной версии). */
    val abbreviation: String
        get() {
            val words = name.split(" ").filter { it.isNotBlank() }
            return when {
                words.isEmpty() -> "?"
                words.size == 1 -> words[0].take(3).uppercase()
                else -> words.take(3).joinToString("") { it.take(1) }.uppercase()
            }
        }
}

private val TOKEN_PARAM = Regex("""([?&])token=[^&]*&?""", RegexOption.IGNORE_CASE)

/** Адрес без параметра token: `…/mono.m3u8?token=abc` → `…/mono.m3u8`; остальные параметры сохраняются. */
fun channelKey(url: String): String {
    if (!url.contains("token=", ignoreCase = true)) return url
    var out = TOKEN_PARAM.replace(url) { m -> if (m.value.endsWith("&")) m.groupValues[1] else "" }
    out = out.trimEnd('?', '&')
    return out
}
