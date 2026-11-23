package com.teliq.tv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.teliq.tv.R
import com.teliq.tv.data.Channel

/**
 * Адаптер списка каналов в редакторе.
 * Внутри хранит полный отсортированный список (allItems); адаптер показывает срез
 * по выбранной категории (visibleItems). Перемещение в видимом списке отражается
 * на глобальном порядке через обмен по глобальным индексам.
 */
class ChannelEditorAdapter(
    private val allItems: MutableList<Channel>,
) : RecyclerView.Adapter<ChannelEditorAdapter.VH>() {

    private var visibleItems: List<Channel> = allItems.toList()
    private var category: String = "Все"

    /** Индекс «захваченной» строки в видимом списке. -1 если ничего не захвачено. */
    var grabbedVisiblePosition: Int = -1
        private set

    fun setCategory(cat: String) {
        category = cat
        rebuildVisible()
        notifyDataSetChanged()
        grabbedVisiblePosition = -1
    }

    fun setGrabbed(visiblePos: Int) {
        val old = grabbedVisiblePosition
        grabbedVisiblePosition = visiblePos
        if (old >= 0) notifyItemChanged(old)
        if (visiblePos >= 0) notifyItemChanged(visiblePos)
    }

    /**
     * Переместить элемент видимой позиции [from] в [to] (обычно ±1).
     * Меняет порядок и в глобальном списке — обменом по глобальным индексам.
     */
    fun moveVisible(from: Int, to: Int) {
        if (from == to) return
        if (from !in visibleItems.indices || to !in visibleItems.indices) return
        val a = visibleItems[from]
        val b = visibleItems[to]
        val gA = allItems.indexOf(a)
        val gB = allItems.indexOf(b)
        if (gA < 0 || gB < 0) return
        allItems[gA] = b
        allItems[gB] = a
        // перенумерация всех каналов 1..N
        for (i in allItems.indices) allItems[i] = allItems[i].copy(number = i + 1)
        rebuildVisible()
        notifyItemMoved(from, to)
        // Обновим номера и подсветку соседних строк
        notifyItemRangeChanged(0, visibleItems.size)
        if (grabbedVisiblePosition == from) grabbedVisiblePosition = to
    }

    private fun rebuildVisible() {
        visibleItems = if (category == "Все") allItems.toList()
                       else allItems.filter { it.group == category }
    }

    fun snapshotAll(): List<Channel> = allItems.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_editor_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount() = visibleItems.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(visibleItems[position], isGrabbed = position == grabbedVisiblePosition)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val root: LinearLayout = view.findViewById(R.id.e_root)
        private val number: TextView = view.findViewById(R.id.e_number)
        private val logo: ImageView = view.findViewById(R.id.e_logo)
        private val name: TextView = view.findViewById(R.id.e_name)
        private val group: TextView = view.findViewById(R.id.e_group)
        private val mode: TextView = view.findViewById(R.id.e_mode)

        fun bind(ch: Channel, isGrabbed: Boolean) {
            // Показываем глобальный номер (как в плеере)
            number.text = ch.number.toString()
            name.text = ch.name
            group.text = ch.group
            if (ch.logoUrl.isNotEmpty()) {
                logo.load(ch.logoUrl) {
                    crossfade(true)
                    placeholder(ColorDrawable(Color.parseColor("#1C1D26")))
                    error(ColorDrawable(Color.parseColor("#1C1D26")))
                }
            } else {
                logo.setImageDrawable(ColorDrawable(Color.parseColor("#1C1D26")))
            }
            root.isActivated = isGrabbed
            mode.visibility = if (isGrabbed) View.VISIBLE else View.GONE
            if (isGrabbed) mode.text = "↕ ПЕРЕМЕЩЕНИЕ"
        }
    }
}
