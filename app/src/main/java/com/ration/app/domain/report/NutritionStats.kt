package com.ration.app.domain.report

import com.ration.app.data.db.entity.MealLog
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.SlotType
import java.time.LocalDate

/** Итог одного дня. Дни без записей не входят в средние (иначе пропущенные дни занижают картину). */
data class DayNutrition(val day: Long, val kcal: Double, val protein: Double, val hasLogs: Boolean)

data class WeekNutrition(val weekStart: LocalDate, val days: Int, val avgKcal: Double, val avgProtein: Double)

data class SlotShare(val slot: SlotType?, val avgKcal: Double, val avgProtein: Double, val sharePct: Double)

data class NutritionSummary(
    val days: List<DayNutrition>,
    /** Дней с записями в периоде. */
    val loggedDays: Int,
    val avgKcal: Double?,
    val avgProtein: Double?,
    val minKcal: DayNutrition?,
    val maxKcal: DayNutrition?,
    /** Ккал в диапазоне цели (kcalMin..kcalMax). */
    val kcalInRange: Int,
    /** Перебор > 10% от ориентира (красный маркер раздела 6.5). */
    val redDays: Int,
    /** Белок не ниже proteinMin. */
    val proteinOk: Int,
    /** Текущая серия дней подряд с белком в норме (до последнего дня с записями). */
    val proteinStreak: Int,
    /** Средняя доля калорий из белка, % (белок × 4 ккал / ккал). */
    val proteinKcalPct: Double?,
    val weeks: List<WeekNutrition>,
    val bySlot: List<SlotShare>,
)

/** Статистика калорий и белка за период (только из журнала, без данных календаря). */
object NutritionStats {
    fun daily(logs: List<MealLog>, from: Long, to: Long): List<DayNutrition> {
        val byDay = logs.filter { it.day in from..to }.groupBy { it.day }
        return (from..to).map { d ->
            val l = byDay[d].orEmpty()
            DayNutrition(d, l.sumOf { it.kcal }, l.sumOf { it.protein }, l.isNotEmpty())
        }
    }

    fun summary(logs: List<MealLog>, from: Long, to: Long, s: AppSettings): NutritionSummary {
        val days = daily(logs, from, to)
        val logged = days.filter { it.hasLogs }
        val avgK = logged.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average()
        val avgP = logged.takeIf { it.isNotEmpty() }?.map { it.protein }?.average()
        var streak = 0
        for (d in logged.asReversed()) { if (d.protein >= s.proteinMin) streak++ else break }
        val weeks = logged.groupBy { TimeUtil.weekStart(LocalDate.ofEpochDay(it.day), s.weekStart) }
            .toSortedMap().map { (ws, l) -> WeekNutrition(ws, l.size, l.map { it.kcal }.average(), l.map { it.protein }.average()) }
        val inPeriod = logs.filter { it.day in from..to }
        val totalKcal = inPeriod.sumOf { it.kcal }
        val n = logged.size.coerceAtLeast(1)
        val bySlot = inPeriod.groupBy { it.slot }
            .map { (slot, l) -> SlotShare(slot, l.sumOf { it.kcal } / n, l.sumOf { it.protein } / n, if (totalKcal > 0) l.sumOf { it.kcal } / totalKcal * 100 else 0.0) }
            .sortedBy { it.slot?.ordinal ?: Int.MAX_VALUE }
        return NutritionSummary(
            days = days,
            loggedDays = logged.size,
            avgKcal = avgK,
            avgProtein = avgP,
            minKcal = logged.minByOrNull { it.kcal },
            maxKcal = logged.maxByOrNull { it.kcal },
            kcalInRange = logged.count { it.kcal in s.kcalMin.toDouble()..s.kcalMax.toDouble() },
            redDays = logged.count { it.kcal > s.kcalTarget * s.overDayRedPct / 100.0 },
            proteinOk = logged.count { it.protein >= s.proteinMin },
            proteinStreak = streak,
            proteinKcalPct = if (avgK != null && avgP != null && avgK > 0) avgP * 4 / avgK * 100 else null,
            weeks = weeks,
            bySlot = bySlot,
        )
    }
}
