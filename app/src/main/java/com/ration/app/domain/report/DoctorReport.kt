package com.ration.app.domain.report

import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.plan.CounterState
import com.ration.app.domain.plan.WeekCounters
import com.ration.app.domain.plan.WeekRules
import java.time.LocalDate
import java.time.ZoneId

data class DayRow(val day: LocalDate, val kcal: Double, val protein: Double)
data class WeekRow(val start: LocalDate, val counters: WeekCounters, val ok: Int, val total: Int)

data class DoctorReportData(
    val from: LocalDate,
    val to: LocalDate,
    val weights: List<WeightLog>,
    val bpAvgSys: Double?,
    val bpAvgDia: Double?,
    val bpAvgPulse: Double?,
    val bpCount: Int,
    val days: List<DayRow>,
    val avgKcal: Double?,
    val avgProtein: Double?,
    val weeks: List<WeekRow>,
)

object DoctorReport {
    fun build(
        from: LocalDate, to: LocalDate, zone: ZoneId, s: AppSettings,
        weights: List<WeightLog>, bp: List<BpLog>, logs: List<MealLog>, quick: List<QuickLog>,
    ): DoctorReportData {
        val w = weights.filter { it.day in from.toEpochDay()..to.toEpochDay() }.sortedBy { it.day }
        val b = bp.filter { TimeUtil.toLocal(it.atMillis, zone).toLocalDate().let { d -> !d.isBefore(from) && !d.isAfter(to) } }
        val inRange = logs.filter { it.day in from.toEpochDay()..to.toEpochDay() }
        val days = inRange.groupBy { it.day }.toSortedMap().map { (d, l) ->
            DayRow(LocalDate.ofEpochDay(d), l.sumOf { it.kcal }, l.sumOf { it.protein })
        }
        val weeks = mutableListOf<WeekRow>()
        var ws = TimeUtil.weekStart(from, s.weekStart)
        while (!ws.isAfter(to)) {
            val we = ws.plusDays(6)
            val wl = inRange.filter { it.day in ws.toEpochDay()..we.toEpochDay() }
            val wq = quick.filter { it.day in ws.toEpochDay()..we.toEpochDay() }
            if (wl.isNotEmpty()) {
                val c = WeekCounters.of(wl, wq)
                val rows = WeekRules.rows(c, s, 0)
                weeks += WeekRow(ws, c, rows.count { it.state == CounterState.OK }, rows.size)
            }
            ws = ws.plusWeeks(1)
        }
        return DoctorReportData(
            from, to, w,
            b.takeIf { it.isNotEmpty() }?.map { it.systolic }?.average(),
            b.takeIf { it.isNotEmpty() }?.map { it.diastolic }?.average(),
            b.mapNotNull { it.pulse }.takeIf { it.isNotEmpty() }?.average(),
            b.size, days,
            days.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average(),
            days.takeIf { it.isNotEmpty() }?.map { it.protein }?.average(),
            weeks,
        )
    }

    fun toText(r: DoctorReportData): List<String> = buildList {
        add("Отчёт «Рацион» за период ${TimeUtil.dateShort(r.from)}.${r.from.year} — ${TimeUtil.dateShort(r.to)}.${r.to.year}")
        add("Приложение не является медицинским изделием; данные введены пользователем вручную.")
        add("")
        add("ВЕС")
        if (r.weights.isEmpty()) add("  нет записей")
        r.weights.forEach { add("  ${LocalDate.ofEpochDay(it.day)}: ${TimeUtil.num(it.kg)} кг") }
        if (r.weights.size >= 2) add("  изменение за период: ${TimeUtil.num(r.weights.last().kg - r.weights.first().kg)} кг")
        add("")
        add("ДАВЛЕНИЕ (измерений: ${r.bpCount})")
        if (r.bpAvgSys != null && r.bpAvgDia != null) {
            add("  среднее: ${Math.round(r.bpAvgSys)}/${Math.round(r.bpAvgDia)} мм рт. ст." +
                (r.bpAvgPulse?.let { ", пульс ${Math.round(it)}" } ?: ""))
        } else add("  нет записей")
        add("")
        add("ПИТАНИЕ ПО ДНЯМ")
        r.days.forEach { add("  ${it.day}: ${Math.round(it.kcal)} ккал, белок ${Math.round(it.protein)} г") }
        if (r.avgKcal != null) add("  среднее: ${Math.round(r.avgKcal)} ккал, белок ${Math.round(r.avgProtein ?: 0.0)} г")
        add("")
        add("НЕДЕЛЬНЫЕ ПРАВИЛА")
        r.weeks.forEach {
            val c = it.counters
            add("  неделя с ${it.start}: выполнено ${it.ok} из ${it.total}; рыба ${c.fish}, жирная ${c.fattyFish}, " +
                "красное мясо ${c.redMeat}, свободный обед ${c.freeLunch}, алкоголь ${c.alcohol}")
        }
    }

    private fun csvCell(s: String): String {
        // Защита от формул в табличных редакторах и корректное экранирование.
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == ';' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun toCsv(r: DoctorReportData, bp: List<BpLog>, zone: ZoneId): String = buildString {
        appendLine("section,date,value1,value2,value3")
        r.weights.forEach { appendLine(listOf("weight", LocalDate.ofEpochDay(it.day).toString(), it.kg.toString(), "", "").joinToString(",") { c -> csvCell(c) }) }
        bp.filter { TimeUtil.toLocal(it.atMillis, zone).toLocalDate().let { d -> !d.isBefore(r.from) && !d.isAfter(r.to) } }
            .sortedBy { it.atMillis }
            .forEach { appendLine(listOf("bp", TimeUtil.toLocal(it.atMillis, zone).toString(), it.systolic.toString(), it.diastolic.toString(), it.pulse?.toString() ?: "").joinToString(",") { c -> csvCell(c) }) }
        r.days.forEach { appendLine(listOf("day", it.day.toString(), Math.round(it.kcal).toString(), Math.round(it.protein).toString(), "").joinToString(",") { c -> csvCell(c) }) }
        r.weeks.forEach { appendLine(listOf("week", it.start.toString(), it.ok.toString(), it.total.toString(), "").joinToString(",") { c -> csvCell(c) }) }
    }
}
