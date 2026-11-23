package com.teliq.tv.ui

import com.teliq.tv.TeliqApp
import com.teliq.tv.data.Channel
import com.teliq.tv.data.ChannelOrdering

/** Действия из меню канала: порядок и блокировка. Работают со списком [TeliqApp.channels]. */
object ChannelActions {

    /**
     * Меняет канал [ch] местами с соседом по [visible] (текущий видимый список: категория и т.п.).
     * @return true, если перемещение выполнено.
     */
    fun move(ch: Channel, delta: Int, visible: List<Channel>): Boolean {
        val i = visible.indexOfFirst { it.url == ch.url }
        val j = i + delta
        if (i < 0 || j !in visible.indices) return false
        val app = TeliqApp.instance
        val order = app.channels.map { it.key }.toMutableList()
        val a = order.indexOf(visible[i].key)
        val b = order.indexOf(visible[j].key)
        if (a < 0 || b < 0) return false
        order[a] = visible[j].key
        order[b] = visible[i].key
        app.settings.channelOrder = order
        app.channels = ChannelOrdering.apply(app.channels, order)
        return true
    }

    fun block(ch: Channel) {
        val app = TeliqApp.instance
        app.settings.blocked = app.settings.blocked + ch.key
        app.channels = ChannelOrdering.apply(app.channels, app.settings.channelOrder)
    }
}
