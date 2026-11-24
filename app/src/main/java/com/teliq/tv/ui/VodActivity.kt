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
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Название фильма или сериала"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Поиск")
            .setView(edit)
            .setPositiveButton("Найти") { _, _ ->
                val q = edit.text.toString().trim()
                if (q.isNotEmpty()) {
                    selected = -1
                    markSelected()
                    title.text = "🎬  Кино · «$q»"
                    startList({ null }, search = q)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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
