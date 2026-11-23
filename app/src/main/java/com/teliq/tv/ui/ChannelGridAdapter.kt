package com.teliq.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.R
import com.teliq.tv.TeliqApp
import com.teliq.tv.data.Channel

/**
 * Плитка канала (общая для главной, поиска и избранного).
 * Касание — открыть, долгое касание / долгое OK / ☆ — избранное. В фокусе (пульт) плитка
 * подсвечивается неоном и слегка увеличивается.
 */
class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val logo: ImageView = view.findViewById(R.id.tile_logo)
    private val name: TextView = view.findViewById(R.id.tile_name)
    private val program: TextView = view.findViewById(R.id.tile_program)
    private val group: TextView = view.findViewById(R.id.tile_group)
    private val fav: TextView = view.findViewById(R.id.tile_fav)

    private val baseElevation = view.elevation
    private val focusElevation = view.resources.getDimension(R.dimen.tile_focus_elevation)

    // Слушатели ставятся один раз (раньше — при каждом bind: лишний мусор и работа GC при прокрутке);
    // какой именно канал в плитке — берём из этих полей
    private var channel: Channel? = null
    private var onClick: (Channel) -> Unit = {}
    private var onToggleFav: (Channel) -> Unit = {}
    private var onMenu: (Channel) -> Unit = {}

    init {
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
            // Тень меняем только там, где она есть (телефон); на ТВ тени у плиток нет совсем
            if (focusElevation > 0f) v.elevation = if (hasFocus) focusElevation else baseElevation
        }
        // касание / OK — открыть; долгое касание / удержание OK — меню канала (не открывает канал)
        RemoteLongPress.attach(view, onShort = { channel?.let(onClick) }, onLong = { channel?.let(onMenu) })
        fav.setOnClickListener { channel?.let(onToggleFav) }
    }

    fun bind(
        ch: Channel,
        onClick: (Channel) -> Unit,
        onToggleFav: (Channel) -> Unit,
        onMenu: (Channel) -> Unit = onToggleFav,
    ) {
        channel = ch
        this.onClick = onClick
        this.onToggleFav = onToggleFav
        this.onMenu = onMenu

        // Переиспользованная плитка не должна оставаться увеличенной от прошлого канала
        if (!itemView.hasFocus()) {
            itemView.animate().cancel()
            itemView.scaleX = 1f
            itemView.scaleY = 1f
        }
        val showNumber = TeliqApp.instance.settings.showChannelNumbers
        name.text = if (showNumber) "${ch.number}. ${ch.name}" else ch.name
        program.text = ch.currentProgram.ifEmpty { ch.group }
        group.text = ch.group.take(20)
        fav.text = if (ch.isFavorite) "★" else "☆"
        fav.setTextColor(
            if (ch.isFavorite) itemView.context.getColor(R.color.star_gold) else 0xFF2A3D8F.toInt(),
        )
        Ui.loadLogo(logo, ch)
    }
}

/** Простая сетка каналов (поиск и т.п.). */
class ChannelGridAdapter(
    private var items: List<Channel>,
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit = {},
    private val onMenu: (Channel) -> Unit = onLongClick,
) : RecyclerView.Adapter<TileHolder>() {

    fun setItems(newItems: List<Channel>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun itemAt(pos: Int): Channel? = items.getOrNull(pos)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder =
        TileHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_tile, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        holder.bind(items[position], onClick, onLongClick, onMenu)
    }
}
