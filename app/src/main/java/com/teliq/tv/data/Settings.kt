package com.teliq.tv.data

import android.content.Context
import android.content.SharedPreferences

/** Простая обёртка над SharedPreferences для настроек TELIQ. */
class Settings(ctx: Context) {

    private val sp: SharedPreferences = ctx.getSharedPreferences("teliq", Context.MODE_PRIVATE)

    enum class BufferMode(val ms: Int, val label: String) {
        LOW_LATENCY(500, "Реальное время"),
        BALANCED(1500, "Сбалансированный"),
        STABLE(3000, "Стабильный"),
    }

    enum class AspectRatio(val label: String, val vlc: String?) {
        AUTO("Авто (по видео)", null),
        R_16_9("16:9", "16:9"),
        R_4_3("4:3", "4:3"),
        R_1_1("Квадрат 1:1", "1:1"),
        R_2_35("Кинотеатр 2.35:1", "235:100"),
        FILL("Растянуть на экран", "fill"),
        CROP("Обрезать (заполнить)", "crop"),
    }

    enum class HwDecoding(val label: String, val arg: String) {
        AUTO("Авто", "any"),
        FULL("Полное", "full"),
        DISABLED("Программное", "none"),
    }

    enum class UserAgentPreset(val label: String, val ua: String) {
        VLC("VLC", "VLC/3.0.20 LibVLC/3.0.20"),
        KODI("Kodi", "Kodi/20.0 (Android 13) Mediacenter"),
        PERFECT("Perfect Player", "Perfect Player IPTV/0.0 (Android)"),
        BROWSER("Браузер (Android)", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0"),
        EXOPLAYER("ExoPlayer", "ExoPlayer"),
    }

    var lastPlaylistUrl: String
        get() = sp.getString(KEY_PLAYLIST_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_PLAYLIST_URL, v).apply()

    var lastEpgUrl: String
        get() = sp.getString(KEY_EPG_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_EPG_URL, v).apply()

    var lastChannelNumber: Int
        get() = sp.getInt(KEY_LAST_CHANNEL, 0)
        set(v) = sp.edit().putInt(KEY_LAST_CHANNEL, v).apply()

    var bufferMode: BufferMode
        get() {
            val name = sp.getString(KEY_BUFFER_MODE, BufferMode.BALANCED.name)
            return runCatching { BufferMode.valueOf(name!!) }.getOrDefault(BufferMode.BALANCED)
        }
        set(v) = sp.edit().putString(KEY_BUFFER_MODE, v.name).apply()

    /** Сохранённый пользовательский порядок каналов — список URL. */
    var channelOrder: List<String>
        get() = sp.getString(KEY_CHANNEL_ORDER, "")
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.map(::channelKey)
            ?: emptyList()
        set(v) = sp.edit().putString(KEY_CHANNEL_ORDER, v.map(::channelKey).joinToString("\n")).apply()

    /** URL заблокированных (скрытых) каналов. */
    var blocked: Set<String>
        get() = (sp.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()).map(::channelKey).toSet()
        set(v) = sp.edit().putStringSet(KEY_BLOCKED, v.map(::channelKey).toSet()).apply()

    fun clearChannelOrder() = sp.edit().remove(KEY_CHANNEL_ORDER).apply()

    /** URL избранных каналов. */
    var favorites: Set<String>
        get() = (sp.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).map(::channelKey).toSet()
        set(v) = sp.edit().putStringSet(KEY_FAVORITES, v.map(::channelKey).toSet()).apply()

    fun toggleFavorite(url: String) {
        val key = channelKey(url)
        val s = favorites.toMutableSet()
        if (!s.add(key)) s.remove(key)
        favorites = s
    }

    /** История просмотра — список URL, последний просмотренный первый. Максимум 15. */
    var history: List<String>
        get() = sp.getString(KEY_HISTORY, "")
            ?.split('\n')?.filter { it.isNotBlank() }?.map(::channelKey)?.distinct() ?: emptyList()
        set(v) = sp.edit().putString(KEY_HISTORY, v.map(::channelKey).joinToString("\n")).apply()

    fun pushHistory(url: String) {
        val key = channelKey(url)
        val cur = history.toMutableList()
        cur.remove(key)
        cur.add(0, key)
        history = cur.take(15)
    }

