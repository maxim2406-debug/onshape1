package com.ration.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ration.app.domain.model.CookMethod
import com.ration.app.domain.model.CookState
import com.ration.app.domain.model.DocType
import com.ration.app.domain.model.KcalSource
import com.ration.app.domain.model.WorkoutType
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.FoodRole
import com.ration.app.domain.model.ProductSource
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.model.MealSlotStatus
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.MeatChoice
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Storage
import kotlinx.serialization.Serializable

/*
 * Соглашения по времени: day = LocalDate.toEpochDay(), atMillis = epoch millis,
 * minuteOfDay = минуты от полуночи в часовом поясе устройства.
 * Пищевая ценность продуктов хранится на 100 г (для «шт» пересчёт через gramsPerPiece).
 */

@Serializable
@Entity(tableName = "product", indices = [Index(value = ["key"], unique = true), Index("searchKey")])
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Стабильный ключ засева (null у продуктов пользователя). */
    val key: String? = null,
    val name: String,
    val category: String = "",
    val unit: MeasureUnit,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val gramsPerPiece: Double? = null,
    val storage: Storage = Storage.FRIDGE,
    val parLevel: Double? = null,
    val parManual: Boolean = false,
    val aliases: List<String> = emptyList(),
    val untracked: Boolean = false,
    val note: String = "",
    /** fish, fatty_fish, red_meat, egg, seafood, processed, salty (12.3, 15). */
    val tags: List<String> = emptyList(),
    val source: ProductSource = ProductSource.REFERENCE,
    /** Скрыт из поиска (используется в блоках, запасах или журнале — не удаляется). */
    val hidden: Boolean = false,
    val favorite: Boolean = false,
    /** Доля съедобной части (кость): ккал считаются от веса × доля, склад списывает вес целиком. */
    val edibleFraction: Double = 1.0,
    val cooked: CookState? = null,
    val role: FoodRole? = null,
    /** Буквы слотов, где продукт уместен: З, С, О, У, Е, П. */
    val slots: List<String> = emptyList(),
    val minPortion: Double? = null,
    val maxPortion: Double? = null,
    /** Нормализованный ключ поиска: название, алиасы, категория, теги (16.1). */
    val searchKey: String = "",
    val useCount: Int = 0,
    val lastUsedMillis: Long = 0,
)

@Serializable
@Entity(tableName = "stock_item", indices = [Index("productId")])
data class StockItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val qty: Double,
    val purchasedDay: Long,
    val expiresDay: Long? = null,
    val note: String = "",
    /** Количество оценено на глаз (инвентаризация, 17.1). */
    val approx: Boolean = false,
)

@Serializable
@Entity(tableName = "purchase")
data class Purchase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val store: String = "",
)

@Serializable
@Entity(tableName = "purchase_line", indices = [Index("purchaseId"), Index("productId")])
data class PurchaseLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val productId: Long,
    val name: String,
    val qty: Double,
    val unit: MeasureUnit,
    val price: Double? = null,
)

@Serializable
data class RecipeStep(val text: String, val timerSeconds: Int? = null)

@Serializable
@Entity(tableName = "block", indices = [Index(value = ["code"], unique = true)])
data class Block(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val kind: MealKind,
    val name: String,
    val kcal: Double,
    val protein: Double,
    val prepMinutes: Int = 0,
    val tags: List<String> = emptyList(),
    val recipeSteps: List<RecipeStep> = emptyList(),
    val deductStock: Boolean = true,
    val composition: String = "",
    val active: Boolean = true,
    /** Свой блок пользователя (M1, M2, …). */
    val custom: Boolean = false,
    /** Исходные блоки З1–Е6 скрыты из списков (19.4): вместо них отдельные блюда [Dish]. */
    val hidden: Boolean = false,
)

@Serializable
@Entity(tableName = "block_ingredient", indices = [Index("blockId")])
data class BlockIngredient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blockId: Long,
    /** Текст состава, как в плане. */
    val label: String,
    val productId: Long? = null,
    /** Альтернативы «или»: списывается первая, которой хватает. */
    val altProductIds: List<Long> = emptyList(),
    /** Ключ выхода заготовки, если ингредиент «(з)». */
    val prepKey: String? = null,
    val qty: Double? = null,
    val unit: MeasureUnit? = null,
    /** «по вкусу»: не списывается со склада. */
    val toTaste: Boolean = false,
)

@Serializable
data class PrepInput(val productId: Long, val qty: Double, val variable: Boolean = false)

