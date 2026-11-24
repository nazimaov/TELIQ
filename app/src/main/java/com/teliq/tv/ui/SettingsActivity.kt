package com.teliq.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.teliq.tv.BuildConfig
import com.teliq.tv.R
import com.teliq.tv.update.Updater
import com.teliq.tv.TeliqApp
import com.teliq.tv.data.ChannelOrdering
import com.teliq.tv.data.EpgParser
import com.teliq.tv.data.M3uParser
import com.teliq.tv.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Экран настроек TELIQ — кастомный, в едином стиле с главным экраном. */
class SettingsActivity : FragmentActivity() {

    private lateinit var playlistUrl: TextView
    private lateinit var playlistStatus: TextView
    private lateinit var epgUrl: TextView
    private lateinit var epgStatus: TextView

    private lateinit var bufLow: TextView
    private lateinit var bufBalanced: TextView
    private lateinit var bufStable: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Окно настроек: до 720dp, но не шире 94% экрана (телефон) — и с прокруткой внутри
        val maxW = resources.getDimensionPixelSize(R.dimen.settings_max_width)
        val w = minOf(maxW, (resources.displayMetrics.widthPixels * 0.94f).toInt())
        window.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        // Логотип с автокропом — как на главном
        val logo: ImageView = findViewById(R.id.logo)
        val raw = BitmapFactory.decodeResource(resources, R.drawable.teliq)
        if (raw != null) logo.setImageBitmap(BitmapCrop.trim(raw))

        playlistUrl = findViewById(R.id.playlist_url)
        playlistStatus = findViewById(R.id.playlist_status)
        epgUrl = findViewById(R.id.epg_url)
        epgStatus = findViewById(R.id.epg_status)

        bufLow = findViewById(R.id.buf_low)
        bufBalanced = findViewById(R.id.buf_balanced)
        bufStable = findViewById(R.id.buf_stable)

        findViewById<LinearLayout>(R.id.card_playlist).setOnClickListener {
            showPlaylistsManager()
        }
        findViewById<LinearLayout>(R.id.card_epg).setOnClickListener {
            promptUrl("URL расписания XMLTV", TeliqApp.instance.settings.lastEpgUrl) { url ->
                TeliqApp.instance.settings.lastEpgUrl = url
                reloadEpg(url)
            }
        }

        bufLow.setOnClickListener { setBuffer(Settings.BufferMode.LOW_LATENCY) }
        bufBalanced.setOnClickListener { setBuffer(Settings.BufferMode.BALANCED) }
        bufStable.setOnClickListener { setBuffer(Settings.BufferMode.STABLE) }