    /** Несколько URL плейлистов — построчно. */
    var playlistUrls: List<String>
        get() {
            val multi = sp.getString(KEY_PLAYLISTS, "")
                ?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            if (multi.isNotEmpty()) return multi
            // Бэкварды: если задан только один (старая версия) — вернём его
            val single = sp.getString(KEY_PLAYLIST_URL, "") ?: ""
            return if (single.isNotEmpty()) listOf(single) else emptyList()
        }
        set(v) = sp.edit().putString(KEY_PLAYLISTS, v.joinToString("\n")).apply()

    fun addPlaylistUrl(url: String) {
        val cur = playlistUrls.toMutableList()
        if (url !in cur) cur += url
        playlistUrls = cur
        if (lastPlaylistUrl.isEmpty()) lastPlaylistUrl = url
    }

    fun removePlaylistUrl(url: String) {
        playlistUrls = playlistUrls.filter { it != url }
        // старый одиночный ключ тоже чистим, иначе удалённая ссылка «воскреснет»
        if (sp.getString(KEY_PLAYLIST_URL, "") == url) sp.edit().remove(KEY_PLAYLIST_URL).apply()
        if (lastPlaylistUrl == url) lastPlaylistUrl = playlistUrls.firstOrNull() ?: ""
    }

    /** Заменяет ссылку (или путь к файлу) на месте — порядок плейлистов не меняется. */
    fun replacePlaylistUrl(old: String, new: String) {
        val cur = playlistUrls.toMutableList()
        val i = cur.indexOf(old)
        if (i < 0) cur += new else cur[i] = new
        playlistUrls = cur.distinct()
        if (sp.getString(KEY_PLAYLIST_URL, "") == old) sp.edit().remove(KEY_PLAYLIST_URL).apply()
        if (lastPlaylistUrl == old) lastPlaylistUrl = new
    }

    var aspectRatio: AspectRatio
        get() = runCatching { AspectRatio.valueOf(sp.getString(KEY_ASPECT, AspectRatio.AUTO.name)!!) }
            .getOrDefault(AspectRatio.AUTO)
        set(v) = sp.edit().putString(KEY_ASPECT, v.name).apply()

    var hwDecoding: HwDecoding
        get() = runCatching { HwDecoding.valueOf(sp.getString(KEY_HW, HwDecoding.AUTO.name)!!) }
            .getOrDefault(HwDecoding.AUTO)
        set(v) = sp.edit().putString(KEY_HW, v.name).apply()

    var userAgent: UserAgentPreset
        get() = runCatching { UserAgentPreset.valueOf(sp.getString(KEY_UA, UserAgentPreset.VLC.name)!!) }
            .getOrDefault(UserAgentPreset.VLC)
        set(v) = sp.edit().putString(KEY_UA, v.name).apply()

    /** Смещение EPG в часах от системного времени (для корректировки часовых поясов). */
    var epgTimeOffset: Int
        get() = sp.getInt(KEY_EPG_OFFSET, 0)
        set(v) = sp.edit().putInt(KEY_EPG_OFFSET, v).apply()

    /** Деинтерлейсинг — «yadif», «bob», «linear», «mean», «x», «" "» (выкл). */
    var deinterlace: String
        get() = sp.getString(KEY_DEINTERLACE, "") ?: ""
        set(v) = sp.edit().putString(KEY_DEINTERLACE, v).apply()

    /** URL канала, для которого баннер «Продолжить просмотр» закрыт крестиком. */
    var heroDismissedUrl: String
        get() = channelKey(sp.getString(KEY_HERO_DISMISSED, "") ?: "")
        set(v) = sp.edit().putString(KEY_HERO_DISMISSED, channelKey(v)).apply()

    var showChannelNumbers: Boolean
        get() = sp.getBoolean(KEY_SHOW_NUMBERS, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_NUMBERS, v).apply()

    companion object {
        private const val KEY_PLAYLIST_URL = "playlist_url"
        private const val KEY_EPG_URL = "epg_url"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_BUFFER_MODE = "buffer_mode"
        private const val KEY_CHANNEL_ORDER = "channel_order"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_BLOCKED = "blocked_channels"
        private const val KEY_HISTORY = "history"
        private const val KEY_PLAYLISTS = "playlist_urls"
        private const val KEY_ASPECT = "aspect_ratio"
        private const val KEY_HW = "hw_decoding"
        private const val KEY_UA = "user_agent"
        private const val KEY_EPG_OFFSET = "epg_offset"
        private const val KEY_DEINTERLACE = "deinterlace"
        private const val KEY_SHOW_NUMBERS = "show_numbers"
        private const val KEY_HERO_DISMISSED = "hero_dismissed_url"
    }
}
