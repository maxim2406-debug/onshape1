package com.ration.app.domain.health

import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.LabResult
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.data.db.entity.Workout
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.model.Tags
import com.ration.app.domain.plan.WeekCounters
import java.time.Instant
import java.time.ZoneId

enum class AreaColor { GREEN, YELLOW, RED, NONE }

data class Area(val title: String, val color: AreaColor, val summary: String)

data class ConditionReport(val areas: List<Area>, val advice: List<Advice>)

/**
 * «Состояние» (20.4): пять областей (питание, вес, давление, активность, анализы) и до 5 рекомендаций.
 * Правила детерминированные, пороги — health_rules.json. Без лекарств и дозировок; темы лечения — «Обсудите с врачом».
 */
object Condition {
    const val DISCLAIMER = "Информационная оценка по вашим записям. Не диагноз и не замена врача."
    const val DOCTOR = "Обсудите с врачом."

    fun report(
        today: Long, zone: ZoneId, s: AppSettings, rules: HealthRules,
        weights: List<WeightLog>, bp: List<BpLog>, logs: List<MealLog>, quick: List<QuickLog>, workouts: List<Workout>, labs: List<LabResult>,
    ): ConditionReport {
        val from = today - 6
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
        val advice = mutableListOf<Pair<Int, Advice>>() // приоритет → совет

        // Давление: среднее за 7 дней по категориям; повторные 140/90 и выше — красный
        val bp7 = bp.filter { dayOf(it.atMillis) in from..today }
        val r = rules.bp
        val bpHigh = bp7.count { it.systolic >= r.highSys || it.diastolic >= r.highDia }
        val bpArea = if (bp7.isEmpty()) Area("Давление", AreaColor.NONE, "нет измерений за 7 дней") else {
            val sys = bp7.map { it.systolic }.average(); val dia = bp7.map { it.diastolic }.average()
            val color = when {
                bpHigh >= r.repeatHighCount || sys >= r.highSys || dia >= r.highDia -> AreaColor.RED
                sys >= r.normalSys || dia >= r.normalDia -> AreaColor.YELLOW
                else -> AreaColor.GREEN
            }
            Area("Давление", color, "среднее ${Math.round(sys)}/${Math.round(dia)} за 7 дней, ${bp7.size} изм." +
                if (bpHigh >= r.repeatHighCount) "; $bpHigh раз 140/90 и выше — $DOCTOR" else "")
        }
        val bpRaised = bpArea.color == AreaColor.RED || bpArea.color == AreaColor.YELLOW

        // Вес: тренд против цели
        val trend = FormAnalysis.trendKgWeek(weights, today)
        val current = Energy.weightOn(weights, today)
        val tooFast = trend != null && current != null && -trend / current * 100 > HealthConstants.MAX_LOSS_PCT_PER_WEEK
        val weightArea = when {
            trend == null || current == null -> Area("Вес", AreaColor.NONE, "мало измерений для тренда")
            current <= s.targetWeight -> Area("Вес", AreaColor.GREEN, "цель достигнута: ${"%.1f".format(current)} кг")
            tooFast -> Area("Вес", AreaColor.YELLOW, "снижение ${"%.2f".format(-trend)} кг/нед — быстрее 1% массы")
            trend < -HealthConstants.FLAT_RATE_KG_WEEK -> Area("Вес", AreaColor.GREEN, "снижение ${"%.2f".format(-trend)} кг/нед")
            trend > HealthConstants.FLAT_RATE_KG_WEEK -> Area("Вес", AreaColor.RED, "рост ${"%.2f".format(trend)} кг/нед при цели на снижение")
            else -> Area("Вес", AreaColor.YELLOW, "вес почти не меняется")
        }

        // Питание: белок и вода против целей, доля processed/salty, рыба в неделю
        val logs7 = logs.filter { it.day in from..today }
        val foodDays = logs7.groupBy { it.day }
        val nutritionArea = if (foodDays.isEmpty()) Area("Питание", AreaColor.NONE, "нет записей за 7 дней") else {
            val protein = foodDays.values.map { l -> l.sumOf { it.protein } }.average()
            val water = (from..today).map { d -> quick.filter { it.day == d && it.type == QuickType.WATER }.sumOf { it.amount } }.average()
            // позиции: строки состава записи, у старых записей без состава — сама запись
            fun bad(tags: List<String>) = Tags.PROCESSED in tags || Tags.SALTY in tags
            val positions = logs7.sumOf { l -> maxOf(l.items.size, 1) }
            val tagged = logs7.sumOf { l -> if (l.items.isEmpty()) (if (bad(l.tags)) 1 else 0) else l.items.count { bad(it.tags) } }
            val share = if (positions == 0) 0.0 else tagged * 100.0 / positions
            val fish = WeekCounters.of(logs7, emptyList()).fish
            val pPct = protein / s.proteinTarget * 100
            val wPct = water / s.waterGoalMl * 100
            val n = rules.nutrition
            val color = when {
                pPct < 80 || share >= n.processedSaltyRedPct -> AreaColor.RED
                pPct < 90 || wPct < 80 || share >= n.processedSaltyYellowPct || fish < n.fishPerWeekMin -> AreaColor.YELLOW
                else -> AreaColor.GREEN
            }
            if (pPct < 80) advice += 3 to Advice("Добавьте белковые позиции (варианты — «Что приготовить» в конструкторе).", "белок ${Math.round(protein)} г при цели ${s.proteinTarget} г")
            if (wPct < 80) advice += 6 to Advice("Пейте больше воды.", "в среднем ${Math.round(water)} мл при цели ${s.waterGoalMl}")
            Area("Питание", color, "белок ${Math.round(protein)} г, вода ${Math.round(water)} мл, переработанное и солёное ${Math.round(share)}%, рыба $fish раз")
                .also { if (bpRaised && share >= n.processedSaltyYellowPct) advice += 1 to Advice("Меньше соли и переработанного, больше овощей и рыбы.", "давление выше нормы и ${Math.round(share)}% позиций — переработанные или солёные") }
        }

        // Активность: минуты тренировок за 7 дней
        val minutes = workouts.filter { it.day in from..today }.sumOf { it.durationMin }
        val a = rules.activity
        val activityArea = Area("Активность", when {
            minutes >= a.greenMinWeek -> AreaColor.GREEN
            minutes >= a.yellowMinWeek -> AreaColor.YELLOW
            else -> AreaColor.RED
        }, "${Math.round(minutes)} мин тренировок за 7 дней")
        if (minutes < a.greenMinWeek && !tooFast) {
            advice += 4 to if (bpRaised) Advice("Только умеренная нагрузка: ходьба, спокойное плавание. Согласуйте нагрузку с врачом.",
                "давление выше нормы, ${Math.round(minutes)} мин тренировок за неделю")
            else Advice("Добавьте сессию бассейна или дорожки.", "${Math.round(minutes)} мин тренировок за неделю (ориентир 150)")
        }
        if (tooFast) advice += 2 to Advice("Не увеличивайте нагрузку и повысьте калорийность.", "вес снижается быстрее 1% массы в неделю")

        // Анализы: вне нормы и ухудшение при повторных результатах
        val latest = labs.groupBy { Labs.norm(it.indicator) }.values.mapNotNull { l -> l.maxByOrNull { it.day } }
        val out = latest.filter { Labs.eval(it, rules).status.let { st -> st == LabStatus.HIGH || st == LabStatus.LOW } }
        val worsening = latest.filter { r0 ->
            val prev = labs.filter { Labs.norm(it.indicator) == Labs.norm(r0.indicator) && it.day < r0.day }.maxByOrNull { it.day } ?: return@filter false
            val e = Labs.eval(r0, rules)
            Labs.worsened(prev.value, r0.value, rules.indicator(r0.indicator), e.low, e.high, rules.trend.labChangePct)
        }
        val critical = latest.any { Labs.eval(it, rules).critical }
        val labArea = if (latest.isEmpty()) Area("Анализы", AreaColor.NONE, "нет результатов") else Area("Анализы", when {
            critical || out.any { it in worsening } -> AreaColor.RED
            out.isNotEmpty() || worsening.isNotEmpty() -> AreaColor.YELLOW
            else -> AreaColor.GREEN
        }, if (out.isEmpty()) "все последние результаты в норме" else "вне нормы: " + out.joinToString(", ") { it.indicator })
        val liverOrLipids = out.any { Labs.norm(it.indicator) in setOf("алт", "аст", "общий холестерин", "лпнп", "триглицериды", "ггт") }
        if (liverOrLipids) advice += 1 to Advice("Меньше насыщенных жиров и переработанного, больше рыбы, клетчатки и овощей. $DOCTOR",
            "вне нормы: " + out.joinToString(", ") { it.indicator })
        if (bpArea.color == AreaColor.RED) advice += 0 to Advice("Повторные высокие значения давления. $DOCTOR", bpArea.summary)

        val top = advice.sortedBy { it.first }.map { it.second }.distinctBy { it.text }.take(5)
        return ConditionReport(listOf(nutritionArea, weightArea, bpArea, activityArea, labArea), top)
    }
}
