package com.teliq.tv.data

/** Применение сохранённого пользовательского порядка к свежему плейлисту. */
object ChannelOrdering {

    /**
     * @param channels — каналы как пришли из M3U.
     * @param order    — сохранённый порядок (список URL).
     * @return каналы в нужном порядке: сначала те, что упомянуты в order, затем все новые.
     *   Номера каналов пересчитываются 1..N.
     */
    fun apply(allChannels: List<Channel>, order: List<String>): List<Channel> {
        // заблокированные каналы скрываем (вернуть можно в настройках)
        val blocked = com.teliq.tv.TeliqApp.instance.settings.blocked
        val channels = if (blocked.isEmpty()) allChannels else allChannels.filter { it.key !in blocked }
        if (order.isEmpty() || channels.isEmpty()) return renumber(channels)
        val byUrl = channels.associateBy { it.key }
        val seen = HashSet<String>()
        val out = mutableListOf<Channel>()

        for (url in order) {
            val ch = byUrl[url] ?: continue
            if (seen.add(url)) out.add(ch)
        }
        // Новые каналы, которых не было при последнем сохранении порядка — в конец
        for (ch in channels) {
            if (ch.key !in seen) out.add(ch)
        }
        return renumber(out)
    }

    private fun renumber(channels: List<Channel>): List<Channel> =
        channels.mapIndexed { idx, c -> c.copy(number = idx + 1) }
}
