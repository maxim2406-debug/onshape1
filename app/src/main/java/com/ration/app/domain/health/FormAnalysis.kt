package com.ration.app.domain.health

import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.data.db.entity.Workout
import com.ration.app.domain.health.HealthConstants as C
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.QuickType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Недостающие данные профиля: расчёт не выполняется, значения не придумываются (20.1). */
data class Profile(val bmrPerDay: Double, val pal: Double, val weightKg: Double, val targetKg: Double)

object ProfileCheck {
    /** Профиль для дня или список недостающего. */
    fun profile(s: AppSettings, weights: List<WeightLog>, day: Long): Pair<Profile?, List<String>> {
        val missing = mutableListOf<String>()
        val kg = Energy.weightOn(weights, day)
        if (kg == null) missing += "вес (запишите в «Вес и давление»)"
        if (s.heightCm !in 100..250) missing += "рост"
        if (s.ageYears !in 14..110) missing += "возраст"
        if (s.pal !in C.PAL_MIN..C.PAL_MAX) missing += "коэффициент активности 1,2–1,5"
        if (missing.isNotEmpty() || kg == null) return null to missing
        return Profile(Energy.bmr(s.sex, kg, s.heightCm.toDouble(), s.ageYears), s.pal, kg, s.targetWeight) to emptyList()
    }
}

/** День формы: BMR, бытовой расход (BMR·PAL − BMR), тренировки, всего, получено, баланс. */
data class FormDay(
    val day: Long,
    val bmr: Double,
    val baseKcal: Double,
    val workoutKcal: Double,
    val intakeKcal: Double,
    val hasFood: Boolean,
    val workoutMin: Double,
    val protein: Double,
    val waterMl: Double,
) {
    val tdee get() = baseKcal + workoutKcal
    val household get() = baseKcal - bmr
    val balance get() = intakeKcal - tdee
}

data class Calibration(val observedTdee: Double, val calcTdee: Double, val suggestedPal: Double?)

data class Advice(val text: String, val why: String)

data class FormReport(
    val days: List<FormDay>,
    /** Дни с едой — только они в средних. */
    val foodDays: Int,
    val avgIntake: Double?,
    val avgTdee: Double?,
    val avgBalance: Double?,
    /** Прогноз по балансу, кг в неделю (минус — снижение). */
    val forecastKgWeek: Double?,
    /** Фактический тренд по сглаженному весу за 14 дней, кг в неделю. */
    val trendKgWeek: Double?,
    val weeksToTarget: Double?,
    val calibration: Calibration?,
    val advice: List<Advice>,
)

object FormAnalysis {
    fun days(
        from: Long, to: Long, s: AppSettings, weights: List<WeightLog>, logs: List<MealLog>,
        workouts: List<Workout>, quick: List<QuickLog>,
    ): List<FormDay> = (from..to).mapNotNull { d ->
        val (p, _) = ProfileCheck.profile(s, weights, d)
        p ?: return@mapNotNull null
        val dayLogs = logs.filter { it.day == d }
        val w = workouts.filter { it.day == d }
        FormDay(
            day = d, bmr = p.bmrPerDay, baseKcal = p.bmrPerDay * p.pal, workoutKcal = w.sumOf { it.kcalNet },
            intakeKcal = dayLogs.sumOf { it.kcal }, hasFood = dayLogs.isNotEmpty(), workoutMin = w.sumOf { it.durationMin },
            protein = dayLogs.sumOf { it.protein },
            waterMl = quick.filter { it.day == d && it.type == QuickType.WATER }.sumOf { it.amount },
        )
    }

    /** Сглаживание: среднее измерений за [C.SMOOTH_DAYS] дней, включая день измерения. */
    fun smoothed(weights: List<WeightLog>): List<Pair<Long, Double>> {
        val sorted = weights.sortedBy { it.day }
        return sorted.map { w -> w.day to sorted.filter { it.day in (w.day - C.SMOOTH_DAYS + 1)..w.day }.map { it.kg }.average() }
    }

    /** Наклон линейной регрессии (кг/день); null — меньше двух точек. */
    fun slopePerDay(points: List<Pair<Long, Double>>): Double? {
        if (points.size < 2 || points.first().first == points.last().first) return null
        val mx = points.map { it.first.toDouble() }.average()
        val my = points.map { it.second }.average()
        val num = points.sumOf { (it.first - mx) * (it.second - my) }
        val den = points.sumOf { (it.first - mx) * (it.first - mx) }
        return if (den == 0.0) null else num / den
    }

    /** Тренд по сглаженному ряду за 14 дней до [to], кг в неделю. */
    fun trendKgWeek(weights: List<WeightLog>, to: Long): Double? =
        slopePerDay(smoothed(weights).filter { it.first in (to - C.TREND_DAYS + 1)..to })?.times(7)

    fun forecastKgWeek(avgBalance: Double): Double = avgBalance * 7 / C.KCAL_PER_KG

    fun report(
        from: Long, to: Long, s: AppSettings, weights: List<WeightLog>, logs: List<MealLog>,
        workouts: List<Workout>, quick: List<QuickLog>,
    ): FormReport {
        val days = days(from, to, s, weights, logs, workouts, quick)
        val food = days.filter { it.hasFood }
        val avgIntake = food.takeIf { it.isNotEmpty() }?.map { it.intakeKcal }?.average()
        val avgTdee = food.takeIf { it.isNotEmpty() }?.map { it.tdee }?.average()
        val avgBalance = food.takeIf { it.isNotEmpty() }?.map { it.balance }?.average()
        val forecast = avgBalance?.let(::forecastKgWeek)
        val trend = trendKgWeek(weights, to)
        val current = Energy.weightOn(weights, to)
        val rate = trend ?: forecast
        val weeks = if (current != null && rate != null && rate < -1e-6 && current > s.targetWeight) (current - s.targetWeight) / -rate else null
        val calib = calibration(from, to, days, weights, avgTdee, s)
        return FormReport(days, food.size, avgIntake, avgTdee, avgBalance, forecast, trend, weeks, calib, advice(days, s, trend, current, avgBalance, avgIntake))
    }

