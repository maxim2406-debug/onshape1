package com.ration.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object TimeUtil {
    fun hm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun parseHm(s: String): Int? {
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h !in 0..23 || mi !in 0..59) return null
        return h * 60 + mi
    }

    fun minuteOf(t: LocalTime): Int = t.hour * 60 + t.minute
    fun timeOf(minute: Int): LocalTime = LocalTime.of((minute / 60) % 24, minute % 60)

    fun toMillis(dt: LocalDateTime, zone: ZoneId): Long = dt.atZone(zone).toInstant().toEpochMilli()
    fun toLocal(millis: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    fun slotDateTime(day: Long, minuteOfDay: Int): LocalDateTime =
        LocalDate.ofEpochDay(day).atTime(timeOf(minuteOfDay))

    /** Начало недели (ISO-день weekStart: 1 = пн). */
    fun weekStart(date: LocalDate, weekStartIso: Int): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(weekStartIso)))

    private val ruDays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val ruDaysFull = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    fun dayShort(iso: Int) = ruDays[iso - 1]
    fun dayFull(iso: Int) = ruDaysFull[iso - 1]

    private val ruMonths = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря")
    fun dateRu(d: LocalDate): String = "${d.dayOfMonth} ${ruMonths[d.monthValue - 1]}, ${dayShort(d.dayOfWeek.value)}"
    fun dateShort(d: LocalDate): String = "%02d.%02d".format(d.dayOfMonth, d.monthValue)

    /** Форматирование числа без лишних нулей: 1.0 → «1», 0.5 → «0,5». */
    fun num(v: Double, decimals: Int = 1): String {
        val r = Math.round(v * Math.pow(10.0, decimals.toDouble())) / Math.pow(10.0, decimals.toDouble())
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', ',')
    }
}
