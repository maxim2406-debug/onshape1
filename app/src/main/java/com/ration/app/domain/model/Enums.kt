package com.ration.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MeasureUnit(val label: String) {
    G("г"), ML("мл"), PCS("шт");

    companion object {
        fun fromLabel(s: String): MeasureUnit? = when (s.trim().lowercase()) {
            "г", "g", "гр" -> G
            "мл", "ml" -> ML
            "шт", "pcs", "pc", "шт." -> PCS
            else -> null
        }
    }
}

@Serializable
enum class Storage(val label: String) { FRIDGE("холодильник"), FREEZER("морозилка"), DRY("сухое") }

/** Тип приёма пищи, к которому относится блок. */
@Serializable
enum class MealKind(val label: String, val letter: String) {
    BREAKFAST("Завтрак", "З"),
    LUNCH_CARRY("Обед с собой", "С"),
    LUNCH_STREET("Обед на улице", "О"),
    SNACK("Перекус", "П"),
    DINNER("Ужин", "У"),
    EVENING("Вечер", "Е"),
}

/** Слот дня. */
@Serializable
enum class SlotType(val label: String, val short: String) {
    BREAKFAST("Завтрак", "З"),
    LUNCH("Обед", "О"),
    SNACK_1("Перекус 1", "П"),
    SNACK_2("Перекус 2", "П"),
    ROAD_BAR("Батончик в дороге", "П"),
    DINNER("Ужин", "У"),
    EVENING("Вечер", "Е");

    val kinds: Set<MealKind>
        get() = when (this) {
            BREAKFAST -> setOf(MealKind.BREAKFAST)
            LUNCH -> setOf(MealKind.LUNCH_CARRY, MealKind.LUNCH_STREET)
            SNACK_1, SNACK_2, ROAD_BAR -> setOf(MealKind.SNACK)
            DINNER -> setOf(MealKind.DINNER)
            EVENING -> setOf(MealKind.EVENING)
        }

    /** Основной приём: белок не меньше 30 г (правило 6.3.1). */
    val isMain: Boolean get() = this == BREAKFAST || this == LUNCH || this == DINNER
    val isSnack: Boolean get() = this == SNACK_1 || this == SNACK_2 || this == ROAD_BAR
}

@Serializable
enum class DayType(val label: String) { A("А"), B("Б") }

@Serializable
enum class SlotStatus(val label: String) {
    PLANNED("запланирован"), EATEN("съеден"), SKIPPED("пропущен"), REPLACED("заменён")
}

@Serializable
enum class MealSource(val label: String) {
    PLAN("по плану"), CUSTOM("свой"), QUICK("быстрая кнопка"), BAR("батончик"), BLOCK("блок каталога")
}

@Serializable
enum class QuickType(val label: String) { WATER("Вода"), ESPRESSO("Эспрессо"), ALCOHOL("Алкоголь") }

@Serializable
enum class MeatChoice(val label: String) { CHICKEN("курица"), RED("красное мясо") }

object Tags {
    const val FISH = "fish"
    const val FATTY_FISH = "fatty_fish"
    const val SEAFOOD = "seafood"
    const val RED_MEAT = "red_meat"
    const val FREE_LUNCH = "free_lunch"
    const val BAR = "bar"
    const val EGG = "egg"
    const val PREP = "prep"
    const val HOME = "home"
    const val SHOP = "shop"
    const val STREET = "street"
    const val CARRY = "carry"
    const val PREP_NIGHT = "prep_night"
    const val LIGHT = "light"
    const val ASK_MEAT = "ask_meat"
    const val PROTEIN_BAR = "protein_bar"
    const val CEREAL_BAR = "cereal_bar"
}
