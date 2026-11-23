package com.teliq.tv.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.R
import com.teliq.tv.data.Channel

/** Адаптер списка каналов в панели плеера (строка = логотип, название, передачи, ★). */
class SidebarChannelAdapter(
    private var items: List<Channel>,
    private val onClick: (Channel) -> Unit,
    private val onToggleFav: (Channel) -> Unit = {},
    private val onMenu: (Channel) -> Unit = {},
    /** Выделение в списке пришло на канал (пульт) — для мини-просмотра. */
    private val onFocused: (Channel) -> Unit = {},
) : RecyclerView.Adapter<SidebarChannelAdapter.VH>() {

    private var currentUrl: String? = null

    fun setItems(newItems: List<Channel>, current: String? = currentUrl) {
        items = newItems
        currentUrl = current
        notifyDataSetChanged()
    }

    fun refreshUrl(url: String) {
        val i = indexOfUrl(url)
        if (i >= 0) notifyItemChanged(i)
    }

    fun indexOfUrl(url: String): Int = items.indexOfFirst { it.url == url }

    fun setCurrentUrl(url: String?) {
        currentUrl = url
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.itemView.isActivated = ch.url == currentUrl
        holder.bind(ch)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.s_logo)
        private val name: TextView = view.findViewById(R.id.s_name)
        private val now: TextView = view.findViewById(R.id.s_now)
        private val next: TextView = view.findViewById(R.id.s_next)
        val star: ImageView = view.findViewById(R.id.s_star)

        private var channel: Channel? = null

        init {
            // касание / OK — смотреть; долгое касание / удержание OK — меню канала (слушатели — один раз на строку)
            RemoteLongPress.attach(view, onShort = { channel?.let(onClick) }, onLong = { channel?.let(onMenu) })
            star.setOnClickListener { channel?.let(onToggleFav) }
            view.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) channel?.let(onFocused) }
        }

        fun bind(ch: Channel) {
            channel = ch
            name.text = "${ch.number}. ${ch.name}"
            now.text = ch.currentProgram.ifEmpty { "—" }
            next.text = ch.nextProgram
            next.visibility = if (ch.nextProgram.isEmpty()) View.GONE else View.VISIBLE
            star.setImageResource(if (ch.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border)
            star.imageTintList = ColorStateList.valueOf(
                itemView.context.getColor(if (ch.isFavorite) R.color.star_gold else R.color.text_muted),
            )
            Ui.loadLogo(logo, ch)
        }
    }
}