@Serializable
data class PrepOutput(
    val key: String,
    val name: String,
    /** Продукт-результат (для замен и справочной калорийности). */
    val productId: Long? = null,
    /** Размер порции в граммах; null — штучная порция (1 шт = 1 порция). */
    val portionGrams: Double? = null,
    val defaultPortions: Double,
    /** Выход вводит пользователь (граммы готового). */
    val userEntersYield: Boolean = false,
)

@Serializable
@Entity(tableName = "prep_template", indices = [Index(value = ["key"], unique = true)])
data class PrepTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val name: String,
    val inputs: List<PrepInput>,
    val outputs: List<PrepOutput>,
    val shelfDays: Int,
    val steps: List<RecipeStep> = emptyList(),
    val inSaturdayBatch: Boolean = false,
)

@Serializable
@Entity(tableName = "prep", indices = [Index("outputKey")])
data class Prep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val outputKey: String,
    val name: String,
    val madeDay: Long,
    val expiresDay: Long,
    val portionsTotal: Double,
    val portionsLeft: Double,
    val portionGrams: Double? = null,
    val discarded: Boolean = false,
)

/** Строка фактического состава приёма (12.1–12.3). */
@Serializable
data class MealItem(
    val name: String,
    val productId: Long? = null,
    val customFoodId: Long? = null,
    val prepKey: String? = null,
    val qty: Double,
    val unit: MeasureUnit,
    /** Граммы съедобной части для расчёта ккал. */
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val tags: List<String> = emptyList(),
    /** Сколько списано со склада в единицах продукта (0 — «без списания»). */
    val deducted: Double = 0.0,
    val untracked: Boolean = false,
    /** Строка из отдельного блюда (19.4). Без продукта и заготовки — фиксированные ккал/белок, без списания. */
    val dishId: Long? = null,
)

/** Что было списано при записи приёма — для отмены. */
@Serializable
data class Deduction(
    val stockItemId: Long? = null,
    val prepId: Long? = null,
    val amount: Double,
)

@Serializable
@Entity(tableName = "meal_log", indices = [Index("day")])
data class MealLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val atMillis: Long,
    val slot: SlotType?,
    val blockId: Long? = null,
    val blockCode: String? = null,
    val customFoodId: Long? = null,
    val name: String,
    val multiplier: Double = 1.0,
    val kcal: Double,
    val protein: Double,
    val source: MealSource,
    val withFruit: Boolean = false,
    val meatChoice: MeatChoice? = null,
    val tags: List<String> = emptyList(),
    val eggs: Double = 0.0,
    val deductions: List<Deduction> = emptyList(),
    /** Исходный блок, если состав изменён или собран на его основе. */
    val basedOnBlockCode: String? = null,
    /** Фактический состав (конструктор, изменённый состав, вариант «Что приготовить»). */
    val items: List<MealItem> = emptyList(),
    /** Слоты, автоматически пропущенные этой записью (19.3): отмена записи возвращает их в «не отмечен». */
    val autoSkipped: List<String> = emptyList(),
)

/**
 * Состояние приёма дня (19.5). Нет строки — EMPTY. LOGGED ставится при первой записи в слот,
 * SKIPPED — вручную или автопропуском ([autoSkipped]).
 */
@Serializable
@Entity(tableName = "slot_state", primaryKeys = ["day", "slot"])
data class SlotState(
    val day: Long,
    val slot: SlotType,
    val status: MealSlotStatus,
    val autoSkipped: Boolean = false,
    val updatedAt: Long = 0,
)

/**
 * Отдельное блюдо (19.4) — компонент исходного блока со своим весом, ккал и белком.
 * Ключ (legacyBlockId, componentIndex) делает разбиение идемпотентным.
 * Источник: продукт ([productId], вес сырой — для списания), заготовка ([prepKey]) или фиксированные значения
 * (блюда «на улице» и батончики, без списания).
 */
@Serializable
@Entity(tableName = "dish", indices = [Index(value = ["legacyBlockId", "componentIndex"], unique = true)])
data class Dish(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val legacyBlockId: Long,
    val componentIndex: Int,
    val name: String,
    val productId: Long? = null,
    val prepKey: String? = null,
    val qty: Double,
    val unit: MeasureUnit,
    val kcal: Double,
    val protein: Double,
    val tags: List<String> = emptyList(),
    val blockCode: String = "",
    val hidden: Boolean = false,
)

@Serializable
@Entity(tableName = "custom_food")
data class CustomFood(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val portionGrams: Double? = null,
    val kcalPerPortion: Double? = null,
    val proteinPerPortion: Double? = null,
    val replacesProductId: Long? = null,
    val isBar: Boolean = false,
    val isProteinBar: Boolean = false,
)

