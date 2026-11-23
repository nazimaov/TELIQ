package com.teliq.tv

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import coil.dispose
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.data.Channel
import com.teliq.tv.data.EpgUtil
import com.teliq.tv.ui.CategoryIcons
import com.teliq.tv.ui.ChannelActions
import com.teliq.tv.ui.ChannelMenu
import com.teliq.tv.ui.ScheduleActivity
import com.teliq.tv.ui.SettingsActivity
import com.teliq.tv.ui.SidebarChannelAdapter
import com.teliq.tv.ui.Ui
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Полноэкранный плеер на libVLC (HLS, HEVC, MPEG-TS, RTMP, UDP-мультикаст и т.п.).
 *
 *  * телефон / планшет: касание — показать карточку, вертикальный свайп справа — громкость,
 *    слева — яркость, горизонтальный свайп — смена канала; кнопки на карточке;
 *  * Android TV: стрелки ↑↓ — канал, ←→ — громкость, OK — список каналов.
 */
class PlayerActivity : FragmentActivity() {

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var hud: TextView
    private lateinit var infoCard: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoProgram: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoTimeRow: View
    private lateinit var infoTStart: TextView
    private lateinit var infoTEnd: TextView
    private lateinit var infoTiming: TextView
    private lateinit var btnFav: ImageButton

    // Карточка играющего канала под окошком плеера (пока открыт список каналов, широкий экран)
    private lateinit var miniCard: View
    private lateinit var miniLogo: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniProgram: TextView
    private lateinit var miniFav: ImageView
    private lateinit var miniTimeRow: View
    private lateinit var miniProgress: ProgressBar
    private lateinit var miniTStart: TextView
    private lateinit var miniTEnd: TextView
    private lateinit var miniNext: TextView
    private lateinit var miniFavBox: LinearLayout
    private lateinit var miniFavTitle: TextView
    private lateinit var miniFavRow: LinearLayout

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    private var channels: List<Channel> = emptyList()
    private var currentIndex: Int = 0

    // Список каналов
    private lateinit var sidebarRoot: View
    private lateinit var sidebar: LinearLayout
    private lateinit var sidebarCategories: LinearLayout
    private lateinit var sidebarList: RecyclerView
    private lateinit var sidebarTitle: TextView
    private lateinit var sidebarAdapter: SidebarChannelAdapter
    private var sidebarCategoriesList: List<String> = emptyList()
    private var sidebarActiveCategory: String = "Все"

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val isTv by lazy { Ui.isTv(this) }

    private val startStreamRunnable = Runnable { startStream() }
    private val miniTick = object : Runnable {
        override fun run() {
            if (miniCard.visibility == View.VISIBLE && channels.isNotEmpty()) {
                renderMini(channels[currentIndex])
                handler.postDelayed(this, 15_000)
            }
        }
    }
    private val hideHudRunnable = Runnable { hud.visibility = View.GONE }
    private val hideInfoRunnable = Runnable { hideInfo() }
    private val infoTick = object : Runnable {
        override fun run() {
            if (infoCard.visibility == View.VISIBLE && channels.isNotEmpty()) {
                renderTiming(channels[currentIndex])
                handler.postDelayed(this, 1000)
            }
        }
    }

    // жесты (громкость / яркость)
    private var gestureVolume = 0f
    private var gestureBrightness = 0.5f

    // ---------------------------------------------------------------- жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_player)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            bindViews()

