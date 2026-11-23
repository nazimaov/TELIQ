package com.teliq.tv.data

/** Применение расписания к каналу: текущая и следующая передачи + их время. */
object EpgUtil {

    fun apply(ch: Channel, epg: Epg, now: Long = System.currentTimeMillis()) {
        val id = epg.resolveId(ch.tvgId, ch.name) ?: return
        val cur = epg.current(id, now)
        if (cur != null) {
            ch.currentProgram = EpgParser.formatProgramme(cur)
            ch.progStart = cur.start
            ch.progStop = cur.stop
        } else {
            ch.currentProgram = ""
            ch.progStart = 0L
            ch.progStop = 0L
        }
        ch.nextProgram = epg.nextAfter(id, now)?.let { EpgParser.formatProgramme(it) } ?: ""
    }

    /** «1 ч 05 мин» / «12 мин» */
    fun fmtDuration(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0L) / 60_000L).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h ч ${m.toString().padStart(2, '0')} мин" else "$m мин"
    }
}
