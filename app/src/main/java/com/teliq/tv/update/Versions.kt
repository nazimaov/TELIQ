package com.teliq.tv.update

/** Сравнение версий МАЖОР.МИНОР.ПАТЧ; префикс `v` и суффиксы `-…` игнорируются. */
object Versions {
    fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: 0 }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