            val app = TeliqApp.instance
            channels = app.channels
            if (channels.isEmpty()) {
                Toast.makeText(this, "Список каналов пуст — загрузите плейлист", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val number = intent.getIntExtra(EXTRA_CHANNEL_NUMBER, 1)
            currentIndex = channels.indexOfFirst { it.number == number }.coerceAtLeast(0)
        } catch (t: Throwable) {
            Log.e(TAG, "PlayerActivity.onCreate failed", t)
            Toast.makeText(this, "Ошибка: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun bindViews() {
        videoLayout = findViewById(R.id.video_layout)
        hud = findViewById(R.id.hud)
        infoCard = findViewById(R.id.info_card)
        infoLogo = findViewById(R.id.info_logo)
        infoTitle = findViewById(R.id.info_title)
        infoProgram = findViewById(R.id.info_program)
        infoProgress = findViewById(R.id.info_progress)
        infoTimeRow = findViewById(R.id.info_time_row)
        infoTStart = findViewById(R.id.info_t_start)
        infoTEnd = findViewById(R.id.info_t_end)
        infoTiming = findViewById(R.id.info_timing)
        btnFav = findViewById(R.id.btn_fav)
        miniCard = findViewById(R.id.mini_card)
        miniLogo = findViewById(R.id.mini_logo)
        miniTitle = findViewById(R.id.mini_title)
        miniProgram = findViewById(R.id.mini_program)
        miniFav = findViewById(R.id.mini_fav)
        miniTimeRow = findViewById(R.id.mini_time_row)
        miniProgress = findViewById(R.id.mini_progress)
        miniTStart = findViewById(R.id.mini_t_start)
        miniTEnd = findViewById(R.id.mini_t_end)
        miniNext = findViewById(R.id.mini_next)
        miniFavBox = findViewById(R.id.mini_fav_box)
        miniFavTitle = findViewById(R.id.mini_fav_title)
        miniFavRow = findViewById(R.id.mini_fav_row)
        miniFav.setOnClickListener { if (channels.isNotEmpty()) toggleFavorite(channels[currentIndex]) }

        sidebarRoot = findViewById(R.id.sidebar_root)
        sidebar = findViewById(R.id.sidebar)
        sidebarCategories = findViewById(R.id.sidebar_categories)
        sidebarList = findViewById(R.id.sidebar_list)
        sidebarTitle = findViewById(R.id.sidebar_title)
        sidebarList.layoutManager = LinearLayoutManager(this)
        sidebarAdapter = SidebarChannelAdapter(
            items = emptyList(),
            onClick = { ch -> switchToChannel(ch); hideSidebar() },
            onToggleFav = { ch -> toggleFavorite(ch) },
            onMenu = { ch -> showChannelMenu(ch) },
        )
        sidebarList.adapter = sidebarAdapter
        findViewById<TextView>(R.id.sidebar_hint).visibility = if (isTv) View.VISIBLE else View.GONE

        findViewById<View>(R.id.sidebar_scrim).setOnClickListener { hideSidebar() }
        // пультом по шестерёнке не ходим (она ловила фокус при движении вверх), на касание работает как раньше
        if (isTv) findViewById<ImageButton>(R.id.btn_sidebar_settings).isFocusable = false
        findViewById<ImageButton>(R.id.btn_sidebar_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { switchBy(-1) }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { switchBy(+1) }
        findViewById<ImageButton>(R.id.btn_list).setOnClickListener { showSidebar() }
        findViewById<ImageButton>(R.id.btn_close_player).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_schedule).setOnClickListener {
            if (channels.isNotEmpty()) {
                startActivity(
                    Intent(this, ScheduleActivity::class.java)
                        .putExtra(ScheduleActivity.EXTRA_CHANNEL_URL, channels[currentIndex].url),
                )
            }
        }
        btnFav.setOnClickListener { if (channels.isNotEmpty()) toggleFavorite(channels[currentIndex]) }

        videoLayout.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }
    }

    override fun onStart() {
        super.onStart()
        try {
            initPlayer()
            playCurrent()
        } catch (t: Throwable) {
            Log.e(TAG, "Player init failed", t)
            Toast.makeText(this, "Ошибка плеера: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (sidebarRoot.visibility == View.VISIBLE) { applySidebarWidth(); layoutMiniPlayer(true) }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(startStreamRunnable)
        stopPreview()
        val mp = mediaPlayer ?: return
        mediaPlayer = null
        libVlc = null   // общий LibVLC живёт в TeliqApp и здесь не освобождается
        try {
            mp.setEventListener(null)
            mp.detachViews()
        } catch (_: Throwable) {}
        // release() останавливает поток и ждёт сетевые потоки VLC — на главном потоке это зависание при выходе
        // из плеера (на слабой приставке — до секунды и больше), поэтому освобождаем в фоне
        Thread({ try { mp.release() } catch (_: Throwable) {} }, "vlc-release").start()
    }

    // ---------------------------------------------------------------- плеер

    private fun initPlayer() {
        val settings = TeliqApp.instance.settings
        val lib = TeliqApp.instance.sharedLibVlc()
        libVlc = lib
        mediaPlayer = MediaPlayer(lib).also {
            it.attachViews(videoLayout, null, false, false)
            it.setEventListener(playerEventListener)
            applyAspectRatio(it, settings.aspectRatio)
        }
    }

    private fun applyAspectRatio(player: MediaPlayer, mode: com.teliq.tv.data.Settings.AspectRatio) {
        when (mode) {
            com.teliq.tv.data.Settings.AspectRatio.AUTO -> {
                player.aspectRatio = null
                player.scale = 0f
            }
            com.teliq.tv.data.Settings.AspectRatio.FILL -> {
                player.aspectRatio = null
                player.scale = 1f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
            }
            com.teliq.tv.data.Settings.AspectRatio.CROP -> {
                player.aspectRatio = null
                player.scale = 1f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
            else -> {
                player.aspectRatio = mode.vlc
                player.scale = 0f
            }
        }
    }

    /**
     * Показать текущий канал: карточка с названием — сразу, сам поток — [immediate] или через паузу
     * [SWITCH_DEBOUNCE_MS]. При быстром листании ↑↓ поток запускается один раз на канале, где остановились,
     * а не на каждом проскочённом: раньше каждое нажатие останавливало и заново открывало поток — интерфейс
     * зависал, пока плеер «догонял» нажатия.
     */
    private fun playCurrent(immediate: Boolean = true) {
        if (channels.isEmpty()) { finish(); return }
        val ch = channels[currentIndex]
        ch.isFavorite = ch.key in TeliqApp.instance.settings.favorites
        renderInfo(ch)
        showInfoTemporarily()
        handler.removeCallbacks(startStreamRunnable)
        if (immediate) startStream() else handler.postDelayed(startStreamRunnable, SWITCH_DEBOUNCE_MS)
    }

    private fun startStream() {
        if (channels.isEmpty()) return
        val ch = channels[currentIndex]
        TeliqApp.instance.settings.lastChannelNumber = ch.number
        TeliqApp.instance.settings.pushHistory(ch.url)
        Log.i(TAG, "Playing #${ch.number} ${ch.name} → ${ch.url}")
        try {
            val player = mediaPlayer ?: return
            val lib = libVlc ?: return
            // Без player.stop(): оно блокирует главный поток до остановки потока; смена media сама перезапускает воспроизведение
            val media = Media(lib, Uri.parse(ch.url))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=${TeliqApp.instance.settings.bufferMode.ms}")
            media.addOption(":live-caching=${TeliqApp.instance.settings.bufferMode.ms}")
            player.media = media
            media.release()
            player.play()
        } catch (t: Throwable) {
            Log.e(TAG, "startStream failed for ${ch.url}", t)
            Toast.makeText(this, "Не открыть поток: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchBy(delta: Int) {
        if (channels.isEmpty()) return
        currentIndex = ((currentIndex + delta) % channels.size + channels.size) % channels.size
        playCurrent(immediate = false)
    }

    private fun switchToChannel(ch: Channel) {
        val idx = channels.indexOfFirst { it.url == ch.url }
        if (idx >= 0) {
            currentIndex = idx
            playCurrent()
        }
    }

    private val playerEventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.EncounteredError -> {
                Log.e(TAG, "VLC encountered error")
                runOnUiThread { showHud("⚠ Поток недоступен", 4000L) }
            }
            MediaPlayer.Event.EndReached -> {
                Log.i(TAG, "Stream ended — retrying")
                mediaPlayer?.play()
            }
        }
    }

    // ---------------------------------------------------------------- карточка канала

    private fun renderInfo(ch: Channel) {
        infoTitle.text = ch.name
        infoProgram.text = ch.currentProgram.ifEmpty { ch.group }
        Ui.loadLogo(infoLogo, ch)
        renderFavIcon(ch)
        renderTiming(ch)
        renderMini(ch)
    }

    /** Карточка под окошком плеера: логотип, название, передача, шкала, «Далее», ★ (как в ПК-версии). */
    private fun renderMini(playing: Channel) {
        if (miniCard.visibility != View.VISIBLE) return
        val ch = playing
        val now = System.currentTimeMillis()
        if (ch.progStop in 1 until now) EpgUtil.apply(ch, TeliqApp.instance.epg, now)
        miniTitle.text = ch.name
        miniProgram.text = ch.currentProgram.ifEmpty { ch.group }
        Ui.loadLogo(miniLogo, ch)
        miniFav.setImageResource(if (ch.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border)
        miniFav.imageTintList = ColorStateList.valueOf(
            getColor(if (ch.isFavorite) R.color.star_gold else R.color.text_primary),
        )
        val has = ch.progStart > 0 && ch.progStop > ch.progStart
        miniTimeRow.visibility = if (has) View.VISIBLE else View.GONE
        if (has) {
            miniProgress.progress = (((now - ch.progStart) * 1000) / (ch.progStop - ch.progStart)).toInt().coerceIn(0, 1000)
            miniTStart.text = timeFmt.format(Date(ch.progStart))
            miniTEnd.text = timeFmt.format(Date(ch.progStop))
        }
        miniNext.visibility = if (ch.nextProgram.isEmpty()) View.GONE else View.VISIBLE
        miniNext.text = if (ch.nextProgram.isEmpty()) "" else "Далее: ${ch.nextProgram}"
        renderMiniFavorites()
    }

    private class FavCell(
        val view: LinearLayout,
        val frame: android.widget.FrameLayout,
        val image: ImageView,
        val program: TextView,
        val ch: Channel,
    )

    private var favCells: List<FavCell> = emptyList()
    private var favKey = ""

    /** В ячейке — последний снятый кадр канала, а пока его нет — логотип. */
    private fun showFrameOrLogo(cell: FavCell) {
        val snap = previewCache.get(cell.ch.url)
        cell.image.dispose()
        if (snap != null) {
            cell.image.background = null
            cell.image.setPadding(0, 0, 0, 0)
            cell.image.scaleType = ImageView.ScaleType.CENTER_CROP
            cell.image.setImageBitmap(snap.bmp)
        } else {
            val pad = Ui.dp(this, 8)
            cell.image.background = ContextCompat.getDrawable(this, R.drawable.logo_bg)
            cell.image.setPadding(pad, pad, pad, pad)
            cell.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            Ui.loadLogo(cell.image, cell.ch)
        }
    }

    private fun buildFavCell(c: Channel): FavCell {
        fun d(n: Int) = Ui.dp(this, n)
        val frame = android.widget.FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.snap_bg)
            clipToOutline = true
        }
        val image = ImageView(this)
        frame.addView(image, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(TextView(this).apply {
            text = "${c.number}. ${c.name}"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tile_badge_size) * 1.15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = d(144)
            setBackgroundColor(0x99000000.toInt())
            setPadding(d(5), d(2), d(5), d(2))
        }, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.START))
        val program = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tile_prog_size))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.tile_bg)
            setPadding(d(8), d(8), d(8), d(8))
            isFocusable = true
            isClickable = true
            setOnClickListener { switchToChannel(c); hideSidebar() }
        }
        view.addView(frame, LinearLayout.LayoutParams(d(144), d(81)))
        view.addView(program, LinearLayout.LayoutParams(d(144), android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(4) })
        val cell = FavCell(view, frame, image, program, c)
        view.setOnFocusChangeListener { _, has ->
            if (has) onFavFocus(cell) else handler.post { if (!miniFavRow.hasFocus()) stopPreview() }
        }
        showFrameOrLogo(cell)
        return cell
    }

    /**
     * Ряд «Избранное» под карточкой (как в ПК-версии): в каждой ячейке — кадр (или логотип) канала и что на нём идёт
     * сейчас. Пультом: → из списка — в ряд, ←→ — между ячейками, OK — переключиться; на ячейке под выделением
     * кадры обновляются (мини-просмотр). Ячейки не пересобираются, пока набор избранных не изменился (иначе слетит фокус).
     */
    private fun renderMiniFavorites() {
        val favs = TeliqApp.instance.settings.favorites
        val all = channels.filter { it.key in favs }
        val list = if (miniCard.visibility == View.VISIBLE) all.take(12) else emptyList()
        if (list.isEmpty()) { miniFavBox.visibility = View.GONE; favCells = emptyList(); favKey = ""; return }
        miniFavTitle.text = "★ Избранное (${all.size})"
        val key = list.joinToString("|") { it.url }
        if (key != favKey) {
            favKey = key
            miniFavRow.removeAllViews()
            favCells = list.map { buildFavCell(it) }
            favCells.forEach { cell ->
                miniFavRow.addView(cell.view, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(this@PlayerActivity, 8) })
            }
        }
        for (cell in favCells) cell.program.text = cell.ch.currentProgram.ifEmpty { cell.ch.group }
        // Ставим ниже карточки (её высота известна после раскладки); не помещается — не показываем
        if (miniFavBox.visibility == View.GONE) miniFavBox.visibility = View.INVISIBLE
        miniCard.post {
            val cardLp = miniCard.layoutParams as android.widget.FrameLayout.LayoutParams
            val top = cardLp.topMargin + miniCard.height + Ui.dp(this, 12)
            if (resources.displayMetrics.heightPixels - top < Ui.dp(this, 150) || miniCard.visibility != View.VISIBLE) {
                miniFavBox.visibility = View.GONE
            } else {
                miniFavBox.layoutParams = android.widget.FrameLayout.LayoutParams(cardLp.width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    setMargins(0, top, cardLp.rightMargin, 0)
                }
                miniFavBox.visibility = View.VISIBLE
            }
        }
    }

    private fun renderFavIcon(ch: Channel) {
        btnFav.setImageResource(if (ch.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border)
        btnFav.imageTintList = ColorStateList.valueOf(
            getColor(if (ch.isFavorite) R.color.star_gold else R.color.text_primary),
        )
    }

    /** Шкала передачи: время начала и конца по краям, «Осталось …» под ней. */
    private fun renderTiming(ch: Channel) {
        val now = System.currentTimeMillis()
        if (ch.progStop in 1 until now) {
            EpgUtil.apply(ch, TeliqApp.instance.epg, now)   // передача закончилась — берём следующую
            infoProgram.text = ch.currentProgram.ifEmpty { ch.group }
        }
        val has = ch.progStart > 0 && ch.progStop > ch.progStart
        infoTimeRow.visibility = if (has) View.VISIBLE else View.GONE
        infoTiming.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) return
        val total = ch.progStop - ch.progStart
        infoProgress.progress = (((now - ch.progStart) * 1000) / total).toInt().coerceIn(0, 1000)
        infoTStart.text = timeFmt.format(Date(ch.progStart))
        infoTEnd.text = timeFmt.format(Date(ch.progStop))
        infoTiming.text = "Осталось ${EpgUtil.fmtDuration(ch.progStop - now)}   •   " +
            "Прошло ${EpgUtil.fmtDuration(now - ch.progStart)}" +
            (if (ch.nextProgram.isNotEmpty()) "   •   Далее: ${ch.nextProgram}" else "")
    }

    private fun showInfoTemporarily(ms: Long = 5000L) {
        if (isInPictureInPictureMode) return
        infoCard.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfoRunnable)
        handler.removeCallbacks(infoTick)
        handler.post(infoTick)
        handler.postDelayed(hideInfoRunnable, ms)
    }

    private fun hideInfo() {
        infoCard.visibility = View.GONE
        handler.removeCallbacks(infoTick)
    }

    private fun toggleInfo() {
        if (infoCard.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideInfoRunnable)
            hideInfo()
        } else {
            showInfoTemporarily()
        }
    }

    private fun showHud(text: String, ms: Long = 1500L) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, ms)
    }

    private fun toggleFavorite(ch: Channel) {
        TeliqApp.instance.settings.toggleFavorite(ch.url)
        ch.isFavorite = ch.key in TeliqApp.instance.settings.favorites
        showHud(if (ch.isFavorite) "★ Добавлено в избранное" else "Удалено из избранного")
        if (channels.isNotEmpty() && channels[currentIndex].url == ch.url) renderFavIcon(ch)
        channels.getOrNull(currentIndex)?.let { renderMini(it) }
        sidebarAdapter.refreshUrl(ch.url)   // только эта строка: фокус и прокрутка списка не сбиваются
    }

    // ---------------------------------------------------------------- жесты и громкость

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                gestureVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                val b = window.attributes.screenBrightness
                gestureBrightness = if (b < 0f) 0.5f else b
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleInfo()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val start = e1 ?: return false
                if (abs(e2.y - start.y) < abs(e2.x - start.x)) return false   // горизонтальное — не здесь
                val h = videoLayout.height.coerceAtLeast(1).toFloat()
                if (start.x > videoLayout.width / 2f) {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    gestureVolume = (gestureVolume + dy / h * max * 1.4f).coerceIn(0f, max.toFloat())
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, gestureVolume.roundToInt(), 0)
                    showHud("🔊 ${(gestureVolume * 100 / max.coerceAtLeast(1)).roundToInt()}%")
                } else {
                    gestureBrightness = (gestureBrightness + dy / h * 1.2f).coerceIn(0.02f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = gestureBrightness }
                    showHud("☀ ${(gestureBrightness * 100).roundToInt()}%")
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > 1800 && abs(vx) > abs(vy) * 2) {
                    switchBy(if (vx < 0) +1 else -1)
                    return true
                }
                return false
            }
        })
    }

    private fun adjustVolume(delta: Int) {
        val dir = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        showHud("🔊 ${current * 100 / max.coerceAtLeast(1)}%")
    }

    // ---------------------------------------------------------------- пульт / клавиши

    /**
     * Пока список каналов закрыт, пульт управляет только плеером: OK открывает список, ↑↓ — канал, ←→ — громкость.
     * Иначе OK нажимал бы кнопку, на которой случайно оказался фокус (например, ★ в карточке канала).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv && !isSidebarVisible() && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.repeatCount == 0 || event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER) {
                        return onKeyDown(event.keyCode, event)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isSidebarVisible()) {
            // Фокус «в списке» — на элементе или на самом списке (после обновления данных)
            val f = currentFocus
            val focusInList = f != null && (f === sidebarList || isDescendantOf(f, sidebarList))
            val inFavs = f != null && f.parent === miniFavRow
            val inPills = f != null && f.parent === sidebarCategories
            val favsShown = miniFavBox.visibility == View.VISIBLE && favCells.isNotEmpty()
            return when (keyCode) {
                // Ряд «Избранное»: ←→ между ячейками (системой), с первой ячейки ← — назад в список, ↑ — в список, ↓ — никуда
                KeyEvent.KEYCODE_DPAD_LEFT -> when {
                    inFavs -> if (favCells.firstOrNull()?.view === f) { focusSidebarItem(sidebarCurrentIndex(), false); true } else super.onKeyDown(keyCode, event)
                    inPills -> if (sidebarCategories.getChildAt(0) === f) true else super.onKeyDown(keyCode, event)
                    focusInList || f == null -> { sidebarSwitchCategory(-1, wrap = favsShown); true }
                    else -> super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                    inFavs -> if (favCells.lastOrNull()?.view === f) true else super.onKeyDown(keyCode, event)
                    inPills -> if (sidebarCategories.getChildAt(sidebarCategories.childCount - 1) === f) true else super.onKeyDown(keyCode, event)
                    focusInList || f == null -> {
                        // есть избранное — → переходит в него (так же быстро, как раньше шёл на следующую категорию)
                        if (favsShown) favCells.first().view.requestFocus() else sidebarSwitchCategory(+1)
                        true
                    }
                    else -> super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (inFavs) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) focusSidebarItem(sidebarCurrentIndex(), false)
                    true
                } else if (inPills) {
                    // категории: ↓ — в список (на играющий канал, если он в этой категории), ↑ — выше некуда
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) focusSidebarItem(sidebarCurrentIndex(), true)
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && f != null && f.parent === sidebarList && sidebarList.getChildAdapterPosition(f) == 0) {
                    // ↑ с самого верха списка — на категории
                    focusActivePill()
                    true
                } else {
                    // фокус потерялся (список перестроился) — возвращаем его в список
                    if (f == null || f === sidebarList) { focusSidebarItem(sidebarCurrentIndex(), false); true }
                    else super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { hideSidebar(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showSidebar(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switchBy(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> { switchBy(+1); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_VOLUME_DOWN -> { adjustVolume(-1); showInfoTemporarily(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_VOLUME_UP -> { adjustVolume(+1); showInfoTemporarily(); true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ---------------------------------------------------------------- список каналов

    private fun isSidebarVisible() = sidebarRoot.visibility == View.VISIBLE

    private fun isDescendantOf(v: View, parent: View): Boolean {
        var cur: android.view.ViewParent? = v.parent
        while (cur != null) {
            if (cur === parent) return true
            cur = cur.parent
        }
        return false
    }

    /** Ширина панели: до 420dp, но не больше 92% экрана (телефон в портрете). */
    private fun applySidebarWidth() {
        val maxPx = resources.getDimensionPixelSize(R.dimen.sidebar_max_width)
        val w = minOf(maxPx, (resources.displayMetrics.widthPixels * 0.92f).toInt())
        sidebar.layoutParams = sidebar.layoutParams.apply { width = w }
    }

    private fun showSidebar() {
        if (channels.isEmpty()) return
        val groups = channels.map { it.group }.distinct().sorted()
        sidebarCategoriesList = listOf("Все") + groups
        sidebarActiveCategory = channels[currentIndex].group.ifEmpty { "Все" }
        if (sidebarActiveCategory !in sidebarCategoriesList) sidebarActiveCategory = "Все"
        applySidebarWidth()
        rebuildSidebarCategoryBar()
        applySidebarFilter(centerOnCurrent = true)
        hideInfo()
        sidebarRoot.visibility = View.VISIBLE
        sidebarRoot.bringToFront()
        layoutMiniPlayer(true)
    }

    private fun hideSidebar() {
        stopPreview()
        sidebarRoot.visibility = View.GONE
        layoutMiniPlayer(false)
    }

    /**
     * Как в ПК-версии: пока открыт список каналов, плеер уменьшается в «окошко» и продолжает играть.
     * Широкий экран — окошко справа от списка; узкий (телефон в портрете) — сверху, список под ним.
     */
    private fun layoutMiniPlayer(open: Boolean) {
        val lp = videoLayout.layoutParams as android.widget.FrameLayout.LayoutParams
        val sbLp = sidebar.layoutParams as android.widget.FrameLayout.LayoutParams
        val dm = resources.displayMetrics
        val m = Ui.dp(this, 10)
        if (!open) {
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = android.view.Gravity.NO_GRAVITY
            lp.setMargins(0, 0, 0, 0)
            sbLp.topMargin = m
        } else if (dm.widthPixels / dm.density >= 600f) {
            var vh = minOf((dm.widthPixels - sidebar.layoutParams.width - m * 3) * 9 / 16, dm.heightPixels - m * 2)
            // Есть избранное — под карточкой нужны ячейки с кадрами (≈ 160dp): окошко плеера чуть меньше
            val favs = TeliqApp.instance.settings.favorites
            if (channels.any { it.key in favs }) vh = minOf(vh, (dm.heightPixels - Ui.dp(this, 276)).coerceAtLeast(Ui.dp(this, 140)))
            val vw = vh * 16 / 9
            lp.width = vw
            lp.height = vh
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            lp.setMargins(0, m, m, 0)
            sbLp.topMargin = m
        } else {
            val vh = dm.widthPixels * 9 / 16
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = vh
            lp.gravity = android.view.Gravity.TOP
            lp.setMargins(0, 0, 0, 0)
            sbLp.topMargin = vh + m
        }
        videoLayout.layoutParams = lp
        sidebar.layoutParams = sbLp
        // Карточка канала — под окошком плеера, только на широком экране и если под окошком хватает места
        val wide = open && dm.widthPixels / dm.density >= 600f
        val cardTop = lp.topMargin + lp.height + Ui.dp(this, 3) * 2 + m
        if (wide && dm.heightPixels - cardTop >= Ui.dp(this, 150)) {
            miniCard.layoutParams = android.widget.FrameLayout.LayoutParams(lp.width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, cardTop, m, 0)
            }
            miniCard.visibility = View.VISIBLE
            handler.removeCallbacks(miniTick)
            channels.getOrNull(currentIndex)?.let { renderMini(it) }
            handler.postDelayed(miniTick, 15_000)
        } else {
            miniCard.visibility = View.GONE
            miniFavBox.visibility = View.GONE
            handler.removeCallbacks(miniTick)
        }
        videoLayout.isFocusable = !open   // пока открыт список, фокус пульта не должен уходить в окошко плеера
        val frame = findViewById<View>(R.id.mini_frame)
        if (open) {
            val b = Ui.dp(this, 3)
            frame.layoutParams = android.widget.FrameLayout.LayoutParams(lp.width + b * 2, lp.height + b * 2).apply {
                gravity = lp.gravity
                setMargins(lp.leftMargin - b, lp.topMargin - b, lp.rightMargin - b, 0)
            }
            frame.visibility = View.VISIBLE
        } else {
            frame.visibility = View.GONE
        }
    }

    private fun sidebarCurrentIndex(): Int =
        sidebarAdapter.indexOfUrl(channels[currentIndex].url).coerceAtLeast(0)

    /** Ставит фокус пульта на строку списка (после того как RecyclerView её создал). */
    private fun focusSidebarItem(pos: Int, scroll: Boolean = true) {
        if (!isTv) return
        if (scroll) sidebarList.scrollToPosition(pos)
        sidebarList.post {
            val v = sidebarList.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) v.requestFocus() else sidebarList.postDelayed({
                sidebarList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
            }, 80)
        }
    }

    /** Меню канала в списке (удержание OK): смотреть, избранное, переместить, заблокировать. */
    private fun showChannelMenu(ch: Channel) {
        val visible = if (sidebarActiveCategory == "Все") channels else channels.filter { it.group == sidebarActiveCategory }
        val idx = visible.indexOfFirst { it.url == ch.url }
        ChannelMenu.show(this, ch, canUp = idx > 0, canDown = idx in 0 until visible.size - 1) { action ->
            when (action) {
                ChannelMenu.Action.PLAY -> { switchToChannel(ch); hideSidebar() }
                ChannelMenu.Action.FAV -> toggleFavorite(ch)
                ChannelMenu.Action.UP, ChannelMenu.Action.DOWN -> {
                    if (ChannelActions.move(ch, if (action == ChannelMenu.Action.UP) -1 else 1, visible)) {
                        reloadChannelsKeepCurrent()
                        focusSidebarItem(sidebarAdapter.indexOfUrl(ch.url).coerceAtLeast(0))
                    }
                }
                ChannelMenu.Action.BLOCK -> {
                    val playingUrl = channels[currentIndex].url
                    val pos = sidebarAdapter.indexOfUrl(ch.url)
                    ChannelActions.block(ch)
                    Toast.makeText(this, "«${ch.name}» заблокирован. Вернуть — в Настройках", Toast.LENGTH_LONG).show()
                    if (ch.url == playingUrl) {
                        // заблокировали то, что играет: переходим на ближайший канал
                        val all = TeliqApp.instance.channels
                        if (all.isEmpty()) { finish(); return@show }
                        channels = all
                        currentIndex = currentIndex.coerceAtMost(all.size - 1)
                        playCurrent()
                    } else {
                        reloadChannelsKeepCurrent()
                    }
                    focusSidebarItem(pos.coerceAtLeast(0))
                }
            }
        }
    }

    /** После изменения порядка/блокировки: берём свежий список и находим играющий канал по URL. */
    private fun reloadChannelsKeepCurrent() {
        val playingUrl = channels[currentIndex].url
        channels = TeliqApp.instance.channels
        currentIndex = channels.indexOfFirst { it.url == playingUrl }.coerceAtLeast(0)
        applySidebarFilter(centerOnCurrent = false, focus = false)
    }

    private fun rebuildSidebarCategoryBar() {
        sidebarCategories.removeAllViews()
        val vPad = Ui.dp(this, if (isTv) 8 else 6)
        for (cat in sidebarCategoriesList) {
            val tv = TextView(this).apply {
                text = CategoryIcons.decorate(cat)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_small))
                setPadding(Ui.dp(this@PlayerActivity, 14), vPad, Ui.dp(this@PlayerActivity, 14), vPad)
                setTextColor(getColor(R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.cat_pill_bg)
                isFocusable = true    // пультом: ↑ с верха списка — на категории, ←→ — между ними, OK — выбрать
                isClickable = true
                maxLines = 1
                isActivated = (cat == sidebarActiveCategory)
                setOnClickListener {
                    sidebarActiveCategory = cat
                    val fromRemote = sidebarCategories.hasFocus()
                    rebuildSidebarCategoryBar()
                    // выбор пультом — остаёмся на категориях (можно листать дальше), касанием — как раньше
                    applySidebarFilter(centerOnCurrent = false, focus = !fromRemote)
                    if (fromRemote) sidebarCategories.post { focusActivePill() }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Ui.dp(this@PlayerActivity, 8) }
            sidebarCategories.addView(tv, lp)
            if (cat == sidebarActiveCategory) {
                tv.post { (sidebarCategories.parent as? android.widget.HorizontalScrollView)
                    ?.smoothScrollTo((tv.left - Ui.dp(this@PlayerActivity, 40)).coerceAtLeast(0), 0) }
            }
        }
    }

    /** [wrap] — по кругу (← нужна для листания категорий назад, когда → занята переходом в «Избранное»). */
    /** Фокус пульта — на выбранную категорию (панель категорий над списком). */
    private fun focusActivePill() {
        for (i in 0 until sidebarCategories.childCount) {
            val v = sidebarCategories.getChildAt(i)
            if (v.isActivated) { v.requestFocus(); return }
        }
        sidebarCategories.getChildAt(0)?.requestFocus()
    }

    private fun sidebarSwitchCategory(delta: Int, wrap: Boolean = false) {
        if (sidebarCategoriesList.isEmpty()) return
        val n = sidebarCategoriesList.size
        val idx = sidebarCategoriesList.indexOf(sidebarActiveCategory).coerceAtLeast(0)
        val newIdx = if (wrap) ((idx + delta) % n + n) % n else (idx + delta).coerceIn(0, n - 1)
        if (newIdx == idx) return
        sidebarActiveCategory = sidebarCategoriesList[newIdx]
        rebuildSidebarCategoryBar()
        applySidebarFilter(centerOnCurrent = false)
    }

    private fun applySidebarFilter(centerOnCurrent: Boolean, focus: Boolean = true) {
        val filtered = if (sidebarActiveCategory == "Все") channels
        else channels.filter { it.group == sidebarActiveCategory }
        for (c in filtered) c.isFavorite = c.key in TeliqApp.instance.settings.favorites
        sidebarAdapter.setItems(filtered, channels[currentIndex].url)
        sidebarTitle.text = "Каналы (${sidebarActiveCategory})   ${filtered.size}"
        val pos = if (centerOnCurrent) filtered.indexOfFirst { it.url == channels[currentIndex].url }.coerceAtLeast(0) else 0
        if (focus) focusSidebarItem(pos)
    }

    // ---------------------------------------------------------------- мини-просмотр

    private class Snap(val bmp: Bitmap, val at: Long)

    private var previewPlayer: MediaPlayer? = null
    private var previewLayout: VLCVideoLayout? = null
    private var previewCell: FavCell? = null
    private var pendingCell: FavCell? = null
    private var previewGen = 0
    private val previewCache = android.util.LruCache<String, Snap>(24)
    private val previewStartRunnable = Runnable { pendingCell?.let { startPreview(it) } }

    /** Выделение пришло на ячейку «Избранного»: через короткую паузу начнём обновлять в ней кадры. */
    private fun onFavFocus(cell: FavCell) {
        pendingCell = cell
        handler.removeCallbacks(previewStartRunnable)
        handler.postDelayed(previewStartRunnable, PREVIEW_DELAY_MS)
    }

    /**
     * Мини-просмотр: на ячейке под выделением второй плеер без звука читает поток канала и каждые
     * [PREVIEW_FRAME_MS] мс снимает кадр (TextureView → bitmap) в эту ячейку; через [PREVIEW_FRAMES] кадров
     * плеер закрывается, последний кадр остаётся. Кадры помнятся — ячейки других каналов показывают свои.
     */
    private fun startPreview(cell: FavCell) {
        if (!isSidebarVisible() || channels.isEmpty() || !cell.view.hasFocus()) return
        stopPreviewPlayer()
        // Играющий канал и так на экране — второй поток для него не открываем
        if (cell.ch.url == channels[currentIndex].url) return
        val lib = libVlc ?: return
        previewCell = cell
        val gen = ++previewGen
        try {
            val layout = VLCVideoLayout(this)
            previewLayout = layout
            cell.frame.addView(layout, 0, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            val mp = MediaPlayer(lib)
            previewPlayer = mp
            mp.attachViews(layout, null, false, true)   // TextureView — из него можно снять кадр
            mp.setEventListener { ev ->
                if (ev.type == MediaPlayer.Event.Vout && ev.voutCount > 0) {
                    handler.postDelayed({ captureSnap(gen, 0, 0) }, 900L)
                }
            }
            val media = Media(lib, Uri.parse(cell.ch.url))
            media.setHWDecoderEnabled(false, false)   // второй аппаратный декодер на слабой приставке может не открыться
            media.addOption(":no-audio")
            media.addOption(":network-caching=500")
            media.addOption(":live-caching=500")
            mp.media = media
            media.release()
            mp.play()
            handler.postDelayed({ if (gen == previewGen) stopPreviewPlayer() }, PREVIEW_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "preview failed", t)
            stopPreviewPlayer()
        }
    }

    private fun findTextureView(v: View): android.view.TextureView? {
        if (v is android.view.TextureView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findTextureView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Снять очередной кадр в ячейку; чёрный (поток только открылся) пропускаем и пробуем позже. */
    private fun captureSnap(gen: Int, taken: Int, attempt: Int) {
        if (gen != previewGen || previewPlayer == null) return
        val cell = previewCell ?: return
        val layout = previewLayout ?: return
        val bmp = findTextureView(layout)?.getBitmap(320, 180)
        if (bmp != null && !isBlank(bmp)) {
            previewCache.put(cell.ch.url, Snap(bmp, System.currentTimeMillis()))
            cell.image.dispose()
            cell.image.background = null
            cell.image.setPadding(0, 0, 0, 0)
            cell.image.scaleType = ImageView.ScaleType.CENTER_CROP
            cell.image.setImageBitmap(bmp)
            if (taken + 1 >= PREVIEW_FRAMES) stopPreviewPlayer()
            else handler.postDelayed({ captureSnap(gen, taken + 1, 0) }, PREVIEW_FRAME_MS)
        } else if (attempt < 8) {
            handler.postDelayed({ captureSnap(gen, taken, attempt + 1) }, 700L)
        }
    }

    /** Почти чёрный кадр за настоящий не считаем. */
    private fun isBlank(b: Bitmap): Boolean {
        var sum = 0L
        var n = 0
        for (y in 4 until b.height step 36) for (x in 4 until b.width step 40) {
            val c = b.getPixel(x, y)
            sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
            n++
        }
        return n == 0 || sum / (n * 3) < 12
    }

    /** Закрыть второй плеер (снятые кадры остаются в ячейках). Освобождение — в фоне, как у основного. */
    private fun stopPreviewPlayer() {
        val mp = previewPlayer
        previewPlayer = null
        previewLayout?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        previewLayout = null
        if (mp == null) return
        try {
            mp.setEventListener(null)
            mp.detachViews()
        } catch (_: Throwable) {}
        Thread({ try { mp.release() } catch (_: Throwable) {} }, "vlc-preview-release").start()
    }

    private fun stopPreview() {
        handler.removeCallbacks(previewStartRunnable)
        pendingCell = null
        previewGen++
        stopPreviewPlayer()
        previewCell = null
    }

    // ---------------------------------------------------------------- PiP

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }
        } catch (_: Throwable) {}
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideInfo()
            hud.visibility = View.GONE
            hideSidebar()
        }
    }

    companion object {
        const val EXTRA_CHANNEL_NUMBER = "channel_number"
        private const val TAG = "TeliqPlayer"

        /** Пауза после последнего переключения канала, прежде чем открыть поток (быстрое листание ↑↓). */
        private const val SWITCH_DEBOUNCE_MS = 250L

        /** Пауза выделения на канале в списке, прежде чем показать мини-просмотр, и предел ожидания кадра. */
        private const val PREVIEW_DELAY_MS = 450L
        private const val PREVIEW_TIMEOUT_MS = 14000L
        private const val PREVIEW_FRAME_MS = 1500L   // как часто обновляется кадр на ячейке под выделением
        private const val PREVIEW_FRAMES = 6         // сколько кадров снять, прежде чем закрыть второй плеер
    }
}
