package com.ration.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.MealKind
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
@Entity(tableName = "product", indices = [Index(value = ["key"], unique = true)])
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
