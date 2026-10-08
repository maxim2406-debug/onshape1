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

    /**
     * Время шести приёмов (19.1, 19.8): одно расписание без типов дня.
     * null — ещё не перенесено из прежних расписаний А/Б (см. [SlotSchedule.migrate]).
     */
    val slotTimes: Map<SlotType, Int>? = null,
    /** Прежние расписания типов дня Б и А (до версии 3). Только для переноса, не редактируются. */
    val slotTimesB: Map<String, Int> = mapOf("BREAKFAST" to 9 * 60, "LUNCH" to 14 * 60, "DINNER" to 20 * 60 + 30, "EVENING" to 22 * 60),
    val slotTimesA: Map<String, Int> = mapOf("LUNCH" to 12 * 60, "SNACK_1" to 15 * 60, "SNACK_2" to 17 * 60 + 30, "DINNER" to 20 * 60 + 30, "EVENING" to 22 * 60),
    val roadBarTime: Int = 16 * 60 + 30,
    /** Напоминания «нет записи» для перекусов С, П, Е (основные приёмы напоминают всегда). */
    val snackReminders: Boolean = false,

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
    /** Профиль для расчётов формы (20.1). */
    val sex: Sex = Sex.M,
    val ageYears: Int = 41,
    /** Целевой вес; null — стартовый вес минус цель снижения (95 − 15). */
    val targetWeightKg: Double? = null,
    /** Коэффициент активности вне тренировок (1,2–1,5). */
    val pal: Double = 1.3,
    /** Желаемый темп снижения, кг в неделю. */
    val lossPaceKgPerWeek: Double = 0.5,
    /** Скрытые предупреждения о здоровье: ключ → до какого дня (epochDay) не показывать (20.6). */
    val healthWarningsDismissed: Map<String, Long> = emptyMap(),
    /** День последнего уведомления о предупреждениях здоровья (не чаще раза в 3 дня). */
    val healthWarnNotifiedDay: Long? = null,

    val claudeApiEnabled: Boolean = false,
    val claudeModel: String = "claude-opus-5-5",
    val claudeWarningAccepted: Boolean = false,
    val biometricLock: Boolean = false,
    /** Быстрые кнопки веса для овощей в конструкторе (12.1.4). */
    val vegQuickGrams: List<Int> = listOf(100, 200, 300),
    /** Выбранная сортировка библиотеки (16.2). */
    val librarySort: String = "FREQUENT",
    /** Скрытые варианты «Что приготовить». */
    val hiddenSuggestions: List<String> = emptyList(),
    val seeded: Boolean = false,
) {
    val targetWeight: Double get() = targetWeightKg ?: (startWeightKg - weightLossGoalKg)

    /** Действующее расписание шести слотов. */
    fun times(): Map<SlotType, Int> = slotTimes ?: SlotSchedule.fromLegacy(slotTimesB, slotTimesA, DayType.B)

    companion object {
        val CLAUDE_MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5", "claude-fable-5-1")
    }
}

/** Перенос расписаний типов дня в одно расписание (19.8). Новых чисел не придумывает. */
object SlotSchedule {
    /**
     * Слоты берутся из расписания типа дня, выбранного на сегодня ([today]); недостающие — из второго расписания.
     * П — первый перекус после обеда (SNACK_1, затем SNACK_2). С раньше не было: середина между З и О,
     * округлённая до 30 минут. Порядок времени З < С < О < П < У < Е сохраняется.
     */
    fun fromLegacy(b: Map<String, Int>, a: Map<String, Int>, today: DayType?): Map<SlotType, Int> {
        val primary = if (today == DayType.A) a else b
        val secondary = if (today == DayType.A) b else a
        fun pick(vararg keys: String): Int? = keys.firstNotNullOfOrNull { primary[it] } ?: keys.firstNotNullOfOrNull { secondary[it] }
        val z = pick("BREAKFAST") ?: 9 * 60
        val o = pick("LUNCH") ?: 14 * 60
        val p = pick("SNACK_1", "SNACK_2", "ROAD_BAR") ?: 15 * 60
        val u = pick("DINNER") ?: 20 * 60 + 30
        val e = pick("EVENING") ?: 22 * 60
        val c = ((z + o) / 2 / 30) * 30
        return linkedMapOf(
            SlotType.BREAKFAST to z, SlotType.SNACK_AM to c, SlotType.LUNCH to o,
            SlotType.SNACK_PM to p, SlotType.DINNER to u, SlotType.EVENING to e,
        )
    }

    fun migrate(s: AppSettings, today: DayType?): AppSettings =
        if (s.slotTimes != null) s else s.copy(slotTimes = fromLegacy(s.slotTimesB, s.slotTimesA, today))
}