    /**
     * Калибровка (20.3): 14+ дней, ≥70% дней с едой, ≥6 измерений веса. Наблюдаемый расход = средняя еда − 7700·Δвес/дни.
     * PAL предлагается (не применяется) при расхождении с расчётом больше 10%.
     */
    fun calibration(from: Long, to: Long, days: List<FormDay>, weights: List<WeightLog>, calcTdee: Double?, s: AppSettings): Calibration? {
        val n = (to - from + 1).toInt()
        if (n < C.CALIBRATION_MIN_DAYS || calcTdee == null) return null
        val food = days.filter { it.hasFood }
        if (food.size < n * C.CALIBRATION_FOOD_SHARE) return null
        val w = weights.filter { it.day in from..to }
        if (w.size < C.CALIBRATION_MIN_WEIGHTS) return null
        val slope = slopePerDay(smoothed(weights).filter { it.first in from..to }) ?: return null
        val observed = food.map { it.intakeKcal }.average() - C.KCAL_PER_KG * slope
        val suggested = if (abs(observed - calcTdee) / calcTdee > C.CALIBRATION_DIFF) {
            val bmr = food.map { it.bmr }.average()
            val workouts = food.map { it.workoutKcal }.average()
            ((observed - workouts) / bmr).coerceIn(C.PAL_MIN, C.PAL_MAX)
        } else null
        return Calibration(observed, calcTdee, suggested)
    }

    /** Общие рекомендации по форме (правила 20.3), у каждой — из каких данных она вытекла. */
    fun advice(days: List<FormDay>, s: AppSettings, trend: Double?, weight: Double?, avgBalance: Double?, avgIntake: Double?): List<Advice> {
        val out = mutableListOf<Advice>()
        val food = days.filter { it.hasFood }
        val lossPct = if (trend != null && weight != null && weight > 0) -trend / weight * 100 else null
        if ((lossPct != null && lossPct > C.MAX_LOSS_PCT_PER_WEEK) || (avgBalance != null && -avgBalance > C.MAX_DEFICIT_KCAL) ||
            (avgIntake != null && avgIntake < C.MIN_INTAKE_KCAL)) {
            out += Advice("Слишком быстро: повысьте калорийность.", listOfNotNull(
                lossPct?.takeIf { it > C.MAX_LOSS_PCT_PER_WEEK }?.let { "потеря ${"%.1f".format(it)}% массы в неделю (больше 1%)" },
                avgBalance?.takeIf { -it > C.MAX_DEFICIT_KCAL }?.let { "дефицит ${Math.round(-it)} ккал в день (больше 1000)" },
                avgIntake?.takeIf { it < C.MIN_INTAKE_KCAL }?.let { "в среднем ${Math.round(it)} ккал в день (меньше 1500)" },
            ).joinToString("; "))
        }
        if (trend != null && abs(trend) < C.FLAT_RATE_KG_WEEK && avgBalance != null && -avgBalance > C.PAPER_DEFICIT_KCAL) {
            out += Advice("Вес почти не меняется при дефиците на бумаге: проверьте точность записей и коэффициент активности, ориентируйтесь на калибровку.",
                "тренд ${"%.2f".format(trend)} кг/нед при дефиците ${Math.round(-avgBalance)} ккал в день")
        }
        if (food.isNotEmpty()) {
            val p = food.map { it.protein }.average()
            if (p < s.proteinTarget * C.LOW_SHARE_PCT / 100) out += Advice("Сделайте акцент на белок (варианты — «Что приготовить» в конструкторе).",
                "белок в среднем ${Math.round(p)} г при цели ${s.proteinTarget} г")
        }
        if (days.isNotEmpty()) {
            val water = days.map { it.waterMl }.average()
            if (water < s.waterGoalMl * C.LOW_SHARE_PCT / 100) out += Advice("Пейте больше воды.", "в среднем ${Math.round(water)} мл при цели ${s.waterGoalMl}")
            val minPerWeek = days.sumOf { it.workoutMin } / days.size * 7
            if (minPerWeek < C.ACTIVITY_MIN_PER_WEEK) out += Advice("Добавьте тренировку: бассейн или дорожку.",
                "${Math.round(minPerWeek)} минут в неделю (ориентир 150)")
        }
        return out
    }
}

/** Рекомендация «по форме» для шкал «Сегодня» (20.5). Цель дня сама не меняется. */
data class FormTarget(val kcal: Double, val proteinLow: Int, val proteinHigh: Int, val deficit: Double, val tdee: Double)

object FormTargets {
    fun today(p: Profile, s: AppSettings, todayWorkoutsNet: Double): FormTarget {
        val tdee = p.bmrPerDay * p.pal + C.TODAY_WORKOUT_SHARE * todayWorkoutsNet
        val deficit = s.lossPaceKgPerWeek * C.KCAL_PER_KG / 7
        val kcal = max(tdee - deficit, max(C.FORM_MIN_KCAL, p.bmrPerDay))
        fun r5(v: Double) = ((v / C.PROTEIN_ROUND).roundToInt() * C.PROTEIN_ROUND)
        return FormTarget(kcal, r5(C.PROTEIN_PER_KG_LOW * p.targetKg), r5(C.PROTEIN_PER_KG_HIGH * p.targetKg), deficit, tdee)
    }
}
