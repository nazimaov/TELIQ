package com.teliq.tv

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.data.Channel
import com.teliq.tv.data.ChannelOrdering
import com.teliq.tv.data.EpgParser
import com.teliq.tv.data.EpgUtil
import com.teliq.tv.data.M3uParser
import com.teliq.tv.ui.BitmapCrop
import com.teliq.tv.ui.CategoryIcons
import com.teliq.tv.ui.ChannelActions
import com.teliq.tv.ui.ChannelMenu
import com.teliq.tv.ui.HomeAdapter
import com.teliq.tv.ui.HomeItem
import com.teliq.tv.ui.SearchActivity
import com.teliq.tv.ui.SettingsActivity
import com.teliq.tv.ui.UpdateDialog
import com.teliq.tv.ui.VodActivity
import com.teliq.tv.update.Updater
import com.teliq.tv.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран TELIQ (телефон, планшет, Android TV).
 *
 *  * компактный экран (телефон) — нижнее меню; ширина ≥ 600dp (планшет, ТВ, телефон
 *    в ландшафте) — боковое меню слева (раскладка layout-w600dp);
 *  * страницы: Главная (баннер + популярные), Телеканалы (по категориям), Избранное, История;
 *  * касание, жесты прокрутки и пульт (D-pad) работают одновременно.
 */
class MainActivity : FragmentActivity() {

    private enum class Page { HOME, CHANNELS, FAVORITES, HISTORY }

    private lateinit var clock: TextView
    private var categoryScroll: HorizontalScrollView? = null
    private var categoryBar: LinearLayout? = null
    private var navScroll: View? = null
    private var railMode = false
    private var railOpen = false
    private var navKey = ""
    private lateinit var homeList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var navContainer: LinearLayout
    private lateinit var adapter: HomeAdapter
    private lateinit var gridManager: GridLayoutManager

    private val navItems = mutableMapOf<String, TextView>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val isTv by lazy { Ui.isTv(this) }

    private var page = Page.HOME
    private var activeCategory = "Все"
    private var categories: List<String> = emptyList()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Меняем splash-тему на основную ДО super.onCreate
        setTheme(R.style.Theme_Teliq_Main)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clock = findViewById(R.id.clock)
        categoryScroll = findViewById(R.id.category_scroll)
        categoryBar = findViewById(R.id.category_bar)
        navScroll = findViewById(R.id.nav_scroll)
        railMode = navScroll != null
        homeList = findViewById(R.id.home_list)
        emptyText = findViewById(R.id.empty_text)
        navContainer = findViewById(R.id.nav_container)

        // Фон закрывает окно целиком — собственный фон окна под ним рисовать незачем (лишний слой)
        window.setBackgroundDrawable(null)

