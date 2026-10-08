package com.ration.app.domain.health

/**
 * Константы расчётов раздела 20. Источники — в README («Источники формул и порогов»).
 * Все расчёты — оценка, погрешность энергозатрат ±20–30%.
 */
object HealthConstants {
    // Миффлин–Сан-Жеор (1990): BMR = 10·вес + 6,25·рост − 5·возраст + s, s = +5 (м) / −161 (ж)
    const val BMR_WEIGHT = 10.0
    const val BMR_HEIGHT = 6.25
    const val BMR_AGE = 5.0
    const val BMR_SEX_M = 5.0
    const val BMR_SEX_F = -161.0

    // ACSM Guidelines (метаболические уравнения): ходьба VO2net = 0,1·S + 1,8·S·G; бег VO2net = 0,2·S + 0,9·S·G
    const val WALK_HORIZONTAL = 0.1
    const val WALK_VERTICAL = 1.8
    const val RUN_HORIZONTAL = 0.2
    const val RUN_VERTICAL = 0.9
    /** ~5 ккал на литр O2. */
    const val KCAL_PER_LITER_O2 = 5.0
    const val WALK_MIN_KMH = 3.0
    const val WALK_MAX_KMH = 6.0
    /** С этой скорости — формула бега и предупреждение. */
    const val RUN_FROM_KMH = 6.5

    // Плавание, Compendium of Physical Activities (Ainsworth 2011): MET по темпу на 100 м
    const val SWIM_MET_SLOW = 5.8      // медленнее 3:00 / 100 м
    const val SWIM_MET_MODERATE = 8.3  // 2:00–3:00
    const val SWIM_MET_FAST = 9.8      // быстрее 2:00
    const val SWIM_SLOW_PACE_SEC = 180.0
    const val SWIM_FAST_PACE_SEC = 120.0

    /** ≈7700 ккал на 1 кг изменения массы (приближение Wishnofsky). */
    const val KCAL_PER_KG = 7700.0
    const val PAL_MIN = 1.2
    const val PAL_MAX = 1.5

    // Анализ формы (20.3)
    const val MIN_INTAKE_KCAL = 1500.0
    const val MAX_DEFICIT_KCAL = 1000.0
    const val MAX_LOSS_PCT_PER_WEEK = 1.0
    /** |темп| ниже — «почти ноль». */
    const val FLAT_RATE_KG_WEEK = 0.1
    /** Дефицит «на бумаге», при котором нулевой темп подозрителен. */
    const val PAPER_DEFICIT_KCAL = 300.0
    const val LOW_SHARE_PCT = 80.0
    /** ВОЗ: 150 минут умеренной активности в неделю. */
    const val ACTIVITY_MIN_PER_WEEK = 150.0
    const val CALIBRATION_MIN_DAYS = 14
    const val CALIBRATION_FOOD_SHARE = 0.7
    const val CALIBRATION_MIN_WEIGHTS = 6
    const val CALIBRATION_DIFF = 0.10
    const val SMOOTH_DAYS = 7
    const val TREND_DAYS = 14

    // Рекомендация «по форме» на «Сегодня» (20.5)
    const val TODAY_WORKOUT_SHARE = 0.5
    const val FORM_MIN_KCAL = 1500.0
    const val PROTEIN_PER_KG_LOW = 1.6
    const val PROTEIN_PER_KG_HIGH = 2.0
    const val PROTEIN_ROUND = 5
}
