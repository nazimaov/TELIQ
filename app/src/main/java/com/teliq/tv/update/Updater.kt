package com.teliq.tv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.teliq.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Самообновление из релизов GitHub (так же, как в NTube): проверка `releases/latest`, окно «Что нового»,
 * загрузка APK в кэш и установка через системный установщик. Версия приложения сравнивается с тегом релиза
 * (`v1.2.0` → `1.2.0`); в релизе берётся первый файл `.apk`. Подробности — в README, раздел «Обновления».
 */
object Updater {

    data class Info(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    enum class Reason { NOT_CONFIGURED, NETWORK, NO_APK, INSTALL_FAILED, NEED_PERMISSION, SIGNATURE_MISMATCH }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: Info) : State
        data class Downloading(val info: Info, val progress: Float) : State
        data class Installing(val info: Info) : State
        data class Failed(val reason: Reason) : State
    }

    private const val TAG = "TeliqUpdater"
    private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L   // автопроверка не чаще раза в 3 часа
    private const val API_URL = "https://api.github.com"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    val configured: Boolean get() = BuildConfig.UPDATE_REPO_OWNER.isNotBlank() && BuildConfig.UPDATE_REPO_NAME.isNotBlank()

    /**
     * Проверить обновление. [force] — игнорировать интервал (кнопка в настройках), [userInitiated] — показать
     * результат и «у вас последняя версия»/ошибку (при автопроверке эти состояния молчат).
     */
    suspend fun check(context: Context, force: Boolean = false, userInitiated: Boolean = false) {
        val app = context.applicationContext
        if (!configured) {
            if (userInitiated) _state.value = State.Failed(Reason.NOT_CONFIGURED)
            return
        }
        val s = _state.value
        if (s is State.Downloading || s is State.Installing) return
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) {
            // Недавно проверяли: найденное и ещё не установленное обновление напоминаем при каждом запуске
            loadPending(app)?.let { _state.value = State.Available(it) }
            return
        }
        if (userInitiated) _state.value = State.Checking
        val release = fetchLatest()
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (release == null) {   // сбой сети: прежняя находка остаётся
            _state.value = if (userInitiated) State.Failed(Reason.NETWORK) else State.Idle
            return
        }
        val newer = Versions.isNewer(release.tag, BuildConfig.VERSION_NAME)
        val info = if (newer) toInfo(release) else null
        savePending(app, info)
        _state.value = when {
            !newer -> if (userInitiated) State.UpToDate else State.Idle
            info != null -> State.Available(info)
            else -> if (userInitiated) State.Failed(Reason.NO_APK) else State.Idle
        }
    }

    private class Release(val tag: String, val body: String, val apk: Pair<String, Long>?)

    /** GET /repos/{owner}/{repo}/releases/latest */
    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_URL/repos/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body!!.string())
                val assets = json.optJSONArray("assets")
                var apk: Pair<String, Long>? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.getString("browser_download_url") to a.optLong("size", -1)
                        break
                    }
                }
                Release(json.getString("tag_name"), json.optString("body"), apk)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Проверка обновления не удалась: ${e.message}")
            null
        }
    }

    private fun toInfo(r: Release): Info? =
        r.apk?.let { (url, size) -> Info(r.tag.removePrefix("v"), r.body, url, size) }

    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_VERSION = "pending_version"
    private const val KEY_URL = "pending_url"
    private const val KEY_NOTES = "pending_notes"
    private const val KEY_SIZE = "pending_size"

    private fun loadPending(context: Context): Info? {
        val p = prefs(context)
        val version = p.getString(KEY_VERSION, null) ?: return null
        if (!Versions.isNewer(version, BuildConfig.VERSION_NAME)) return null
        val url = p.getString(KEY_URL, null) ?: return null
        return Info(version, p.getString(KEY_NOTES, "").orEmpty(), url, p.getLong(KEY_SIZE, -1))
    }

    private fun savePending(context: Context, info: Info?) {
        prefs(context).edit().apply {
            if (info == null) remove(KEY_VERSION).remove(KEY_URL).remove(KEY_NOTES).remove(KEY_SIZE)
            else putString(KEY_VERSION, info.version).putString(KEY_URL, info.apkUrl)
                .putString(KEY_NOTES, info.notes).putLong(KEY_SIZE, info.sizeBytes)
        }.apply()
    }

    /** Скачать APK (с прогрессом) и передать системному установщику; подтверждение показывает система. */
    suspend fun downloadAndInstall(context: Context, info: Info) {
        val app = context.applicationContext
        if (!canInstall(app)) {
            openUnknownSourcesSettings(app)
            _state.value = State.Failed(Reason.NEED_PERMISSION)
            return
        }
        _state.value = State.Downloading(info, 0f)
        val file = download(app, info)
        if (file == null) {
            _state.value = State.Failed(Reason.NETWORK)
            return
        }
        _state.value = State.Installing(info)
        val ok = withContext(Dispatchers.IO) {
            runCatching { install(app, file) }.onFailure { Log.w(TAG, "Установка", it) }.isSuccess
        }
        if (!ok) _state.value = State.Failed(Reason.INSTALL_FAILED)
    }

    private suspend fun download(context: Context, info: Info): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "teliq-${info.version}.apk")
        try {
            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                var read = 0L
                var lastReported = 0f
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            val progress = if (total > 0) read.toFloat() / total else -1f
                            if (progress < 0 || progress - lastReported >= 0.01f) {
                                lastReported = progress
                                _state.value = State.Downloading(info, progress)
                            }
                        }
                    }
                }
            }
            target
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Загрузка обновления", e)
            null
        }
    }

    /** PackageInstaller: сессия → запись → commit; результат приходит в [InstallResultReceiver]. */
    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("teliq.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Итог установки от системы (см. [InstallResultReceiver]): при сбое окно показывает причину, а не «Установка…». */
    internal fun onInstallFailed(status: Int, message: String?) {
        Log.w(TAG, "Установка не удалась: $status $message")
        _state.value = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> State.Idle          // пользователь отказался в окне системы
            PackageInstaller.STATUS_FAILURE_CONFLICT -> State.Failed(Reason.SIGNATURE_MISMATCH)
            else -> State.Failed(Reason.INSTALL_FAILED)
        }
    }

    fun dismiss() {
        _state.value = State.Idle
    }
}
