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

/**
 * Шесть приёмов пищи дня (19.1) в фиксированном порядке: З, С, О, П, У, Е.
 * До версии 3 в базе были SNACK_1, SNACK_2 и ROAD_BAR (все между обедом и ужином) — они стали П;
 * слот С (перекус между завтраком и обедом) новый. Переименование — в MIGRATION_2_3 и [fromStored].
 */
@Serializable(with = SlotTypeSerializer::class)
enum class SlotType(val label: String, val short: String) {
    BREAKFAST("Завтрак", "З"),
    SNACK_AM("Перекус", "С"),
    LUNCH("Обед", "О"),
    SNACK_PM("Перекус", "П"),
    DINNER("Ужин", "У"),
    EVENING("Перекус", "Е");

    /** «З · Завтрак», «С · Перекус» — три перекуса различаются буквой. */
    val title: String get() = "$short · $label"

    val kinds: Set<MealKind>
        get() = when (this) {
            BREAKFAST -> setOf(MealKind.BREAKFAST)
            LUNCH -> setOf(MealKind.LUNCH_CARRY, MealKind.LUNCH_STREET)
            SNACK_AM, SNACK_PM -> setOf(MealKind.SNACK)
            DINNER -> setOf(MealKind.DINNER)
            EVENING -> setOf(MealKind.EVENING, MealKind.SNACK)
        }

    /** Основной приём: белок не меньше 30 г (правило 6.3.1). */
    val isMain: Boolean get() = this == BREAKFAST || this == LUNCH || this == DINNER
    val isSnack: Boolean get() = !isMain

    companion object {
        /** Имена слотов до схемы 3 → новые (резервные копии, настройки). */
        val LEGACY = mapOf("SNACK_1" to SNACK_PM, "SNACK_2" to SNACK_PM, "ROAD_BAR" to SNACK_PM)
        fun fromStored(name: String): SlotType? = entries.firstOrNull { it.name == name } ?: LEGACY[name]
    }
}

/** Принимает и прежние имена слотов (SNACK_1, SNACK_2, ROAD_BAR) — копии и настройки версии 2 читаются. */
object SlotTypeSerializer : kotlinx.serialization.KSerializer<SlotType> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("SlotType", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: SlotType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): SlotType {
        val n = decoder.decodeString()
        return SlotType.fromStored(n) ?: throw kotlinx.serialization.SerializationException("неизвестный слот $n")
    }
}

/** Состояние приёма на вкладке «Сегодня» (19.2): не отмечен, пропущен, записан. */
@Serializable
enum class MealSlotStatus(val label: String) { EMPTY("не отмечен"), SKIPPED("пропущен"), LOGGED("записан") }

/** Тип дня — только для старых записей day_plan (19.8: в интерфейсе типов дня нет). */
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
    const val PROCESSED = "processed"
    const val SALTY = "salty"

    /** Теги продукта, по которым считаются недельные счётчики (12.3). */
    val FOOD_TAGS = setOf(FISH, FATTY_FISH, RED_MEAT, EGG, SEAFOOD, PROCESSED, SALTY)
    /** Теги блока, не зависящие от состава. */
    val NON_FOOD_TAGS = setOf(FREE_LUNCH, BAR, PROTEIN_BAR, CEREAL_BAR, LIGHT, STREET, SHOP, HOME, CARRY, PREP, PREP_NIGHT, ASK_MEAT)
}

/** Происхождение пищевой ценности продукта (13.2.3). */
@Serializable
enum class ProductSource(val label: String) { REFERENCE("справочное"), LABEL("с этикетки"), USER("моё") }

@Serializable
enum class CookState(val label: String) { RAW("сырой"), COOKED("готовый") }

/** Роль продукта в подборе блюд (18.2). */
@Serializable
enum class FoodRole(val label: String) {
    PROTEIN("белок"), VEG("овощи"), CARB("гарнир"), FAT("жиры"), FRUIT("фрукты"),
    DAIRY("молочное"), SAUCE("соус"), SNACK("перекус"), READY("готовое"),
}

@Serializable
enum class CookMethod(val label: String, val device: String) {
    SOUSVIDE("су-вид", "🌡"), NINJA_GRILL("Ninja гриль", "🔥"), NINJA_AIRFRY("Ninja аэрогриль", "💨"),
    PAN("сковорода", "🍳"), OVEN("духовка", "♨"), BOIL("варка", "🥘"), RAW("без готовки", "🥗");

    val usesStove: Boolean get() = this == PAN || this == BOIL
    val isNinja: Boolean get() = this == NINJA_GRILL || this == NINJA_AIRFRY
}

/** Приём пищи для подбора (18.1): З / С / О / У / Е / П. */
@Serializable
enum class CookSlot(val letter: String, val label: String) {
    BREAKFAST("З", "Завтрак"), CARRY("С", "Обед с собой"), LUNCH("О", "Обед"),
    DINNER("У", "Ужин"), EVENING("Е", "Вечер"), SNACK("П", "Перекус");

    val mealKinds: Set<MealKind>
        get() = when (this) {
            BREAKFAST -> setOf(MealKind.BREAKFAST)
            CARRY -> setOf(MealKind.LUNCH_CARRY)
            LUNCH -> setOf(MealKind.LUNCH_CARRY)
            DINNER -> setOf(MealKind.DINNER)
            EVENING -> setOf(MealKind.EVENING)
            SNACK -> setOf(MealKind.SNACK)
        }

    val slotType: SlotType
        get() = when (this) {
            BREAKFAST -> SlotType.BREAKFAST
            CARRY, LUNCH -> SlotType.LUNCH
            DINNER -> SlotType.DINNER
            EVENING -> SlotType.EVENING
            SNACK -> SlotType.SNACK_PM
        }

    companion object {
        fun of(slot: SlotType): CookSlot = when (slot) {
            SlotType.BREAKFAST -> BREAKFAST
            SlotType.LUNCH -> LUNCH
            SlotType.DINNER -> DINNER
            SlotType.EVENING -> EVENING
            SlotType.SNACK_AM, SlotType.SNACK_PM -> SNACK
        }
        fun ofLetter(l: String): CookSlot? = entries.firstOrNull { it.letter == l }
    }
}
