package com.ration.app.domain.health

import com.ration.app.data.db.entity.WeightLog
import com.ration.app.domain.health.HealthConstants as C
import com.ration.app.domain.model.Sex

/** Результат расчёта ккал тренировки: значение, формула словами и предупреждения. */
data class KcalEstimate(val kcalNet: Double, val formula: String, val warnings: List<String> = emptyList())

/** Время, скорость и расстояние ходьбы: два известных → третье. */
data class WalkParams(val minutes: Double, val speedKmh: Double, val distanceM: Double)

/** Энергозатраты (20.2–20.3). Детерминированные формулы, без сети. */
object Energy {
    fun bmr(sex: Sex, kg: Double, heightCm: Double, ageYears: Int): Double =
        C.BMR_WEIGHT * kg + C.BMR_HEIGHT * heightCm - C.BMR_AGE * ageYears + if (sex == Sex.M) C.BMR_SEX_M else C.BMR_SEX_F

    /** BMR, приходящийся на [minutes] минут (для перевода «общих» ккал в чистые). */
    fun bmrForMinutes(bmrPerDay: Double, minutes: Double): Double = bmrPerDay * minutes / (24 * 60)

    /** Ввод с часов «общие» → чистые: минус BMR за то же время, не ниже нуля. */
    fun netFromTotal(totalKcal: Double, bmrPerDay: Double, minutes: Double): Double =
        (totalKcal - bmrForMinutes(bmrPerDay, minutes)).coerceAtLeast(0.0)

    /** ACSM: ходьба (3–6 км/ч), с 6,5 км/ч — формула бега и предупреждение. */
    fun walk(speedKmh: Double, inclinePct: Double, kg: Double, minutes: Double): KcalEstimate {
        val s = speedKmh * 1000 / 60 // м/мин
        val g = inclinePct / 100
        val warnings = mutableListOf<String>()
        val running = speedKmh >= C.RUN_FROM_KMH
        val vo2 = if (running) {
            warnings += "Это бег: формула ходьбы занижает, использована формула бега ACSM."
            C.RUN_HORIZONTAL * s + C.RUN_VERTICAL * s * g
        } else {
            if (speedKmh < C.WALK_MIN_KMH || speedKmh > C.WALK_MAX_KMH) warnings += "Формула ходьбы ACSM рассчитана на 3–6 км/ч."
            C.WALK_HORIZONTAL * s + C.WALK_VERTICAL * s * g
        }
        val perMin = vo2 * kg / 1000 * C.KCAL_PER_LITER_O2
        val formula = if (running) "VO2 = 0,2·S + 0,9·S·G; ккал/мин = VO2·вес/1000·5" else "VO2 = 0,1·S + 1,8·S·G; ккал/мин = VO2·вес/1000·5"
        return KcalEstimate(perMin * minutes, "$formula (S = ${"%.1f".format(s)} м/мин, G = ${"%.3f".format(g)}). Оценка ±20–30%.", warnings)
    }

    /** Темп плавания, секунд на 100 м. */
    fun swimPaceSec(distanceM: Double, minutes: Double): Double? = if (distanceM > 0) minutes * 60 / distanceM * 100 else null

    fun swimMet(paceSec: Double): Double = when {
        paceSec > C.SWIM_SLOW_PACE_SEC -> C.SWIM_MET_SLOW
        paceSec < C.SWIM_FAST_PACE_SEC -> C.SWIM_MET_FAST
        else -> C.SWIM_MET_MODERATE
    }

    /** Плавание: чистые ккал = (MET − 1) · вес · часы; стиль не учитывается. */
    fun swim(distanceM: Double, minutes: Double, kg: Double): KcalEstimate? {
        val pace = swimPaceSec(distanceM, minutes) ?: return null
        val met = swimMet(pace)
        val kcal = (met - 1) * kg * minutes / 60
        return KcalEstimate(kcal, "(MET − 1)·вес·часы, MET = ${"%.1f".format(met)} при темпе ${paceLabel(pace)} на 100 м. Оценка ±20–30%.")
    }

    fun paceLabel(sec: Double): String {
        val s = Math.round(sec).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Два из трёх (время, скорость, расстояние) → третье. null — известно меньше двух или значения нулевые. */
    fun solveWalk(minutes: Double?, speedKmh: Double?, distanceM: Double?): WalkParams? = when {
        minutes != null && speedKmh != null && minutes > 0 && speedKmh > 0 -> WalkParams(minutes, speedKmh, distanceM ?: (speedKmh * 1000 * minutes / 60))
        minutes != null && distanceM != null && minutes > 0 && distanceM > 0 -> WalkParams(minutes, distanceM / 1000 / (minutes / 60), distanceM)
        speedKmh != null && distanceM != null && speedKmh > 0 && distanceM > 0 -> WalkParams(distanceM / 1000 / speedKmh * 60, speedKmh, distanceM)
        else -> null
    }

    /** Последний вес не позже дня (вес для расчёта тренировки и BMR дня). */
    fun weightOn(weights: List<WeightLog>, day: Long): Double? = weights.filter { it.day <= day }.maxByOrNull { it.day }?.kg
}