        findViewById<LinearLayout>(R.id.card_advanced).setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
            finish()
        }

        findViewById<LinearLayout>(R.id.card_editor).setOnClickListener {
            if (TeliqApp.instance.channels.isEmpty()) {
                Toast.makeText(this, "Сначала загрузите плейлист", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, EditorActivity::class.java))
                finish() // закрываем диалог, чтобы редактор был на основном экране
            }
        }

        findViewById<LinearLayout>(R.id.card_unblock).setOnClickListener {
            val n = TeliqApp.instance.settings.blocked.size
            if (n == 0) {
                Toast.makeText(this, "Заблокированных каналов нет", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Вернуть заблокированные каналы ($n)?")
                    .setPositiveButton("Вернуть") { _, _ ->
                        TeliqApp.instance.settings.blocked = emptySet()
                        reloadAllPlaylists()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }

        findViewById<LinearLayout>(R.id.card_portal).setOnClickListener { showPortalDialog() }

        findViewById<TextView>(R.id.update_status).text = "версия ${BuildConfig.VERSION_NAME}"
        findViewById<LinearLayout>(R.id.card_update).setOnClickListener {
            lifecycleScope.launch { Updater.check(applicationContext, force = true, userInitiated = true) }
        }
        UpdateDialog.observe(this)

        refreshUi()
    }

    /** Ссылка, которую сейчас меняем файлом (null — добавляем новый плейлист). */
    private var replacing: String? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val old = replacing
        replacing = null
        if (uri == null) return@registerForActivityResult
        val saved = runCatching { importPlaylistFile(uri) }.getOrNull()
        if (saved == null) {
            Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val settings = TeliqApp.instance.settings
        if (old != null) {
            settings.replacePlaylistUrl(old, saved)
            deleteOwnedFile(old)
        } else {
            settings.addPlaylistUrl(saved)
        }
        reloadAllPlaylists()
    }

    private fun launchPicker(old: String?) {
        replacing = old
        runCatching { pickFile.launch(arrayOf("*/*")) }.onFailure {
            replacing = null
            Toast.makeText(this, "На этом устройстве нет выбора файлов. Используйте ссылку.", Toast.LENGTH_LONG).show()
        }
    }

    /** Копирует выбранный файл в память приложения (чтобы не зависеть от прав на исходный файл). */
    private fun importPlaylistFile(uri: android.net.Uri): String {
        val dir = File(filesDir, "playlists").apply { mkdirs() }
        var name = "playlist"
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val out = File(dir, "${System.currentTimeMillis()}_$safe")
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        require(out.length() > 0) { "empty" }
        return out.absolutePath
    }

    private fun isLocalFile(u: String) = !u.startsWith("http://") && !u.startsWith("https://")

    private fun deleteOwnedFile(u: String) {
        if (isLocalFile(u)) runCatching {
            val f = File(u)
            if (f.parentFile == File(filesDir, "playlists")) f.delete()
        }
    }

    private fun label(u: String): String =
        if (isLocalFile(u)) "📄 " + File(u).name.replaceFirst(Regex("^\\d+_"), "") else u

    private fun showPlaylistsManager() {
        val app = TeliqApp.instance
        val urls = app.settings.playlistUrls.toMutableList()
        val labels = (urls.map { label(it) } + "+ Добавить ссылку" + "📁 Добавить файлом").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Плейлисты M3U")
            .setItems(labels) { _, which ->
                when {
                    which == urls.size -> promptUrl("Новый URL плейлиста", "") { newUrl ->
                        app.settings.addPlaylistUrl(newUrl)
                        reloadAllPlaylists()
                    }
                    which == urls.size + 1 -> launchPicker(null)
                    else -> showPlaylistActions(urls[which])
                }
            }
            .show()
    }

    private fun showPlaylistActions(u: String) {
        val app = TeliqApp.instance
        val editLabel = if (isLocalFile(u)) "Заменить файл…" else "Редактировать ссылку"
        AlertDialog.Builder(this)
            .setTitle(label(u))
            .setItems(arrayOf("Обновить", editLabel, "Удалить")) { _, w ->
                when (w) {
                    0 -> reloadAllPlaylists()
                    1 -> if (isLocalFile(u)) {
                        launchPicker(u)
                    } else {
                        promptUrl("Редактировать ссылку", u) { newUrl ->
                            app.settings.replacePlaylistUrl(u, newUrl)
                            reloadAllPlaylists()
                        }
                    }
                    2 -> {
                        app.settings.removePlaylistUrl(u)
                        deleteOwnedFile(u)
                        reloadAllPlaylists()
                    }
                }
            }
            .show()
    }

    private fun reloadAllPlaylists() {
        val app = TeliqApp.instance
        val urls = app.settings.playlistUrls
        if (urls.isEmpty()) { app.channels = emptyList(); refreshUi(); return }
        lifecycleScope.launch {
            val combined = withContext(Dispatchers.IO) {
                val list = mutableListOf<com.teliq.tv.data.Channel>()
                val seen = HashSet<String>()
                for (u in urls) {
                    runCatching { M3uParser.parse(u) }.getOrNull()?.let { parsed ->
                        for (c in parsed) if (seen.add(c.url)) list += c
                    }
                }
                list
            }
            val channels = ChannelOrdering.apply(combined, app.settings.channelOrder)
            app.channels = channels
            Toast.makeText(this@SettingsActivity, "Каналов: ${channels.size}", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
    }

    /** Подключение каталога фильмов VPortal: вставить ссылку `portal::[key:…]http://…/api/v1/`, проверить, сохранить. */
    private fun showPortalDialog() {
        val settings = TeliqApp.instance.settings
        if (settings.portalLink.isEmpty()) { promptPortal(); return }
        AlertDialog.Builder(this)
            .setTitle("Кино (VPortal) подключено")
            .setItems(arrayOf("Заменить ссылку", "Отключить")) { _, which ->
                if (which == 0) promptPortal() else { settings.portalLink = ""; refreshUi() }
            }
            .show()
    }

    private fun promptPortal() {
        promptUrl("Ссылка VPortal (portal::[key:…]http://…)", "") { raw ->
            val link = com.teliq.tv.data.vod.PortalLink.parse(raw)
            if (link == null) {
                Toast.makeText(this, "Не похоже на ссылку VPortal: portal::[key:…]http://…/api/v1/", Toast.LENGTH_LONG).show()
                return@promptUrl
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { com.teliq.tv.data.vod.VPortal.categories(link) } }
                result.onSuccess { cats ->
                    TeliqApp.instance.settings.portalLink = raw
                    Toast.makeText(this@SettingsActivity, "Кино подключено: разделов ${cats.size}", Toast.LENGTH_LONG).show()
                    refreshUi()
                }.onFailure {
                    Toast.makeText(this@SettingsActivity, "Портал не принял ключ: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshUi() {
        val app = TeliqApp.instance
        findViewById<TextView>(R.id.portal_status).text = if (app.settings.portalLink.isEmpty()) "не подключено" else "подключено"
        val urls = app.settings.playlistUrls
        playlistUrl.text = when (urls.size) {
            0 -> "URL не указан"
            1 -> label(urls[0])
            else -> "${urls.size} плейлистов"
        }
        playlistStatus.text = if (app.channels.isNotEmpty()) "${app.channels.size} каналов" else ""

        epgUrl.text = app.settings.lastEpgUrl.ifEmpty { "URL не указан" }
        epgStatus.text = if (!app.epg.isEmpty()) "${app.epg.channelsCount()} каналов" else ""

        val nb = app.settings.blocked.size
        findViewById<TextView>(R.id.unblock_status).text = if (nb > 0) "$nb · вернуть" else "нет"

        val mode = app.settings.bufferMode
        bufLow.isActivated = mode == Settings.BufferMode.LOW_LATENCY
        bufBalanced.isActivated = mode == Settings.BufferMode.BALANCED
        bufStable.isActivated = mode == Settings.BufferMode.STABLE
    }

    private fun setBuffer(mode: Settings.BufferMode) {
        TeliqApp.instance.settings.bufferMode = mode
        refreshUi()
        Toast.makeText(this, "Режим: ${mode.label}", Toast.LENGTH_SHORT).show()
    }

    private fun promptUrl(title: String, current: String, onOk: (String) -> Unit) {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(current)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val s = edit.text.toString().trim()
                if (s.isNotEmpty()) onOk(s)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun reloadPlaylist(url: String) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { M3uParser.parse(url) }.getOrElse { emptyList() }
            }
            val channels = ChannelOrdering.apply(parsed, TeliqApp.instance.settings.channelOrder)
            TeliqApp.instance.channels = channels
            Toast.makeText(this@SettingsActivity, "Каналов: ${channels.size}", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
    }

    private fun reloadEpg(url: String) {
        lifecycleScope.launch {
            val epg = withContext(Dispatchers.IO) {
                runCatching { EpgParser.parse(url) }.getOrNull()
            }
            if (epg != null && !epg.isEmpty()) {
                TeliqApp.instance.epg = epg
                Toast.makeText(
                    this@SettingsActivity,
                    "EPG: ${epg.channelsCount()} каналов, ${epg.programmesCount()} передач",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Не удалось загрузить EPG", Toast.LENGTH_SHORT).show()
            }
            refreshUi()
        }
    }
}
