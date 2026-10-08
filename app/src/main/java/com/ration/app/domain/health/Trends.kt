package com.ration.app.domain.health

import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.LabResult
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.data.db.entity.Workout
import java.time.Instant
import java.time.ZoneId

/** Предупреждение о стабильном ухудшении (20.6): значения по окнам W0, W1, W2 и на что обратить внимание. */
data class HealthWarning(
    val key: String,
    val title: String,
    val windows: List<String>,
    val advice: String,
    val urgent: Boolean = false,
)

/** Итог проверки: предупреждения и метрики, по которым мало данных. */
data class TrendCheck(val warnings: List<HealthWarning>, val lowData: List<String>)

object Trends {
    const val URGENT_TEXT = "Повторите измерение через несколько минут. При высоком давлении с симптомами (боль в груди, одышка, " +
        "сильная головная боль, слабость, нарушение речи или зрения) звоните 101. В остальных случаях свяжитесь с врачом сегодня."

    /** Окна W0: −21…−15, W1: −14…−8, W2: −7…−1 относительно [today]. */
    fun windows(today: Long): List<LongRange> = listOf((today - 21)..(today - 15), (today - 14)..(today - 8), (today - 7)..(today - 1))

    /** Три окна, в каждом следующее хуже предыдущего. */
    private fun twoWorse(v: List<Double>, worse: (Double, Double) -> Boolean) = worse(v[0], v[1]) && worse(v[1], v[2])

    private fun f1(v: Double) = "%.1f".format(v)

    fun check(
        today: Long, zone: ZoneId, rules: HealthRules, goalLoss: Boolean,
        weights: List<WeightLog>, bp: List<BpLog>, logs: List<MealLog>, workouts: List<Workout>, labs: List<LabResult>,
    ): TrendCheck {
        val t = rules.trend
        val win = windows(today)
        val out = mutableListOf<HealthWarning>()
        val low = mutableListOf<String>()
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

        // Вес: рост при цели на снижение или падение быстрее 1% в неделю; шум 0,3 кг
        val w = win.map { r -> weights.filter { it.day in r }.map { it.kg } }
        if (w.all { it.size >= t.minMeasurements }) {
            val m = w.map { it.average() }
            val worse = { a: Double, b: Double ->
                (goalLoss && b - a > t.weightNoiseKg) || (a - b > maxOf(t.weightNoiseKg, a * t.weightFastLossPctWeek / 100))
            }
            if (twoWorse(m, worse)) out += HealthWarning("weight", "Вес", m.map { "${f1(it)} кг" },
                if (m[2] > m[0]) "Вес растёт третью неделю подряд: проверьте калорийность и записи." else "Вес снижается быстрее 1% в неделю: повысьте калорийность.")
        } else low += "вес"

        // Давление: рост среднего систолического (>3) или диастолического (>2)
        val b = win.map { r -> bp.filter { dayOf(it.atMillis) in r } }
        if (b.all { it.size >= t.minMeasurements }) {
            val sys = b.map { l -> l.map { it.systolic.toDouble() }.average() }
            val dia = b.map { l -> l.map { it.diastolic.toDouble() }.average() }
            val worse = { i: Int -> sys[i + 1] - sys[i] > t.bpSysNoise || dia[i + 1] - dia[i] > t.bpDiaNoise }
            if (worse(0) && worse(1)) out += HealthWarning("bp", "Давление", sys.indices.map { "${Math.round(sys[it])}/${Math.round(dia[it])}" },
                "Среднее давление растёт: меньше соли и переработанного, умеренная нагрузка. Обсудите с врачом.")
        } else low += "давление"

        // Активность: снижение минут тренировок больше 20%
        val act = win.map { r -> workouts.filter { it.day in r }.sumOf { it.durationMin } }
        if (act[0] > 0) {
            if (twoWorse(act) { a, c -> a > 0 && (a - c) / a * 100 > t.activityDropPct }) out += HealthWarning("activity", "Активность",
                act.map { "${Math.round(it)} мин" }, "Тренировок становится меньше: вернитесь к бассейну или дорожке.")
        } else low += "активность"

        // Питание: дни с записями (не менее 3 в окне)
        val food = win.map { r -> logs.filter { it.day in r }.groupBy { it.day } }
        if (food.all { it.size >= t.minFoodDays }) {
            val prot = food.map { d -> d.values.map { l -> l.sumOf { it.protein } }.average() }
            if (twoWorse(prot) { a, c -> a > 0 && (a - c) / a * 100 > t.proteinDropPct }) out += HealthWarning("protein", "Белок",
                prot.map { "${Math.round(it)} г" }, "Белка в среднем всё меньше: добавьте белковые позиции.")
            val kcal = food.map { d -> d.values.map { l -> l.sumOf { it.kcal } }.average() }
            if (twoWorse(kcal) { a, c -> c < a && c < t.intakeFloorKcal }) out += HealthWarning("intake", "Потребление",
                kcal.map { "${Math.round(it)} ккал" }, "Среднее потребление ниже 1500 ккал и падает: повысьте калорийность.")
        } else low += "питание"

        // Анализы: три последних результата показателя, каждое изменение — в нежелательную сторону больше 5%
        labs.groupBy { Labs.norm(it.indicator) }.forEach { (_, l) ->
            val last = l.sortedBy { it.day }.takeLast(3)
            if (last.size < 3) return@forEach
            val ind = rules.indicator(last.last().indicator)
            val e = Labs.eval(last.last(), rules)
            if (Labs.worsened(last[0].value, last[1].value, ind, e.low, e.high, t.labChangePct) &&
                Labs.worsened(last[1].value, last[2].value, ind, e.low, e.high, t.labChangePct)) {
                out += HealthWarning("lab:${last.last().indicator}", last.last().indicator, last.map { "${it.value} ${it.unit}".trim() },
                    "Показатель ухудшается при повторных анализах. Обсудите с врачом.")
            }
        }
        return TrendCheck(out, low)
    }

    /** Срочные случаи без ожидания (20.6): давление 180/120 и выше, анализ за критической границей. */
    fun urgent(rules: HealthRules, bp: List<BpLog>, labs: List<LabResult>, nowMs: Long): List<HealthWarning> {
        val out = mutableListOf<HealthWarning>()
        bp.filter { it.atMillis > nowMs - 24 * 3_600_000L }.maxByOrNull { it.atMillis }?.let {
            if (it.systolic >= rules.bp.urgentSys || it.diastolic >= rules.bp.urgentDia) {
                out += HealthWarning("urgent:bp:${it.id}", "Давление ${it.systolic}/${it.diastolic}", emptyList(), URGENT_TEXT, urgent = true)
            }
        }
        labs.groupBy { Labs.norm(it.indicator) }.values.mapNotNull { l -> l.maxByOrNull { it.day } }.forEach { r ->
            if (Labs.eval(r, rules).critical) out += HealthWarning("urgent:lab:${r.id}", "${r.indicator}: ${r.value} ${r.unit}".trim(), emptyList(),
                "Значение за критической границей. Свяжитесь с врачом сегодня.", urgent = true)
        }
        return out
    }
}
