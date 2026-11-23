package com.teliq.tv.ui

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.teliq.tv.R
import com.teliq.tv.TeliqApp
import com.teliq.tv.data.Settings

/** Расширенные настройки — секции, как у Perfect Player. */
class AdvancedSettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)

        val logo: ImageView = findViewById(R.id.logo)
        val raw = BitmapFactory.decodeResource(resources, R.drawable.teliq)
        if (raw != null) logo.setImageBitmap(BitmapCrop.trim(raw))

        bind()
    }

    private fun bind() {
        val s = TeliqApp.instance.settings

        // Соотношение сторон
        findViewById<TextView>(R.id.val_aspect).text = s.aspectRatio.label
        findViewById<LinearLayout>(R.id.row_aspect).setOnClickListener {
            val values = Settings.AspectRatio.entries
            pickList("Соотношение сторон", values.map { it.label }, values.indexOf(s.aspectRatio)) { idx ->
                s.aspectRatio = values[idx]; bind()
            }
        }

        // Аппаратное декодирование
        findViewById<TextView>(R.id.val_hw).text = s.hwDecoding.label
        findViewById<LinearLayout>(R.id.row_hw).setOnClickListener {
            val values = Settings.HwDecoding.entries
            pickList("Аппаратное декодирование", values.map { it.label }, values.indexOf(s.hwDecoding)) { idx ->
                s.hwDecoding = values[idx]; bind()
            }
        }

        // Деинтерлейсинг
        val deinterlaceOptions = listOf(
            "Выключено" to "",
            "Авто (yadif)" to "yadif",
            "Bob" to "bob",
            "Linear" to "linear",
            "Mean" to "mean",
            "X (быстрый)" to "x",
        )
        findViewById<TextView>(R.id.val_deinterlace).text =
            deinterlaceOptions.firstOrNull { it.second == s.deinterlace }?.first ?: "Выключено"
        findViewById<LinearLayout>(R.id.row_deinterlace).setOnClickListener {
            val current = deinterlaceOptions.indexOfFirst { it.second == s.deinterlace }.coerceAtLeast(0)
            pickList("Деинтерлейсинг", deinterlaceOptions.map { it.first }, current) { idx ->
                s.deinterlace = deinterlaceOptions[idx].second; bind()
            }
        }

        // User-Agent
        findViewById<TextView>(R.id.val_ua).text = s.userAgent.label
        findViewById<LinearLayout>(R.id.row_ua).setOnClickListener {
            val values = Settings.UserAgentPreset.entries
            pickList("User-Agent", values.map { it.label }, values.indexOf(s.userAgent)) { idx ->
                s.userAgent = values[idx]; bind()
            }
        }

        // Буферизация
        findViewById<TextView>(R.id.val_buffer).text = "${s.bufferMode.label} · ${s.bufferMode.ms} мс"
        findViewById<LinearLayout>(R.id.row_buffer).setOnClickListener {
            val values = Settings.BufferMode.entries
            pickList("Буферизация", values.map { "${it.label} · ${it.ms} мс" }, values.indexOf(s.bufferMode)) { idx ->
                s.bufferMode = values[idx]; bind()
            }
        }

        // EPG смещение
        findViewById<TextView>(R.id.val_epg_offset).text =
            (if (s.epgTimeOffset >= 0) "+" else "") + "${s.epgTimeOffset} ч"
        findViewById<LinearLayout>(R.id.row_epg_offset).setOnClickListener {
            val hours = (-12..14).toList()
            val labels = hours.map { "${if (it >= 0) "+" else ""}$it ч" }
            val current = hours.indexOf(s.epgTimeOffset).coerceAtLeast(0)
            pickList("Смещение времени EPG", labels, current) { idx ->
                s.epgTimeOffset = hours[idx]; bind()
            }
        }

        // Показывать номера каналов
        findViewById<TextView>(R.id.val_show_numbers).text =
            if (s.showChannelNumbers) "Включено" else "Выключено"
        findViewById<LinearLayout>(R.id.row_show_numbers).setOnClickListener {
            s.showChannelNumbers = !s.showChannelNumbers
            bind()
        }
    }

    private fun pickList(title: String, labels: List<String>, currentIndex: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), currentIndex) { d, idx ->
                onPick(idx); d.dismiss()
            }
            .show()
    }
}
