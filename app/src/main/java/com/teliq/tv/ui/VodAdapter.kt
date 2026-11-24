package com.teliq.tv.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.teliq.tv.R
import com.teliq.tv.data.vod.VodItem

/** Постер 2:3: высота считается от ширины колонки (ширина зависит от числа колонок сетки). */
class PosterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 3 / 2)
    }
}

/** Сетка фильмов/сериалов. [onNearEnd] — пора подгрузить следующую страницу (около 6 плиток до конца). */
class VodAdapter(
    private val onClick: (VodItem) -> Unit,
    private val onNearEnd: () -> Unit,
) : RecyclerView.Adapter<VodAdapter.VH>() {

    private val items = ArrayList<VodItem>()

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun add(more: List<VodItem>) {
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
        if (position >= items.size - 6) onNearEnd()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.vod_poster)
        private val name: TextView = view.findViewById(R.id.vod_name)
        private val meta: TextView = view.findViewById(R.id.vod_meta)
        private var item: VodItem? = null

        init {
            view.setOnClickListener { item?.let(onClick) }
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
            }
        }

        fun bind(it: VodItem) {
            item = it
            if (!itemView.hasFocus()) {
                itemView.animate().cancel()
                itemView.scaleX = 1f
                itemView.scaleY = 1f
            }
            name.text = it.title
            meta.text = listOfNotNull(
                it.year.takeIf { y -> y > 0 }?.toString(),
                if (it.uhd) "4K" else if (it.fhd) "FHD" else null,
                if (it.isStream) null else "серии",
            ).joinToString(" · ")
            poster.dispose()
            if (it.poster != null) {
                poster.load(it.poster) { placeholder(ColorDrawable(0xFF1B2A6B.toInt())) }
            } else {
                poster.setImageDrawable(ColorDrawable(0xFF1B2A6B.toInt()))
            }
        }
    }
}