@Serializable
@Entity(tableName = "substitution", indices = [Index("blockId")])
data class Substitution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blockId: Long,
    val ingredientId: Long,
    val customFoodId: Long,
    val qtyGrams: Double,
    val permanent: Boolean,
    /** Для разовой замены — день, к которому она относится. */
    val day: Long? = null,
    val kcalDelta: Double,
    val proteinDelta: Double,
)

@Serializable
@Entity(tableName = "day_plan")
data class DayPlan(
    @PrimaryKey val day: Long,
    val dayType: DayType,
    val road: Boolean = false,
    val confirmed: Boolean = false,
    val reason: String = "",
    val freeLunchRequested: Boolean = false,
)

@Serializable
@Entity(tableName = "planned_slot", indices = [Index(value = ["day", "slot"], unique = true)])
data class PlannedSlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val slot: SlotType,
    val minuteOfDay: Int,
    val blockId: Long?,
    val multiplier: Double = 1.0,
    val status: SlotStatus = SlotStatus.PLANNED,
    val optional: Boolean = false,
    val needsPurchase: Boolean = false,
    val lastReminderAtMillis: Long? = null,
    val reminderCount: Int = 0,
    val snoozeUntilMillis: Long? = null,
    val snoozeUsed: Boolean = false,
)

@Serializable
@Entity(tableName = "quick_log", indices = [Index("day")])
data class QuickLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val atMillis: Long,
    val type: QuickType,
    val amount: Double = 1.0,
)

@Serializable
@Entity(tableName = "weight_log", indices = [Index("day")])
data class WeightLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val kg: Double,
)

@Serializable
@Entity(tableName = "bp_log", indices = [Index("atMillis")])
data class BpLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
)

/** Тренировка (20.2). [kcalNet] — чистые (активные) ккал, они и идут в баланс. */
@Serializable
@Entity(tableName = "workout", indices = [Index("day")])
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: WorkoutType,
    val day: Long,
    val startMillis: Long,
    val durationMin: Double,
    /** Бассейн: метры; дорожка: метры (вычисляется из скорости и времени, если не введено). */
    val distanceM: Double? = null,
    val inclinePct: Double? = null,
    val speedKmh: Double? = null,
    /** Как получены ккал: расчёт или ввод (активные / общие). */
    val kcalSource: KcalSource,
    /** Введённые или рассчитанные ккал до перевода в чистые. */
    val kcalEntered: Double,
    val kcalNet: Double,
    val note: String = "",
)

/** Показатель анализа (20.4). [source]: manual / import. */
@Serializable
@Entity(tableName = "lab_result", indices = [Index("day"), Index("indicator")])
data class LabResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val indicator: String,
    val value: Double,
    val unit: String = "",
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val documentId: Long? = null,
    val source: String = "manual",
)

/** Документ (анализ, заключение). Сам файл — зашифрованный в files/docs/[fileName]. */
@Serializable
@Entity(tableName = "health_document", indices = [Index("day")])
data class HealthDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val type: DocType,
    val title: String,
    val issuer: String = "",
    val note: String = "",
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
)

/** Кэш дневного расчёта формы (20.3). Можно пересоздать в любой момент из журналов. */
@Serializable
@Entity(tableName = "form_daily")
data class FormDaily(
    @PrimaryKey val day: Long,
    val bmr: Double,
    val baseKcal: Double,
    val workoutKcal: Double,
    val intakeKcal: Double,
    val hasFood: Boolean,
    val computedAt: Long,
)

/** Ингредиент рецепта: ссылка по роли («protein:fish_white») или жёсткая («product:salmon»). */
@Serializable
data class RecipeIngredient(
    val ref: String,
    val grams: Double? = null,
    val pieces: Double? = null,
    val optional: Boolean = false,
)

/** Рецепт офлайн-базы (18.8). source: plan / generated — из cookbook.json, user — правки пользователя. */
@Serializable
@Entity(tableName = "recipe")
data class Recipe(
    @PrimaryKey val id: String,
    val title: String,
    val slots: List<String>,
    val tags: List<String> = emptyList(),
    val ingredients: List<RecipeIngredient>,
    val method: CookMethod,
    val activeMin: Int,
    val totalMin: Int,
    val steps: List<String>,
    val rawToCooked: Double = 1.0,
    val source: String = "generated",
    val version: Int = 0,
    val hidden: Boolean = false,
)
