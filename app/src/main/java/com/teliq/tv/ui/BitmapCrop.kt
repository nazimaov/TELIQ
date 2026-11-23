package com.teliq.tv.ui

import android.graphics.Bitmap
import android.graphics.Color

/** Автокроп — срезает прозрачные и почти-белые поля по краям логотипа. */
object BitmapCrop {

    fun trim(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        var left = w
        var top = h
        var right = 0
        var bottom = 0

        val step = maxOf(1, minOf(w, h) / 200)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = src.getPixel(x, y)
                val a = Color.alpha(c)
                if (a >= 30) {
                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                    val nearlyWhite = r >= 245 && g >= 245 && b >= 245
                    if (!nearlyWhite) {
                        if (x < left) left = x
                        if (y < top) top = y
                        if (x > right) right = x
                        if (y > bottom) bottom = y
                    }
                }
                x += step
            }
            y += step
        }
        if (right <= left || bottom <= top) return src
        val pad = 4
        val l = maxOf(0, left - pad)
        val t = maxOf(0, top - pad)
        val r2 = minOf(w - 1, right + pad)
        val b2 = minOf(h - 1, bottom + pad)
        return Bitmap.createBitmap(src, l, t, r2 - l, b2 - t)
    }
}
