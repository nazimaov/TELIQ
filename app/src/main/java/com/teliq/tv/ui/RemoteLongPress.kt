package com.teliq.tv.ui

import android.view.KeyEvent
import android.view.View

/**
 * Единая обработка нажатий для строк и плиток каналов:
 *  * касание — [onShort], долгое касание — [onLong];
 *  * пульт: короткое OK — [onShort] (по отпусканию), УДЕРЖАНИЕ OK — только [onLong], без [onShort].
 * Раньше удержание OK срабатывало и как «долгое», и как обычное нажатие, и канал открывался.
 */
object RemoteLongPress {
    fun attach(view: View, onShort: () -> Unit, onLong: () -> Unit) {
        var pressed = false
        var longDone = false

        view.setOnClickListener { onShort() }
        view.setOnLongClickListener { onLong(); true }
        view.setOnKeyListener { _, keyCode, event ->
            val select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!select) return@setOnKeyListener false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        pressed = true
                        longDone = false
                    } else if (pressed && !longDone) {
                        longDone = true
                        onLong()
                    }
                    true
                }
                KeyEvent.ACTION_UP -> {
                    val wasShort = pressed && !longDone
                    pressed = false
                    longDone = false
                    if (wasShort) onShort()
                    true
                }
                else -> false
            }
        }
    }
}
