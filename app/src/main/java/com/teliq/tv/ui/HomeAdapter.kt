package com.teliq.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.R
import com.teliq.tv.data.Channel
import com.teliq.tv.data.EpgUtil

/** Элементы главного экрана: баннер, заголовок секции, плитка канала. */
sealed class HomeItem {
    /** [sig] — снимок отображаемых полей: Channel меняется «на месте», по нему diff видит изменения. */
    data class Hero(val channel: Channel, val sig: String = sigOf(channel)) : HomeItem()
    data class Title(val text: String, val link: String? = null) : HomeItem()
    data class Tile(val channel: Channel, val sig: String = sigOf(channel)) : HomeItem()
}

private fun sigOf(ch: Channel): String =
    "${ch.number}|${ch.currentProgram}|${ch.nextProgram}|${ch.isFavorite}|${ch.progStart}|${ch.logoUrl}"

class HomeAdapter(
    private val onOpen: (Channel) -> Unit,
    private val onToggleFav: (Channel) -> Unit,
    private val onMenu: (Channel) -> Unit,
    private val onHeroClose: () -> Unit,
    private val onTitleLink: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<HomeItem> = emptyList()

    /** Обновление с DiffUtil: фокус пульта и позиция прокрутки не сбрасываются. */
    fun setItems(newItems: List<HomeItem>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = newItems[n]
                return when {
                    a is HomeItem.Hero && b is HomeItem.Hero -> true
                    a is HomeItem.Title && b is HomeItem.Title -> a.text == b.text
                    a is HomeItem.Tile && b is HomeItem.Tile -> a.channel.url == b.channel.url
                    else -> false
                }
            }
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    /** Баннер и заголовки занимают всю ширину, плитка — одну колонку. */
    fun spanSize(position: Int, spanCount: Int): Int =
        if (items.getOrNull(position) is HomeItem.Tile) 1 else spanCount

    fun firstTilePosition(): Int = items.indexOfFirst { it is HomeItem.Tile }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeItem.Hero -> TYPE_HERO
        is HomeItem.Title -> TYPE_TITLE
        is HomeItem.Tile -> TYPE_TILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroHolder(inflater.inflate(R.layout.item_hero, parent, false))
            TYPE_TITLE -> TitleHolder(inflater.inflate(R.layout.item_section_title, parent, false))
            else -> TileHolder(inflater.inflate(R.layout.item_channel_tile, parent, false))
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeItem.Hero -> (holder as HeroHolder).bind(item.channel, onOpen, onHeroClose)
            is HomeItem.Title -> (holder as TitleHolder).bind(item, onTitleLink)
            is HomeItem.Tile -> (holder as TileHolder).bind(item.channel, onOpen, onToggleFav, onMenu)
        }
    }

    private class TitleHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.section_title)
        private val link: TextView = view.findViewById(R.id.section_link)

        fun bind(item: HomeItem.Title, onLink: () -> Unit) {
            title.text = item.text
            if (item.link == null) {
                link.visibility = View.GONE
            } else {
                link.visibility = View.VISIBLE
                link.text = item.link
                link.setOnClickListener { onLink() }
            }
        }
    }

    private class HeroHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.hero_logo)
        private val title: TextView = view.findViewById(R.id.hero_title)
        private val program: TextView = view.findViewById(R.id.hero_program)
        private val progress: ProgressBar = view.findViewById(R.id.hero_progress)
        private val timing: TextView = view.findViewById(R.id.hero_timing)
        private val play: TextView = view.findViewById(R.id.hero_play)
        private val close: ImageButton = view.findViewById(R.id.hero_close)

        fun bind(ch: Channel, onOpen: (Channel) -> Unit, onClose: () -> Unit) {
            title.text = ch.name
            program.text = ch.currentProgram.ifEmpty { "Прямой эфир" }
            Ui.loadLogo(logo, ch)

            val has = ch.progStart > 0 && ch.progStop > ch.progStart
            progress.visibility = if (has) View.VISIBLE else View.GONE
            timing.visibility = if (has) View.VISIBLE else View.GONE
            if (has) {
                val now = System.currentTimeMillis()
                val total = ch.progStop - ch.progStart
                progress.progress = (((now - ch.progStart) * 1000) / total).toInt().coerceIn(0, 1000)
                timing.text = "Прошло ${EpgUtil.fmtDuration(now - ch.progStart)}   •   " +
                    "Осталось ${EpgUtil.fmtDuration(ch.progStop - now)}"
            }
            itemView.setOnClickListener { onOpen(ch) }
            itemView.isFocusable = false   // фокус только на кнопках баннера, иначе стрелка «влево» упирается в карточку
            play.setOnClickListener { onOpen(ch) }
            close.setOnClickListener { onClose() }
        }
    }

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_TITLE = 1
        const val TYPE_TILE = 2
    }
}