        // Логотип: PNG 1024×1024 раньше декодировался целиком и обрезался прямо в onCreate — главный поток
        // стоял на запуске. Теперь в фоне и уменьшенным (в шапке он высотой 32dp).
        val logoView: ImageView = findViewById(R.id.logo)
        lifecycleScope.launch {
            val logo = withContext(Dispatchers.Default) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeResource(resources, R.drawable.teliq, opts)?.let { BitmapCrop.trim(it) }
            }
            if (logo != null) logoView.setImageBitmap(logo)
        }

        // Подпись под логотипом — только когда хватает высоты (не на телефоне в ландшафте)
        findViewById<View>(R.id.tagline)?.visibility =
            if (resources.getBoolean(R.bool.show_tagline)) View.VISIBLE else View.GONE

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        adapter = HomeAdapter(
            onOpen = { ch -> openPlayer(ch) },
            onToggleFav = { ch -> toggleFavorite(ch) },
            onMenu = { ch -> showChannelMenu(ch) },
            onHeroClose = { dismissHero() },
            onTitleLink = { setPage(Page.CHANNELS) },
        )
        gridManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = adapter.spanSize(position, spanCount)
            }
        }
        homeList.layoutManager = gridManager
        homeList.adapter = adapter
        homeList.itemAnimator = null
        // Размер списка не зависит от содержимого — RecyclerView не пересчитывает его при каждом обновлении;
        // запас вьюх и пул плиток — быстрая прокрутка без постоянного создания карточек
        homeList.setHasFixedSize(true)
        homeList.setItemViewCacheSize(10)
        homeList.recycledViewPool.setMaxRecycledViews(HomeAdapter.TYPE_TILE, 30)
        // Число колонок зависит от ширины окна: телефон 2, планшет 4–5, ТВ 6–7
        homeList.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) updateSpan()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (railOpen && railMode) {
                    hideRail()
                } else if (page != Page.HOME) {
                    setPage(Page.HOME)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        buildNav()
        setupRail()
        tickClock()
        loadPlaylistAndEpg()

        // Самообновление: проверка релиза на GitHub при запуске (не чаще раза в 3 часа); окно «Что нового» — при находке
        UpdateDialog.observe(this)
        lifecycleScope.launch { Updater.check(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        val app = TeliqApp.instance
        // Открываем настройки только если плейлист ещё ни разу не задан.
        if (app.settings.playlistUrls.isEmpty() && app.channels.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            refresh()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun tickClock() {
        clock.text = timeFmt.format(Date())
        handler.postDelayed({ tickClock() }, 30_000)
    }

    // ---------- Загрузка данных ----------

    private fun loadPlaylistAndEpg() {
        val app = TeliqApp.instance
        val urls = app.settings.playlistUrls
        if (urls.isEmpty()) return

        loading = app.channels.isEmpty()
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                val combined = mutableListOf<Channel>()
                val seenUrls = HashSet<String>()
                for (u in urls) {
                    runCatching { M3uParser.parse(u) }.getOrNull()?.let { list ->
                        for (c in list) if (seenUrls.add(c.url)) combined += c
                    }
                }
                combined
            }
            val channels = ChannelOrdering.apply(parsed, app.settings.channelOrder)
            app.channels = channels
            loading = false
            refresh()

            // LibVLC создаётся заранее в фоне, чтобы плеер открывался без паузы (создание — секунда на слабой приставке)
            launch(Dispatchers.Default) { runCatching { app.sharedLibVlc() } }

            val epgUrl = app.settings.lastEpgUrl
            if (epgUrl.isNotEmpty()) {
                // Расписание применяется к каналам тоже в фоне: на тысячах каналов это зависание главного потока
                val epg = withContext(Dispatchers.IO) {
                    runCatching { EpgParser.parse(epgUrl) }.getOrNull()?.also { e ->
                        if (!e.isEmpty()) for (ch in channels) EpgUtil.apply(ch, e)
                    }
                }
                if (epg != null && !epg.isEmpty()) {
                    app.epg = epg
                    refresh()
                }
            }
        }
    }

    // ---------- Состояние и страницы ----------

    private fun refresh() {
        val app = TeliqApp.instance
        val favs = app.settings.favorites
        for (ch in app.channels) ch.isFavorite = ch.key in favs

        val all = app.channels
        val groups = all.map { it.group }.distinct().sorted()
        categories = listOf("Все") + (if (all.any { it.isFavorite }) listOf(FAV_CAT) else emptyList()) + groups
        if (activeCategory !in categories) activeCategory = "Все"
        if (page == Page.CHANNELS && activeCategory == FAV_CAT && FAV_CAT !in categories) activeCategory = "Все"
        if (navKey != navSignature()) buildNav()
        updateNav()
        rebuildCategoryBar()
        rebuildItems()
    }

    private fun setPage(p: Page) {
        contentFocused = false
        userNavigated = false
        page = p
        if (p == Page.CHANNELS && activeCategory == FAV_CAT) activeCategory = "Все"
        updateNav()
        categoryScroll?.visibility = if (p == Page.HOME || p == Page.CHANNELS) View.VISIBLE else View.GONE
        rebuildCategoryBar()
        rebuildItems()
        homeList.scrollToPosition(0)
    }

    private fun updateSpan() {
        val avail = homeList.width - homeList.paddingLeft - homeList.paddingRight
        if (avail <= 0) return
        val span = Ui.gridSpan(this, avail)
        if (span != gridManager.spanCount) {
            gridManager.spanCount = span
            if (page == Page.HOME) rebuildItems()
        }
    }

    private fun heroChannel(): Channel? {
        val app = TeliqApp.instance
        val byNumber = app.channels.firstOrNull { it.number == app.settings.lastChannelNumber }
        val ch = byNumber ?: app.settings.history.firstOrNull()?.let { u -> app.channels.firstOrNull { it.key == u } }
        return ch?.takeIf { it.key != app.settings.heroDismissedUrl }
    }

    private fun dismissHero() {
        heroChannel()?.let { TeliqApp.instance.settings.heroDismissedUrl = it.key }
        rebuildItems()
    }

    private fun popularChannels(limit: Int): List<Channel> {
        val app = TeliqApp.instance
        val all = app.channels
        val byUrl = all.associateBy { it.key }
        val seen = HashSet<String>()
        val out = mutableListOf<Channel>()
        val candidates = all.filter { it.isFavorite } +
            app.settings.history.mapNotNull { byUrl[it] } +
            all.filter { it.logoUrl.isNotEmpty() } + all
        for (c in candidates) {
            if (seen.add(c.url)) out += c
            if (out.size >= limit) break
        }
        return out
    }

    private fun rebuildItems() {
        val app = TeliqApp.instance
        val all = app.channels
        val items = mutableListOf<HomeItem>()
        var empty = ""

        when (page) {
            Page.HOME -> {
                heroChannel()?.let { items += HomeItem.Hero(it) }
                val popular = popularChannels((gridManager.spanCount * 2).coerceIn(4, 14))
                if (popular.isNotEmpty()) {
                    items += HomeItem.Title("🔥  Популярные каналы", "Смотреть всё  →")
                    popular.forEach { items += HomeItem.Tile(it) }
                }
            }
            Page.CHANNELS -> {
                val list = if (activeCategory == "Все") all else all.filter { it.group == activeCategory }
                list.forEach { items += HomeItem.Tile(it) }
            }
            Page.FAVORITES -> {
                all.filter { it.isFavorite }.forEach { items += HomeItem.Tile(it) }
                empty = "Избранных каналов пока нет.\nНажмите ☆ на плитке или удерживайте OK."
            }
            Page.HISTORY -> {
                val byUrl = all.associateBy { it.key }
                app.settings.history.mapNotNull { byUrl[it] }.forEach { items += HomeItem.Tile(it) }
                empty = "История пуста.\nЗдесь появятся каналы, которые вы смотрели."
            }
        }
        if (items.isEmpty() && all.isEmpty()) {
            empty = if (app.settings.playlistUrls.isEmpty()) {
                "Плейлист не задан.\nОткройте «Настройки» и добавьте M3U."
            } else if (loading) "Загрузка каналов…" else "Каналы не найдены."
        }

        adapter.setItems(items)
        emptyText.text = empty
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (adapter.itemCount > 0) ensureFocusOnTv()
    }

    /**
     * На ТВ следим, чтобы фокус пульта всегда был в содержимом (баннер или первая плитка), а не
     * «нигде»: иначе первая стрелка просто выберет пункт меню. Повторяем, пока список не построен.
     */
    private var contentFocused = false
    private var userNavigated = false

    private fun focusInContent(): Boolean {
        val f = currentFocus ?: return false
        var p: View? = f
        while (p != null) {
            if (p === homeList) return true
            p = p.parent as? View
        }
        return false
    }

    /**
     * Пока пользователь не нажал ни одной клавиши, держим фокус пульта в содержимом (кнопка баннера или
     * первая плитка). Данные и расписание подгружаются не сразу, и система за это время успевает отдать
     * фокус кнопке поиска — тогда первое OK открывало бы поиск. Проверяем повторно, пока всё не устоится.
     */
    private var ensureGen = 0

    private fun ensureFocusOnTv(attempt: Int = 0, gen: Int = ++ensureGen) {
        if (!isTv) return
        handler.postDelayed({
            if (gen != ensureGen || isFinishing || railOpen || userNavigated) return@postDelayed
            if (focusInContent() && contentFocused) {
                if (attempt < 40) ensureFocusOnTv(attempt + 1, gen)   // продолжаем присматривать: список ещё может перестроиться
                return@postDelayed
            }
            val first = homeList.findViewHolderForAdapterPosition(0)?.itemView
            val target = first?.findViewById<View>(R.id.hero_play)
                ?: homeList.findViewHolderForAdapterPosition(adapter.firstTilePosition())?.itemView
            if (target != null && target.requestFocus()) contentFocused = true
            if (attempt < 60) ensureFocusOnTv(attempt + 1, gen)
        }, if (attempt == 0) 300L else 250L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && adapter.itemCount > 0) ensureFocusOnTv()
    }

    // ---------- Меню ----------

    private fun buildNav() {
        navContainer.removeAllViews()
        navItems.clear()
        navKey = navSignature()
        val rail = navContainer.orientation == LinearLayout.VERTICAL
        val iconSize = Ui.dp(this, if (isTv) 30 else 24)
        val states = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
            intArrayOf(Color.WHITE, getColor(R.color.text_muted)),
        )

        fun make(iconRes: Int?, label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(if (rail) R.dimen.nav_label_size else R.dimen.tile_badge_size).let {
                if (rail) it else Ui.dp(this@MainActivity, if (isTv) 12 else 11).toFloat()
            })
            setTextColor(states)
            typeface = Typeface.DEFAULT_BOLD
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.nav_item_bg)
            isFocusable = true
            isClickable = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val d = iconRes?.let { ContextCompat.getDrawable(this@MainActivity, it)!!.mutate() }
            d?.setBounds(0, 0, iconSize, iconSize)
            if (rail) {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                compoundDrawablePadding = Ui.dp(this@MainActivity, 14)
                setPadding(Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 10), 0)
                if (d != null) setCompoundDrawablesRelative(d, null, null, null)
            } else {
                gravity = Gravity.CENTER
                compoundDrawablePadding = Ui.dp(this@MainActivity, 2)
                if (d != null) setCompoundDrawablesRelative(null, d, null, null)
            }
            if (d != null) setCompoundDrawableTintList(states)
            setOnClickListener { onClick() }
        }

        fun add(v: TextView, heightDp: Int = if (isTv) 60 else 52) {
            val lp = if (rail) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Ui.dp(this, heightDp),
                ).apply { topMargin = Ui.dp(this@MainActivity, 3); bottomMargin = Ui.dp(this@MainActivity, 3) }
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .apply { marginStart = Ui.dp(this@MainActivity, 2); marginEnd = Ui.dp(this@MainActivity, 2) }
            }
            navContainer.addView(v, lp)
        }

        fun entry(key: String, icon: Int?, label: String, h: Int = if (isTv) 60 else 52, go: () -> Unit) {
            val v = make(icon, label) { go(); hideRail() }
            navItems[key] = v
            add(v, h)
        }

        entry("home", R.drawable.ic_home, "Главная") { setPage(Page.HOME) }
        if (railMode) {
            // Категории — прямо в боковом меню
            val catH = if (isTv) 50 else 44
            for (cat in categories) {
                if (cat == FAV_CAT) continue
                val label = if (cat == "Все") "📺  Все каналы" else CategoryIcons.decorate(cat).toString()
                entry("cat:$cat", null, label, catH) {
                    activeCategory = cat
                    setPage(Page.CHANNELS)
                }
            }
        } else {
            entry("channels", R.drawable.ic_tv, "Телеканалы") { setPage(Page.CHANNELS) }
        }
        // Каталог фильмов и сериалов (VPortal) — только если портал подключён в настройках
        if (hasPortal()) entry("movies", R.drawable.ic_play, "Кино") { startActivity(Intent(this, VodActivity::class.java)) }
        entry("fav", R.drawable.ic_favorite, "Избранное") { setPage(Page.FAVORITES) }
        entry("hist", R.drawable.ic_history, "История") { setPage(Page.HISTORY) }
        add(make(R.drawable.ic_settings, "Настройки") {
            hideRail()
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        updateNav()
    }

    private fun hasPortal() = TeliqApp.instance.settings.portalLink.isNotEmpty()

    /** Что определяет состав меню: категории (в боковом меню) и пункт «Кино». */
    private fun navSignature(): String = (if (railMode) categories.joinToString("|") else "") + (if (hasPortal()) "|vod" else "")

    private fun activeNavKey(): String = when (page) {
        Page.HOME -> "home"
        Page.CHANNELS -> if (railMode) "cat:$activeCategory" else "channels"
        Page.FAVORITES -> "fav"
        Page.HISTORY -> "hist"
    }

    private fun updateNav() {
        val k = activeNavKey()
        for ((key, v) in navItems) v.isActivated = (key == k)
    }

    // ---------- Боковое меню на ТВ: скрыто, пока фокус в содержимом; открывается стрелкой «влево» ----------

    private fun setupRail() {
        val rail = navScroll ?: return
        // меню скрыто и на ТВ, и на планшете; на ТВ открывается стрелкой «влево», на планшете — свайпом вправо
        rail.post {
            rail.translationX = -rail.width.toFloat()
            rail.visibility = View.GONE
        }
        swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isTv) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) < Ui.dp(this@MainActivity, 60) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false
                if (dx > 0 && !railOpen) showRail() else if (dx < 0 && railOpen) hideRail()
                return true
            }
        })
    }

    private var swipe: android.view.GestureDetector? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (railMode && !isTv) {
            val rail = navScroll
            if (railOpen && rail != null && ev.action == android.view.MotionEvent.ACTION_DOWN && ev.x > rail.width) {
                hideRail()   // касание вне меню закрывает его
                return true
            }
            swipe?.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun inRail(v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === navScroll) return true
            p = p.parent as? View
        }
        return false
    }

    private fun showRail() {
        val rail = navScroll ?: return
        if (railOpen) return
        railOpen = true
        rail.visibility = View.VISIBLE
        rail.translationX = -rail.width.toFloat()
        rail.animate().translationX(0f).setDuration(180).start()
        val target = navItems[activeNavKey()] ?: navItems.values.firstOrNull()
        if (isTv) target?.post { target.requestFocus() }
    }

    private fun hideRail() {
        val rail = navScroll ?: return
        if (!railOpen) return
        railOpen = false
        rail.animate().translationX(-rail.width.toFloat()).setDuration(160)
            .withEndAction { if (!railOpen) rail.visibility = View.GONE }.start()
        if (isTv) {
            contentFocused = false
            ensureFocusOnTv()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) userNavigated = true
        if (isTv && railMode && event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (!railOpen) {
                    val cur = currentFocus
                    val next = cur?.focusSearch(View.FOCUS_LEFT)
                    val noLeft = cur == null || next == null || next === cur || inRail(next) || !isLeftOf(next, cur)
                    if (noLeft) {
                        if (event.repeatCount == 0) showRail()
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (railOpen) {
                    hideRail()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** [a] действительно левее [b] и находится в той же «строке» (вертикально пересекается). */
    private fun isLeftOf(a: View, b: View): Boolean {
        val la = IntArray(2)
        val lb = IntArray(2)
        a.getLocationOnScreen(la)
        b.getLocationOnScreen(lb)
        val left = la[0] + a.width / 2 < lb[0] + b.width / 2
        val sameRow = la[1] < lb[1] + b.height && lb[1] < la[1] + a.height
        return left && sameRow
    }

    private fun rebuildCategoryBar() {
        val categoryBar = categoryBar ?: return
        if (railMode) return
        categoryBar.removeAllViews()
        val vPad = Ui.dp(this, if (isTv) 10 else 8)
        for (cat in categories) {
            val tv = TextView(this).apply {
                text = CategoryIcons.decorate(cat)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pill_text_size))
                setPadding(Ui.dp(this@MainActivity, 18), vPad, Ui.dp(this@MainActivity, 18), vPad)
                setTextColor(getColor(R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.cat_pill_bg)
                isFocusable = true
                isClickable = true
                maxLines = 1
                isActivated = when (page) {
                    Page.HOME -> cat == "Все"
                    Page.CHANNELS -> cat == activeCategory
                    else -> false
                }
                setOnClickListener {
                    if (cat == FAV_CAT) {
                        setPage(Page.FAVORITES)
                    } else {
                        activeCategory = cat
                        setPage(Page.CHANNELS)
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Ui.dp(this@MainActivity, 10) }
            categoryBar.addView(tv, lp)
        }
    }

    // ---------- Действия ----------

    private fun toggleFavorite(ch: Channel) {
        TeliqApp.instance.settings.toggleFavorite(ch.url)
        ch.isFavorite = !ch.isFavorite
        Toast.makeText(
            this,
            if (ch.isFavorite) "★ Добавлено в избранное" else "Удалено из избранного",
            Toast.LENGTH_SHORT,
        ).show()
        refresh()
    }

    /** Меню канала (удержание OK / долгое касание): смотреть, избранное, переместить, заблокировать. */
    private fun showChannelMenu(ch: Channel) {
        // переместить можно только на странице «Телеканалы» (в пределах открытой категории)
        val visible = if (page == Page.CHANNELS) {
            val all = TeliqApp.instance.channels
            if (activeCategory == "Все") all else all.filter { it.group == activeCategory }
        } else null
        val idx = visible?.indexOfFirst { it.url == ch.url } ?: -1
        ChannelMenu.show(
            this, ch,
            canUp = visible?.let { idx > 0 },
            canDown = visible?.let { idx in 0 until it.size - 1 },
        ) { action ->
            when (action) {
                ChannelMenu.Action.PLAY -> openPlayer(ch)
                ChannelMenu.Action.FAV -> toggleFavorite(ch)
                ChannelMenu.Action.UP, ChannelMenu.Action.DOWN -> {
                    if (visible != null && ChannelActions.move(ch, if (action == ChannelMenu.Action.UP) -1 else 1, visible)) refresh()
                }
                ChannelMenu.Action.BLOCK -> {
                    ChannelActions.block(ch)
                    Toast.makeText(this, "«${ch.name}» заблокирован. Вернуть — в Настройках", Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
    }

    private fun openPlayer(channel: Channel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NUMBER, channel.number)
        }
        startActivity(intent)
    }

    companion object {
        const val FAV_CAT = "★ Избранное"
    }
}
