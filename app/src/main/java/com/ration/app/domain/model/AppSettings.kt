package com.ration.app.domain.model

import kotlinx.serialization.Serializable

/** Все редактируемые параметры. Время — минуты от полуночи. */
@Serializable
data class AppSettings(
    val kcalMin: Int = 1900,
    val kcalMax: Int = 2100,
    val kcalTarget: Int = 2000,
    val proteinMin: Int = 120,
    val proteinMax: Int = 140,
    val proteinTarget: Int = 130,
    val waterGoalMl: Int = 2000,
    val waterCheckMinMl: Int = 1000,
    val waterCheckTime: Int = 15 * 60,

    val slotTimesB: Map<SlotType, Int> = mapOf(
        SlotType.BREAKFAST to 9 * 60,
        SlotType.LUNCH to 14 * 60,
        SlotType.DINNER to 20 * 60 + 30,
        SlotType.EVENING to 22 * 60,
    ),
    val slotTimesA: Map<SlotType, Int> = mapOf(
        SlotType.LUNCH to 12 * 60,
        SlotType.SNACK_1 to 15 * 60,
        SlotType.SNACK_2 to 17 * 60 + 30,
        SlotType.DINNER to 20 * 60 + 30,
        SlotType.EVENING to 22 * 60,
    ),
    val roadBarTime: Int = 16 * 60 + 30,

    /** Контрольные суммы 6.2 (без Е и фрукта). */
    val checksumBMin: Int = 1550,
    val checksumBMax: Int = 1750,
    val checksumAMin: Int = 1450,
    val checksumAMax: Int = 1650,
    val fruitKcal: Int = 80,

    val buyThresholdPct: Int = 40,
    val urgentThresholdPct: Int = 20,
    val markReminderDelayMin: Int = 120,
    val snoozeMin: Int = 30,
    val forecastTime: Int = 21 * 60 + 30,
    val shoppingCheckTime: Int = 18 * 60,
    val quietStart: Int = 23 * 60,
    val quietEnd: Int = 7 * 60,
    /** ISO: 1 = понедельник … 7 = воскресенье. */
    val weighDay: Int = 7,
    val weighTime: Int = 7 * 60 + 30,
    val prepDay: Int = 6,
    val prepTime: Int = 12 * 60,
    val eatTodayTime: Int = 8 * 60,
    val fishReminderDay: Int = 4,
    val fishReminderTime: Int = 12 * 60,
    val coffeeLimit: Int = 14 * 60,
    val bpMorning: Int = 8 * 60,
    val bpEvening: Int = 20 * 60,
    /** Серия измерений давления включена до этого дня (epochDay), null — выключена. */
    val bpSeriesUntilDay: Long? = null,

    val calendarHint: Boolean = false,
    /** Событие длиннее 30 минут, начинающееся до этого времени → тип А. */
    val calendarCutoff: Int = 11 * 60,
    val calendarMinEventMin: Int = 30,

    val earlyOverLimitTime: Int = 15 * 60,
    val earlyOverPct: Int = 70,
    val morningOverTime: Int = 12 * 60,
    val morningOverPct: Int = 60,
    val lowProteinTime: Int = 19 * 60,
    val lowProteinG: Int = 75,
    val overDayRedPct: Int = 110,

    val maxBarsPerDay: Int = 2,
    val maxProteinBarsPerDay: Int = 1,
    val maxProteinBarsPerWeek: Int = 4,
    val fishTargetMin: Int = 4,
    val fishTargetMax: Int = 5,
    val fattyFishMin: Int = 2,
    val redMeatMax: Int = 2,
    val freeLunchMax: Int = 1,
    val eggsMaxWeek: Int = 10,
    /** ISO-день начала недели для счётчиков. */
    val weekStart: Int = 1,

    val startWeightKg: Double = 95.0,
    val weightLossGoalKg: Double = 15.0,
    val heightCm: Int = 179,

    val claudeApiEnabled: Boolean = false,
    val claudeModel: String = "claude-opus-5-5",
    val claudeWarningAccepted: Boolean = false,
    val biometricLock: Boolean = false,
    val seeded: Boolean = false,
) {
    fun slotTimes(type: DayType): Map<SlotType, Int> = if (type == DayType.A) slotTimesA else slotTimesB

    companion object {
        val CLAUDE_MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5", "claude-fable-5-1")
    }
}
