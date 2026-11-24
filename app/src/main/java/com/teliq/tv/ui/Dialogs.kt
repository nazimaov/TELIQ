package com.teliq.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.teliq.tv.R

/** Диалоги в стиле приложения (вместо стандартных системных): ввод текста с крупным полем и кнопками-«таблетками». */
object Dialogs {

    /**
     * Ввод строки: название, поле [hint], кнопки «[okLabel]» и «Отмена». Enter на клавиатуре тоже подтверждает.
     * Пустая строка не принимается. Диалог стоит в верхней части экрана, чтобы экранная клавиатура его не закрывала.
     */
    fun prompt(
        context: Context,
        title: String,
        hint: String,
        current: String = "",
        okLabel: String = "OK",
        uri: Boolean = false,
        onOk: (String) -> Unit,
    ) {
        fun dp(n: Int) = Ui.dp(context, n)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.dialog_bg)
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        root.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })

        val edit = EditText(context).apply {
            this.hint = hint
            setHintTextColor(0xFF7F95B8.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = ContextCompat.getDrawable(context, R.drawable.input_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(54)
            inputType = if (uri) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
            setText(current)
            setSelection(text.length)
        }
        root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        fun submit() {
            val s = edit.text.toString().trim()
            if (s.isNotEmpty()) { dialog.dismiss(); onOk(s) }
        }
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        fun button(label: String, primary: Boolean, click: () -> Unit) = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(50)
            minWidth = dp(130)
            setPadding(dp(20), 0, dp(20), 0)
            background = ContextCompat.getDrawable(context, if (primary) R.drawable.cat_pill_bg else R.drawable.btn_focusable_bg)
            isActivated = primary
            isFocusable = true
            isClickable = true
            setOnClickListener { click() }
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(button("Отмена", false) { dialog.dismiss() })
        row.addView(button(okLabel, true) { submit() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(40) }
            setLayout(minOf(dp(620), (context.resources.displayMetrics.widthPixels * 0.92f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }
        dialog.show()
        edit.requestFocus()
    }
}
