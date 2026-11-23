package com.teliq.tv.ui

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.R
import com.teliq.tv.TeliqApp

/**
 * Редактор порядка каналов с категориями.
 *
 * - ↑↓ — переход по каналам в выбранной категории.
 * - ←→ — смена категории.
 * - OK — захватить / отпустить канал (оранжевая рамка + надпись «↕ ПЕРЕМЕЩЕНИЕ»).
 * - При захвате ↑↓ двигают сам канал.
 * - Back — выход (если ничего не захвачено) либо сначала отпустит захват.
 */
class EditorActivity : FragmentActivity() {

    private lateinit var list: RecyclerView
    private lateinit var adapter: ChannelEditorAdapter
    private lateinit var categoriesBar: LinearLayout

    private var categories: List<String> = emptyList()
    private var activeCategory: String = "Все"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val logo: ImageView = findViewById(R.id.logo)
        val raw = BitmapFactory.decodeResource(resources, R.drawable.teliq)
        if (raw != null) logo.setImageBitmap(BitmapCrop.trim(raw))

        list = findViewById(R.id.editor_list)
        list.layoutManager = LinearLayoutManager(this)
        categoriesBar = findViewById(R.id.editor_categories)

        val initial = TeliqApp.instance.channels.toMutableList()
        adapter = ChannelEditorAdapter(initial)
        list.adapter = adapter

        // Категории = "Все" + уникальные group, сохраняя порядок появления
        val groups = mutableListOf<String>()
        for (ch in initial) if (ch.group !in groups) groups += ch.group
        categories = listOf("Все") + groups.sorted()
        rebuildCategoryBar()
        adapter.setCategory(activeCategory)

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Сбросить порядок?")
                .setMessage("Каналы вернутся к порядку из плейлиста.")
                .setPositiveButton("Да") { _, _ ->
                    TeliqApp.instance.settings.clearChannelOrder()
                    finish()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun rebuildCategoryBar() {
        categoriesBar.removeAllViews()
        for (cat in categories) {
            val tv = TextView(this).apply {
                text = cat
                textSize = 12f
                setPadding(dp(16), dp(6), dp(16), dp(6))
                setTextColor(resources.getColor(R.color.text_primary, theme))
                background = resources.getDrawable(R.drawable.cat_pill_bg, theme)
                isFocusable = false
                isActivated = (cat == activeCategory)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            categoriesBar.addView(tv, lp)
        }
    }

    private fun setActiveCategory(cat: String) {
        if (cat == activeCategory) return
        activeCategory = cat
        rebuildCategoryBar()
        adapter.setCategory(cat)
        list.post {
            list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun switchCategory(delta: Int) {
        if (categories.isEmpty()) return
        val idx = categories.indexOf(activeCategory).coerceAtLeast(0)
        val newIdx = (idx + delta).coerceIn(0, categories.size - 1)
        if (newIdx != idx) setActiveCategory(categories[newIdx])
    }

    private fun focusedPosition(): Int {
        val v = currentFocus ?: return RecyclerView.NO_POSITION
        var view: android.view.View? = v
        while (view != null && view.parent !== list) view = view.parent as? android.view.View
        view ?: return RecyclerView.NO_POSITION
        return list.getChildAdapterPosition(view)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val pos = focusedPosition()
        val grabbed = adapter.grabbedVisiblePosition

        // OK — захват / отпускание
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (pos == RecyclerView.NO_POSITION) return super.onKeyDown(keyCode, event)
            adapter.setGrabbed(if (grabbed == pos) -1 else pos)
            return true
        }

        // В режиме захвата стрелки ↑↓ двигают сам элемент
        if (grabbed >= 0 && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            val target = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) grabbed - 1 else grabbed + 1
            if (target in 0 until adapter.itemCount) {
                adapter.moveVisible(grabbed, target)
                list.scrollToPosition(target)
                list.post {
                    list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                }
            }
            return true
        }

        // Категории через ←→ — только когда ничего не захвачено
        if (grabbed < 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { switchCategory(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { switchCategory(+1); return true }
            }
        }

        // Back — сначала отпустит захват, потом выйдет
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (grabbed >= 0) { adapter.setGrabbed(-1); return true }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        adapter.setGrabbed(-1)
        val ordered = adapter.snapshotAll()
        TeliqApp.instance.settings.channelOrder = ordered.map { it.key }
        TeliqApp.instance.channels = ordered
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
