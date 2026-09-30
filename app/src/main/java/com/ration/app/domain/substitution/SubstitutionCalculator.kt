package com.ration.app.domain.substitution

import com.ration.app.data.db.entity.CustomFood
import kotlin.math.max
import kotlin.math.roundToInt

/** Пищевая ценность заменяемого ингредиента в порции блока. */
data class NutrientTarget(val kcal: Double, val protein: Double)

data class SubstitutionProposal(
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val kcalDelta: Double,
    val proteinDelta: Double,
    /** Белка у замены меньше 60% от заменяемого — предложить другой блок. */
    val lowProtein: Boolean,
)

object SubstitutionCalculator {
    const val LOW_PROTEIN_RATIO = 0.6

    /** Ккал и белок на 100 г замены (из этикетки: на 100 г или пересчёт с порции). */
    fun per100(food: CustomFood): Pair<Double, Double> {
        val g = food.portionGrams
        val kcal = if (food.kcalPer100 > 0 || g == null || food.kcalPerPortion == null) food.kcalPer100 else food.kcalPerPortion / g * 100
        val prot = if (food.proteinPer100 > 0 || g == null || food.proteinPerPortion == null) food.proteinPer100 else food.proteinPerPortion / g * 100
        return kcal to prot
    }

    /**
     * Подбор количества q (г), при котором белок и ккал ближе всего к заменяемому:
     * минимум суммы квадратов относительных отклонений
     * ((a·q − P)/P)² + ((b·q − K)/K)², a и b — белок и ккал на грамм.
     * Решение: q = (a/P + b/K) / (a²/P² + b²/K²). Округление до шага [step] г.
     */
    fun propose(target: NutrientTarget, kcalPer100: Double, proteinPer100: Double, step: Double = 5.0): SubstitutionProposal {
        val a = proteinPer100 / 100.0
        val b = kcalPer100 / 100.0
        val p = max(target.protein, 0.1)
        val k = max(target.kcal, 1.0)
        val denom = a * a / (p * p) + b * b / (k * k)
        val raw = if (denom <= 0) 0.0 else (a / p + b / k) / denom
        val grams = max(step, (raw / step).roundToInt() * step)
        val kcal = b * grams
        val prot = a * grams
        return SubstitutionProposal(
            grams, round1(kcal), round1(prot),
            round1(kcal - target.kcal), round1(prot - target.protein),
            lowProtein = prot < target.protein * LOW_PROTEIN_RATIO,
        )
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
}
