package com.teliq.tv.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.teliq.tv.R
import com.teliq.tv.update.ReleaseNotes
import com.teliq.tv.update.Updater
import kotlinx.coroutines.launch

/**
 * Окно самообновления: «Доступна версия X» с текстом «Что нового» → загрузка с процентами → установка.
 * Один диалог на всё время обновления, обновляется на месте (без мигания). Показывается только на экране,
 * который сейчас на переднем плане (RESUMED), иначе окна двоились бы поверх друг друга.
 */
object UpdateDialog {

    fun observe(activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val view = Holder(activity)
                try {
                    Updater.state.collect { view.render(it) }
                } finally {
                    view.close()
                }
            }
        }
    }

    private class Holder(private val activity: FragmentActivity) {
        private var dialog: Dialog? = null
        private var title: TextView? = null
        private var body: TextView? = null
        private var notesScroll: ScrollView? = null
        private var buttons: LinearLayout? = null
        private var shownKind: Class<*>? = null

        fun close() {
            dialog?.setOnCancelListener(null)
            dialog?.dismiss()
            dialog = null
            shownKind = null
        }

        private fun ensure(): Dialog {
            dialog?.let { return it }
            val ctx = activity
            val d = Dialog(ctx)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            fun dp(n: Int) = Ui.dp(ctx, n)
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(ctx, R.drawable.dialog_bg)
                setPadding(dp(20), dp(18), dp(20), dp(16))
            }
            val t = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val b = TextView(ctx).apply {
                setTextColor(0xFFD6EBFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.15f)
            }
            val scroll = ScrollView(ctx).apply { addView(b) }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            root.addView(t)
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            d.setContentView(root)
            d.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val w = minOf(dp(520), (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt())
                setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            d.setOnCancelListener { Updater.dismiss() }   // Back
            title = t; body = b; notesScroll = scroll; buttons = row
            dialog = d
            return d
        }

        private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView {
            val ctx = activity
            return TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minHeight = Ui.dp(ctx, 48)
                minWidth = Ui.dp(ctx, 120)
                setPadding(Ui.dp(ctx, 18), 0, Ui.dp(ctx, 18), 0)
                background = ContextCompat.getDrawable(ctx, if (primary) R.drawable.cat_pill_bg else R.drawable.btn_focusable_bg)
                isActivated = primary
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

        fun render(state: Updater.State) {
            if (state is Updater.State.Idle) { close(); return }
            val ctx = activity
            val d = ensure()
            val lang = ctx.resources.configuration.locales[0].language
            var titleText = ""
            var bodyText = ""
            var cancelable = true
            val actions = ArrayList<Pair<String, () -> Unit>>()
            var primaryFirst = true
            when (state) {
                is Updater.State.Available -> {
                    titleText = "Доступна версия ${state.info.version}"
                    val notes = ReleaseNotes.select(state.info.notes, lang)
                    bodyText = if (notes.isBlank()) "" else "Что нового:\n\n$notes"
                    actions += "Обновить" to { ctx.lifecycleScope.launch { Updater.downloadAndInstall(ctx.applicationContext, state.info) } }
                    actions += "Позже" to { Updater.dismiss() }
                }
                is Updater.State.Downloading -> {
                    titleText = "Загрузка версии ${state.info.version}"
                    bodyText = if (state.progress >= 0) "${(state.progress * 100).toInt()}%" else "Загрузка…"
                    cancelable = false
                }
                is Updater.State.Installing -> {
                    titleText = "Установка обновления"
                    bodyText = "Подтвердите установку в окне системы. Приложение перезапустится."
                    cancelable = false
                }
                Updater.State.Checking -> { titleText = "Проверка обновлений…"; cancelable = false }
                Updater.State.UpToDate -> { titleText = "У вас последняя версия"; actions += "OK" to { Updater.dismiss() } }
                is Updater.State.Failed -> {
                    titleText = when (state.reason) {
                        Updater.Reason.NOT_CONFIGURED -> "Обновления не настроены"
                        Updater.Reason.NETWORK -> "Не удалось проверить или скачать обновление"
                        Updater.Reason.NO_APK -> "В новом релизе нет файла установки"
                        Updater.Reason.INSTALL_FAILED -> "Не удалось запустить установку"
                        Updater.Reason.NEED_PERMISSION -> "Разрешите установку из этого приложения"
                    }
                    if (state.reason == Updater.Reason.NEED_PERMISSION) bodyText = "Включите переключатель в открывшемся окне и снова нажмите «Обновить»."
                    actions += "OK" to { Updater.dismiss() }
                }
                Updater.State.Idle -> Unit
            }
            d.setCancelable(cancelable)
            title?.text = titleText
            body?.text = bodyText
            notesScroll?.visibility = if (bodyText.isEmpty()) View.GONE else View.VISIBLE
            notesScroll?.layoutParams = (notesScroll?.layoutParams as LinearLayout.LayoutParams).also {
                it.height = if (state is Updater.State.Available) Ui.dp(ctx, 220) else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            // кнопки пересобираем только при смене вида окна (не на каждый процент загрузки)
            if (shownKind != state::class.java) {
                shownKind = state::class.java
                val row = buttons ?: return
                row.removeAllViews()
                row.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
                var first: View? = null
                for ((i, a) in actions.withIndex()) {
                    val b = button(a.first, primary = i == 0 && primaryFirst, onClick = a.second)
                    row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 10) })
                    if (first == null) first = b
                }
                if (!d.isShowing) d.show()
                first?.requestFocus()
            } else if (!d.isShowing) {
                d.show()
            }
        }
    }
}
