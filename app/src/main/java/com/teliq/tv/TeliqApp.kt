package com.teliq.tv

import android.app.Application
import com.teliq.tv.data.Channel
import com.teliq.tv.data.Epg
import com.teliq.tv.data.Settings
import org.videolan.libvlc.LibVLC

/** Глобальное состояние: каналы, EPG, настройки. */
class TeliqApp : Application() {

    lateinit var settings: Settings
        private set

    @Volatile var channels: List<Channel> = emptyList()
    @Volatile var epg: Epg = Epg()

    /** Экран «Кино» просит главный экран открыть боковое меню при возврате (стрелка влево с левого края). */
    @Volatile var openMenuOnReturn = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
    }

    private var vlc: LibVLC? = null
    private var vlcKey = ""

    /** Параметры libVLC из настроек (буфер, аппаратное декодирование, User-Agent, деинтерлейсинг). */
    private fun vlcArgs(): ArrayList<String> {
        val mode = settings.bufferMode
        val args = arrayListOf(
            "--quiet",
            "--no-video-title-show",
            "--no-snapshot-preview",
            "--no-stats",
            "--no-osd",
            "--no-spu",
            "--no-sub-autodetect-file",
            "--no-audio-time-stretch",
            "--network-caching=${mode.ms}",
            "--live-caching=${mode.ms}",
            "--file-caching=1000",
            "--avcodec-hw=${settings.hwDecoding.arg}",
            "--http-user-agent=${settings.userAgent.ua}",
        )
        if (settings.deinterlace.isNotEmpty()) {
            args += "--deinterlace=1"
            args += "--deinterlace-mode=${settings.deinterlace}"
        }
        return args
    }

    /**
     * Один общий LibVLC на приложение. Создание экземпляра тяжёлое (грузит библиотеки и модули, на слабой
     * приставке — секунда и больше на главном потоке), поэтому он создаётся заранее в фоне при запуске
     * (см. MainActivity) и переиспользуется всеми плеерами; пересоздаётся только при смене настроек.
     */
    @Synchronized
    fun sharedLibVlc(): LibVLC {
        val args = vlcArgs()
        val key = args.joinToString("\u0001")
        vlc?.let { if (key == vlcKey) return it }
        val old = vlc
        val fresh = LibVLC(this, args)
        vlc = fresh
        vlcKey = key
        // старый экземпляр мог ещё использоваться закрывающимся плеером — освобождаем с запасом, в фоне
        if (old != null) Thread {
            runCatching { Thread.sleep(10_000) }
            runCatching { old.release() }
        }.start()
        return fresh
    }

    companion object {
        lateinit var instance: TeliqApp
            private set
    }
}
