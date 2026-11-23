package com.teliq.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.teliq.tv.R
import com.teliq.tv.data.Channel

/** Меню канала (как правая кнопка мыши в ПК-версии): смотреть, избранное, переместить, заблокировать. */
object ChannelMenu {

    enum class Action { PLAY, FAV, UP, DOWN, BLOCK }

    /** [canUp]/[canDown] = null — пункты перемещения не показываются. */
    fun show(
        context: Context,
        ch: Channel,
        canUp: Boolean?,
        canDown: Boolean?,
        onAction: (Action) -> Unit,
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.dialog_bg)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }
        root.addView(TextView(context).apply {
            text = "${ch.number}. ${ch.name}"
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(context, 10), Ui.dp(context, 4), Ui.dp(context, 10), Ui.dp(context, 10))
        })

        var first: View? = null
        fun item(label: String, enabled: Boolean = true, action: Action) {
            val tv = TextView(context).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(if (enabled) Color.WHITE else 0xFF7F95B8.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                minHeight = Ui.dp(context, 52)
                setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
                background = ContextCompat.getDrawable(context, R.drawable.btn_focusable_bg)
                isFocusable = enabled
                isClickable = enabled
                if (enabled) setOnClickListener { dialog.dismiss(); onAction(action) }
            }
            root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(context, 3) })
            if (enabled && first == null) first = tv
        }

        item("▶  Смотреть", action = Action.PLAY)
        item(if (ch.isFavorite) "☆  Убрать из избранного" else "★  Добавить в избранное", action = Action.FAV)
        if (canUp != null && canDown != null) {
            item("▲  Переместить выше", canUp, Action.UP)
            item("▼  Переместить ниже", canDown, Action.DOWN)
        }
        item("🚫  Заблокировать канал", action = Action.BLOCK)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = minOf(Ui.dp(context, 380), (context.resources.displayMetrics.widthPixels * 0.9f).toInt())
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        first?.requestFocus()
    }
}
