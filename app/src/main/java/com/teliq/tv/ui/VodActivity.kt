package com.teliq.tv.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.teliq.tv.R
import com.teliq.tv.TeliqApp
import com.teliq.tv.data.vod.PortalLink
import com.teliq.tv.data.vod.VPortal
import com.teliq.tv.data.vod.VodCategory
import com.teliq.tv.data.vod.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * «Кино» (VPortal): разделы сверху, сетка постеров, поиск. Фильм (`stream`) играет сразу, сериал/сборник
 * (`multistream`) открывает список серий. Страницы подгружаются по мере прокрутки.
 */
class VodActivity : FragmentActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var catsBar: LinearLayout
    private lateinit var catsScroll: HorizontalScrollView
    private lateinit var adapter: VodAdapter
    private lateinit var grid: GridLayoutManager

    private var link: PortalLink? = null
    private var categories: List<VodCategory> = emptyList()
    private var selected = -1
    private var nextRequest: JSONObject? = null
    private var loading = false
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)
        window.setBackgroundDrawable(null)

        link = PortalLink.parse(TeliqApp.instance.settings.portalLink)
        if (link == null) {
            Toast.makeText(this, "Портал не подключён: Настройки → Кино (VPortal)", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        list = findViewById(R.id.vod_list)
        empty = findViewById(R.id.vod_empty)
        title = findViewById(R.id.vod_title)
        catsBar = findViewById(R.id.vod_cats)
        catsScroll = findViewById(R.id.vod_cats_scroll)

        adapter = VodAdapter(onClick = ::open, onNearEnd = ::loadMore)
        grid = GridLayoutManager(this, 5)
        list.layoutManager = grid
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(10)
        // колонок столько, сколько помещается постеров шириной ≈ 150dp
        list.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) {
                val avail = v.width - v.paddingLeft - v.paddingRight
                if (avail > 0) grid.spanCount = (avail / Ui.dp(this, 150)).coerceIn(2, 8)
            }
        }
        findViewById<ImageButton>(R.id.btn_vod_search).setOnClickListener { promptSearch() }

        loadCategories()
    }

    /** ← с самого левого края (постер или раздел) — назад на главный экран с открытым боковым меню. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && event.repeatCount == 0) {
            val cur = currentFocus
            val next = cur?.focusSearch(View.FOCUS_LEFT)
            if (cur != null && (next == null || next === cur || !isLeftOf(next, cur))) {
                showMenu()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Боковое меню поверх «Кино» (как на главном экране): раздел выбирается, «Кино» остаётся под ним, пока не уйдём. */
    private fun showMenu() {
        fun dp(n: Int) = Ui.dp(this, n)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@VodActivity, R.drawable.nav_rail_bg)
            setPadding(dp(14), dp(24), dp(14), dp(14))
        }
        var current: View? = null
        fun entry(label: String, nav: String?, small: Boolean = false) {
            val tv = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.nav_label_size))
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(10), 0)
                background = ContextCompat.getDrawable(this@VodActivity, R.drawable.nav_item_bg)
                isFocusable = true
                isClickable = true
                isActivated = nav == null
                setOnClickListener {
                    dialog.dismiss()
                    if (nav != null) { TeliqApp.instance.pendingNav = nav; finish() }
                }
            }
            root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (small) 50 else 60)).apply { topMargin = dp(3); bottomMargin = dp(3) })
            if (nav == null) current = tv
        }
        entry("🏠  Главная", "home")
        entry("📺  Все каналы", "channels")
        // те же категории каналов, что и в меню главного экрана
        TeliqApp.instance.channels.map { it.group }.distinct().sorted().forEach { g ->
            entry(CategoryIcons.decorate(g).toString(), "cat:$g", small = true)
        }
        entry("🎬  Кино", null)
        entry("♥  Избранное", "fav")
        entry("🕘  История", "hist")
        entry("⚙  Настройки", "settings")
        dialog.setContentView(ScrollView(this).apply {
            background = ContextCompat.getDrawable(this@VodActivity, R.drawable.nav_rail_bg)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        })
        root.background = null
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.START)
            setLayout(dp(280), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // → закрывает меню, как на главном экране
        dialog.setOnKeyListener { d, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == android.view.KeyEvent.ACTION_DOWN) { d.dismiss(); true } else false
        }
        dialog.show()
        current?.requestFocus()
    }

    private fun isLeftOf(a: View, b: View): Boolean {
        val la = IntArray(2); val lb = IntArray(2)
        a.getLocationOnScreen(la); b.getLocationOnScreen(lb)
        return la[0] + a.width / 2 < lb[0] + b.width / 2 && la[1] < lb[1] + b.height && lb[1] < la[1] + a.height
    }

    private fun loadCategories() {
        val l = link ?: return
        empty.text = "Загрузка…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { VPortal.categories(l) } }
            result.onSuccess { cats ->
                categories = cats
                buildCategoryBar()
                if (cats.isEmpty()) empty.text = "В каталоге нет разделов" else selectCategory(0)
            }.onFailure {
                empty.text = "Не удалось загрузить каталог: ${it.message}"
            }
        }
    }

    private fun buildCategoryBar() {
        catsBar.removeAllViews()
        val vPad = Ui.dp(this, if (Ui.isTv(this)) 10 else 8)
        categories.forEachIndexed { index, cat ->
            val pill = TextView(this).apply {
                text = cat.title
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pill_text_size))
                setPadding(Ui.dp(this@VodActivity, 18), vPad, Ui.dp(this@VodActivity, 18), vPad)
                setTextColor(getColor(R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(this@VodActivity, R.drawable.cat_pill_bg)
                isFocusable = true
                isClickable = true
                maxLines = 1
                setOnClickListener { selectCategory(index) }
            }
            catsBar.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = Ui.dp(this@VodActivity, 10) })
        }
        markSelected()
    }

    private fun markSelected() {
        for (i in 0 until catsBar.childCount) catsBar.getChildAt(i).isActivated = i == selected
    }

    private fun selectCategory(index: Int) {
        selected = index
        markSelected()
        title.text = "🎬  Кино · ${categories[index].title}"
        startList(first = { JSONObject(categories[index].request.toString()).put("offset", 0) })
    }

    /** Новый список: сбрасывает прежний и грузит первую страницу [first]. */
    private fun startList(first: () -> JSONObject?, search: String? = null) {
        val gen = ++generation
        nextRequest = null
        adapter.clear()
        empty.text = "Загрузка…"
        empty.visibility = View.VISIBLE
        loading = true
        val l = link ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { if (search != null) VPortal.search(l, search) else VPortal.page(l, first()!!) }
            }
            if (gen != generation) return@launch
            loading = false
            result.onSuccess { page ->
                adapter.add(page.items)
                nextRequest = page.nextRequest
                empty.text = if (page.items.isEmpty()) "Ничего не найдено" else ""
                empty.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
                if (page.items.isNotEmpty()) focusFirst()
            }.onFailure {
                empty.text = "Не удалось загрузить: ${it.message}"
            }
        }
    }

    private fun loadMore() {
        val l = link ?: return
        val next = nextRequest ?: return
        if (loading) return
        loading = true
        val gen = generation
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { VPortal.page(l, next) } }
            if (gen != generation) return@launch
            loading = false
            result.onSuccess { page ->
                adapter.add(page.items)
                nextRequest = page.nextRequest
            }   // сбой подгрузки: оставляем загруженное, следующая прокрутка повторит
        }
    }

    private fun focusFirst() {
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    // ---------------------------------------------------------------- поиск

    private fun promptSearch() {
        showSearch()
    }

    /**
     * Поиск с подсказками: пока печатаете, под полем (с небольшой паузой после ввода) появляются подходящие
     * фильмы и сериалы с постерами. Плитка открывает фильм сразу; «Найти»/Enter — все результаты в каталоге.
     * Пультом к плиткам: Back убирает экранную клавиатуру, затем ↓ с поля.
     */
    private fun showSearch() {
        val l = link ?: return
        fun dp(n: Int) = Ui.dp(this, n)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@VodActivity, R.drawable.dialog_bg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val edit = EditText(this).apply {
            hint = "Название фильма или сериала"
            setHintTextColor(0xFF7F95B8.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = ContextCompat.getDrawable(this@VodActivity, R.drawable.input_bg)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(50)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
        }
        val go = TextView(this).apply {
            text = "Найти"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(50)
            setPadding(dp(22), 0, dp(22), 0)
            background = ContextCompat.getDrawable(this@VodActivity, R.drawable.cat_pill_bg)
            isActivated = true
            isFocusable = true
            isClickable = true
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        root.addView(top)

        val hintText = TextView(this).apply {
            setTextColor(0xFF9FB4D8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "Начните вводить название — подсказки появятся здесь"
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(results) }
        val area = android.widget.FrameLayout(this)
        area.addView(scroll)
        area.addView(hintText, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        root.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })

        fun submit() {
            val q = edit.text.toString().trim()
            if (q.isEmpty()) return
            dialog.dismiss()
            selected = -1
            markSelected()
            title.text = "🎬  Кино · «$q»"
            startList({ null }, search = q)
        }
        go.setOnClickListener { submit() }
        edit.setOnEditorActionListener { _, _, _ -> submit(); true }

        fun showHint(text: String) {
            results.removeAllViews()
            hintText.text = text
            hintText.visibility = View.VISIBLE
        }

        fun tile(item: VodItem): View {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@VodActivity, R.drawable.tile_bg)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                isFocusable = true
                isClickable = true
                setOnClickListener { dialog.dismiss(); open(item) }
                setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(100).start()
                }
            }
            val poster = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; background = ContextCompat.getDrawable(this@VodActivity, R.drawable.snap_bg) }
            if (item.poster != null) poster.load(item.poster)
            col.addView(poster, LinearLayout.LayoutParams(dp(72), dp(100)))
            col.addView(TextView(this).apply {
                text = item.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            return col
        }

        var job: kotlinx.coroutines.Job? = null
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                job?.cancel()
                if (q.length < 2) { showHint("Начните вводить название — подсказки появятся здесь"); return }
                job = lifecycleScope.launch {
                    kotlinx.coroutines.delay(350)   // пауза после последней буквы: не спрашивать портал на каждую
                    val r = withContext(Dispatchers.IO) { runCatching { VPortal.search(l, q) } }
                    r.onSuccess { page ->
                        results.removeAllViews()
                        val items = page.items.take(12)
                        if (items.isEmpty()) { showHint("Ничего не найдено"); return@onSuccess }
                        hintText.visibility = View.GONE
                        items.forEach { results.addView(tile(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
                    }.onFailure { showHint("Не удалось найти: ${it.message}") }
                }
            }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(12) }
            setLayout(minOf(dp(760), (resources.displayMetrics.widthPixels * 0.94f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        edit.requestFocus()
    }

    // ---------------------------------------------------------------- открытие

    private fun open(item: VodItem) {
        val url = item.streamUrl
        if (url != null) {
            play(item.title, url, item.fid)
            return
        }
        val l = link ?: return
        lifecycleScope.launch {
            val episodes = withContext(Dispatchers.IO) { runCatching { VPortal.flick(l, item) }.getOrNull() }
            when {
                episodes == null -> Toast.makeText(this@VodActivity, "Не удалось открыть", Toast.LENGTH_SHORT).show()
                episodes.isEmpty() -> Toast.makeText(this@VodActivity, "Нет доступных серий", Toast.LENGTH_SHORT).show()
                episodes.size == 1 -> play(item.title, episodes[0].streamUrl!!, episodes[0].fid)
                else -> showEpisodes(item, episodes)
            }
        }
    }

    /** Список серий: название, описание, серии строками (OK — смотреть). */
    private fun showEpisodes(item: VodItem, episodes: List<VodItem>) {
        fun dp(n: Int) = Ui.dp(this, n)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@VodActivity, R.drawable.dialog_bg)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        root.addView(TextView(this).apply {
            text = item.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (item.description.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = item.description
                setTextColor(0xFFD6EBFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        for (ep in episodes) {
            val row = TextView(this).apply {
                text = "▶  " + ep.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(52)
                setPadding(dp(14), 0, dp(14), 0)
                background = ContextCompat.getDrawable(this@VodActivity, R.drawable.btn_focusable_bg)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    play("${item.title} — ${ep.title}", ep.streamUrl!!, ep.fid)
                }
            }
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            if (first == null) first = row
        }
        root.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(280), (resources.displayMetrics.heightPixels * 0.55f).toInt())).apply { topMargin = dp(10) })
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(dp(520), (resources.displayMetrics.widthPixels * 0.92f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        first?.requestFocus()
    }

    private fun play(title: String, url: String, id: Int) {
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra(VodPlayerActivity.EXTRA_URL, url)
                .putExtra(VodPlayerActivity.EXTRA_TITLE, title)
                .putExtra(VodPlayerActivity.EXTRA_ID, id),
        )
    }
}
