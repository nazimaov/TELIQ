package com.teliq.tv.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.R
import com.teliq.tv.TeliqApp
import com.teliq.tv.data.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Полное расписание выбранного канала на сегодня и завтра. */
class ScheduleActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHANNEL_URL = "channel_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        val logo: ImageView = findViewById(R.id.logo)
        val raw = BitmapFactory.decodeResource(resources, R.drawable.teliq)
        if (raw != null) logo.setImageBitmap(BitmapCrop.trim(raw))

        val url = intent.getStringExtra(EXTRA_CHANNEL_URL).orEmpty()
        val ch = TeliqApp.instance.channels.firstOrNull { it.url == url }
        findViewById<TextView>(R.id.sched_channel).text = ch?.name ?: "—"

        val list: RecyclerView = findViewById(R.id.sched_list)
        list.layoutManager = LinearLayoutManager(this)

        val epg = TeliqApp.instance.epg
        val cid = ch?.let { epg.resolveId(it.tvgId, it.name) }
        val all = cid?.let { epg.byId[it] } ?: emptyList()
        // Берём передачи от 6 часов назад и на следующие 36 часов
        val now = System.currentTimeMillis()
        val from = now - 6 * 3600_000L
        val to = now + 36 * 3600_000L
        val items = all.filter { it.start in from..to }
        list.adapter = ScheduleAdapter(items, now)
    }
}

private class ScheduleAdapter(
    private val items: List<Programme>,
    private val now: Long,
) : RecyclerView.Adapter<ScheduleAdapter.VH>() {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_schedule, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.time.text = "${fmt.format(Date(p.start))}–${fmt.format(Date(p.stop))}"
        holder.title.text = if (p.title.isBlank()) "—" else p.title
        val isNow = now in p.start..p.stop
        holder.nowBadge.visibility = if (isNow) View.VISIBLE else View.GONE
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.sch_time)
        val title: TextView = view.findViewById(R.id.sch_title)
        val nowBadge: TextView = view.findViewById(R.id.sch_now)
    }
}
