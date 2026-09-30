package com.ration.app.domain.rules

import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.model.Tags
import com.ration.app.domain.plan.WeekCounters
import java.time.LocalDateTime
import java.time.ZoneId

data class DayWarning(val id: String, val text: String, val severe: Boolean = false)

/** Предупреждения дня (раздел 4 и 6.4–6.5). Тексты без конкретных значений здоровья. */
object DayRules {
    fun warnings(
        now: LocalDateTime,
        zone: ZoneId,
        logs: List<MealLog>,
        quick: List<QuickLog>,
        s: AppSettings,
        weekIncludingToday: WeekCounters,
    ): List<DayWarning> {
        val out = mutableListOf<DayWarning>()
        fun minuteOf(ms: Long) = TimeUtil.minuteOf(TimeUtil.toLocal(ms, zone).toLocalTime())
        val kcal = logs.sumOf { it.kcal }
        val protein = logs.sumOf { it.protein }
        val nowMin = TimeUtil.minuteOf(now.toLocalTime())

        if (kcal > s.kcalMax) out += DayWarning("over_max", "Съедено больше верхней границы дня (${s.kcalMax} ккал).", true)
        val before15 = logs.filter { minuteOf(it.atMillis) < s.earlyOverLimitTime }.sumOf { it.kcal }
        if (before15 >= s.kcalTarget * s.earlyOverPct / 100.0)
            out += DayWarning("early_over", "Ранний перебор: до ${TimeUtil.hm(s.earlyOverLimitTime)} съедено ${s.earlyOverPct}% дневной цели и больше.")
        val before12 = logs.filter { minuteOf(it.atMillis) < s.morningOverTime }.sumOf { it.kcal }
        if (before12 >= s.kcalTarget * s.morningOverPct / 100.0)
            out += DayWarning("morning_over", "Съедено слишком много с утра: до ${TimeUtil.hm(s.morningOverTime)} больше ${s.morningOverPct}% калорий дня.")
        if (nowMin >= s.lowProteinTime && protein < s.lowProteinG)
            out += DayWarning("low_protein", "Мало белка: к ${TimeUtil.hm(s.lowProteinTime)} меньше ${s.lowProteinG} г. Добавьте Е3/Е4, П1 или П5.")

        val bars = logs.count { Tags.BAR in it.tags }
        if (bars > s.maxBarsPerDay) out += DayWarning("bars_day", "Больше ${s.maxBarsPerDay} батончиков за день.")
        val proteinBars = logs.count { Tags.PROTEIN_BAR in it.tags }
        if (proteinBars > s.maxProteinBarsPerDay) out += DayWarning("pbar_day", "Протеиновых батончиков больше ${s.maxProteinBarsPerDay} в день.")
        if (weekIncludingToday.proteinBars > s.maxProteinBarsPerWeek)
            out += DayWarning("pbar_week", "Протеиновых батончиков за неделю больше ${s.maxProteinBarsPerWeek}.")
        if (logs.any { Tags.CEREAL_BAR in it.tags })
            out += DayWarning("cereal_bar", "Злаковый батончик не закрывает белок: добавьте белковый источник в следующий приём.")

        if (quick.any { it.type == QuickType.ESPRESSO && minuteOf(it.atMillis) >= s.coffeeLimit })
            out += DayWarning("coffee", "Эспрессо или американо после ${TimeUtil.hm(s.coffeeLimit)}.")
        if (quick.any { it.type == QuickType.ALCOHOL })
            out += DayWarning("alcohol", "Алкоголь исключён, пока показатели печени не в норме.", true)

        if (weekIncludingToday.redMeat > s.redMeatMax)
            out += DayWarning("red_meat", "Красное мясо больше ${s.redMeatMax} раз за неделю.")
        if (weekIncludingToday.freeLunch > s.freeLunchMax)
            out += DayWarning("free_lunch", "Свободный обед больше ${s.freeLunchMax} раза за неделю.")
        if (weekIncludingToday.eggs > s.eggsMaxWeek)
            out += DayWarning("eggs", "Яиц целиком за неделю больше ${s.eggsMaxWeek} (холестерин).")
        return out
    }

    /** День с превышением нормы больше чем на 10% — красный маркер в журнале. */
    fun isRedDay(kcal: Double, s: AppSettings): Boolean = kcal > s.kcalTarget * s.overDayRedPct / 100.0

    /** Проверки при записи: что добавится после этой записи (для мгновенного предупреждения). */
    fun onLogWarnings(before: WeekCounters, after: WeekCounters, s: AppSettings): List<String> {
        val out = mutableListOf<String>()
        if (after.redMeat > s.redMeatMax && before.redMeat <= s.redMeatMax) out += "Это уже ${after.redMeat}-й раз красного мяса за неделю (лимит ${s.redMeatMax})."
        if (after.freeLunch > s.freeLunchMax && before.freeLunch <= s.freeLunchMax) out += "Второй свободный обед за неделю (лимит ${s.freeLunchMax})."
        if (after.proteinBars > s.maxProteinBarsPerWeek && before.proteinBars <= s.maxProteinBarsPerWeek) out += "Протеиновых батончиков за неделю больше ${s.maxProteinBarsPerWeek}."
        if (after.eggs > s.eggsMaxWeek && before.eggs <= s.eggsMaxWeek) out += "Яиц за неделю больше ${s.eggsMaxWeek}."
        return out
    }
}
