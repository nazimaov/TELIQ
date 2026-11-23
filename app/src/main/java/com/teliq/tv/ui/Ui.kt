package com.teliq.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.load
import com.teliq.tv.R
import com.teliq.tv.data.Channel
import kotlin.math.max

/** Общие помощники для адаптивного интерфейса (телефон / планшет / Android TV). */
object Ui {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** Приставка Android TV: управление пультом, текст крупнее, фокус виден всегда. */
    fun isTv(ctx: Context): Boolean {
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Сколько колонок плиток помещается в [availableWidthPx] (минимум 2). */
    fun gridSpan(ctx: Context, availableWidthPx: Int): Int {
        val minPx = ctx.resources.getDimension(R.dimen.tile_min_width)
        return max(2, (availableWidthPx / minPx).toInt())
    }

    /** Загрузить логотип канала; пока нет картинки — цветная плашка с буквами канала. */
    fun loadLogo(view: ImageView, ch: Channel) {
        val placeholder = LetterDrawable(ch.abbreviation, colorFor(ch.name))
        if (ch.logoUrl.isEmpty()) {
            view.setImageDrawable(placeholder)
            return
        }
        view.load(ch.logoUrl) {
            // без crossfade: анимация на каждой плитке при прокрутке сетки нагружала слабый ТВ
            placeholder(placeholder)
            error(placeholder)
        }
    }

    private val PALETTE = intArrayOf(
        0xFF4060C0.toInt(), 0xFFC04060.toInt(), 0xFF40A060.toInt(), 0xFFA06040.toInt(),
        0xFF6040A0.toInt(), 0xFFC08040.toInt(), 0xFF208080.toInt(), 0xFFA04080.toInt(),
    )

    fun colorFor(name: String): Int = PALETTE[(name.hashCode() and 0x7fffffff) % PALETTE.size]
}

/** Скруглённая плашка с буквами — заглушка логотипа. */
class LetterDrawable(private val text: String, private val color: Int) : Drawable() {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LetterDrawable.color }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) * 0.16f
        canvas.drawRoundRect(RectF(b), r, r, bg)
        fg.textSize = minOf(b.width(), b.height()) * 0.38f
        val y = b.exactCenterY() - (fg.descent() + fg.ascent()) / 2f
        canvas.drawText(text, b.exactCenterX(), y, fg)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; fg.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
