package com.teliq.tv.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.teliq.tv.R
import com.teliq.tv.TeliqApp
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Плеер фильмов (VPortal): в отличие от эфира здесь есть пауза, перемотка и шкала времени.
 * OK при скрытой панели только показывает её, пауза — вторым нажатием; ←→ — перемотка 10 с (удержание — 30 с).
 * Позиция запоминается: при повторном открытии фильм продолжается с того же места.
 */
class VodPlayerActivity : FragmentActivity() {

    private lateinit var video: VLCVideoLayout
    private lateinit var overlay: View
    private lateinit var titleView: TextView
    private lateinit var timeCur: TextView
    private lateinit var timeTotal: TextView
    private lateinit var progress: ProgressBar
    private lateinit var hud: TextView

    private var lib: LibVLC? = null
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var url = ""
    private var videoId = 0
    private var resumeAt = 0L
    private var resumed = false
    private var lastSaved = 0L

    private val hideOverlay = Runnable { if (player?.isPlaying == true) overlay.visibility = View.GONE }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            renderProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val prefs by lazy { getSharedPreferences("vod_positions", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod_player)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        video = findViewById(R.id.vod_video)
        overlay = findViewById(R.id.vod_overlay)
        titleView = findViewById(R.id.vod_player_title)
        timeCur = findViewById(R.id.vod_time_cur)
        timeTotal = findViewById(R.id.vod_time_total)
        progress = findViewById(R.id.vod_progress)
        hud = findViewById(R.id.vod_hud)

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        videoId = intent.getIntExtra(EXTRA_ID, 0)
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        resumeAt = prefs.getLong("pos_$videoId", 0L)
        if (url.isEmpty()) finish()
    }

    override fun onStart() {
        super.onStart()
        if (url.isEmpty()) return
        try {
            val l = TeliqApp.instance.sharedLibVlc()
            lib = l
            val mp = MediaPlayer(l)
            player = mp
            mp.attachViews(video, null, false, false)
            mp.setEventListener { ev ->
                when (ev.type) {
                    MediaPlayer.Event.Playing -> handler.post { onPlaying() }
                    MediaPlayer.Event.EndReached -> handler.post { prefs.edit().remove("pos_$videoId").apply(); finish() }
                    MediaPlayer.Event.EncounteredError -> handler.post {
                        Toast.makeText(this, "Не удалось воспроизвести", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            val media = Media(l, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=3000")   // фильм не эфир: буфер побольше — меньше остановок
            // Встроенный HLS-демуксер libVLC 3.6 падает (assert в FakeESOut) на потоках с отрицательными метками времени;
            // демуксер libavformat тот же поток читает без аварии
            media.addOption(":demux=avformat")
            mp.media = media
            media.release()
            mp.play()
            showOverlay()
            handler.post(tick)
        } catch (t: Throwable) {
            Log.e(TAG, "player init failed", t)
            Toast.makeText(this, "Ошибка плеера: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun onPlaying() {
        // Продолжить с запомненного места — один раз, когда поток реально пошёл
        if (!resumed) {
            resumed = true
            val mp = player ?: return
            if (resumeAt > 15_000) {
                mp.time = resumeAt
                showHud("Продолжаем с ${fmt(resumeAt)}")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        handler.removeCallbacksAndMessages(null)
        val mp = player ?: return
        player = null
        lib = null
        try {
            mp.setEventListener(null)
            mp.detachViews()
        } catch (_: Throwable) {}
        // release() ждёт сетевые потоки VLC: на главном потоке это зависание при выходе
        Thread({ try { mp.release() } catch (_: Throwable) {} }, "vlc-vod-release").start()
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------------------------------------------------------- пульт

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event?.repeatCount != 0) return true
                // OK при скрытой панели только показывает её; пауза — вторым нажатием
                if (overlay.visibility != View.VISIBLE) showOverlay() else togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event?.repeatCount == 0) togglePause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(if ((event?.repeatCount ?: 0) > 0) -30_000 else -10_000); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(if ((event?.repeatCount ?: 0) > 0) 30_000 else 10_000); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showOverlay(); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun togglePause() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            showHud("⏸")
            showOverlay(keep = true)
        } else {
            mp.play()
            showHud("▶")
            showOverlay()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val mp = player ?: return
        val len = mp.length
        if (len <= 0) return
        val target = (mp.time + deltaMs).coerceIn(0L, len - 1000)
        mp.time = target
        showHud(if (deltaMs > 0) "▶▶ ${fmt(target)}" else "◀◀ ${fmt(target)}")
        showOverlay()
        renderProgress()
    }

    private fun showOverlay(keep: Boolean = false) {
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        if (!keep) handler.postDelayed(hideOverlay, 4000)
    }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 1200)
    }

    private fun renderProgress() {
        val mp = player ?: return
        val len = mp.length
        val t = mp.time
        timeCur.text = fmt(t)
        timeTotal.text = if (len > 0) fmt(len) else ""
        progress.progress = if (len > 0) ((t * 1000) / len).toInt().coerceIn(0, 1000) else 0
        // запоминаем позицию раз в 10 секунд (если фильм не в самом начале и не у конца)
        val now = System.currentTimeMillis()
        if (now - lastSaved > 10_000) { lastSaved = now; savePosition() }
    }

    private fun savePosition() {
        val mp = player ?: return
        val len = mp.length
        val t = mp.time
        if (len <= 0 || videoId == 0) return
        if (t > 20_000 && t < len - 60_000) prefs.edit().putLong("pos_$videoId", t).apply()
        else if (t >= len - 60_000) prefs.edit().remove("pos_$videoId").apply()
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "id"
        private const val TAG = "TeliqVod"
    }
}
