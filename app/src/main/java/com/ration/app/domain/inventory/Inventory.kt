package com.ration.app.domain.inventory

import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.Deduction
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.StockItem
import com.ration.app.domain.model.MeasureUnit

private const val EPS = 1e-6

object UnitConv {
    /** Перевод количества в единицу продукта. null — пересчёт невозможен (нет веса штуки). */
    fun toProductUnit(qty: Double, unit: MeasureUnit, product: Product): Double? {
        if (unit == product.unit) return qty
        val gpp = product.gramsPerPiece
        return when {
            unit == MeasureUnit.PCS && product.unit == MeasureUnit.G && gpp != null -> qty * gpp
            unit == MeasureUnit.G && product.unit == MeasureUnit.PCS && gpp != null -> qty / gpp
            // мл ↔ г считаем 1:1 (масло, йогурты)
            unit == MeasureUnit.ML && product.unit == MeasureUnit.G -> qty
            unit == MeasureUnit.G && product.unit == MeasureUnit.ML -> qty
            else -> null
        }
    }

    /** Граммы для расчёта ккал/белка. */
    fun grams(qty: Double, unit: MeasureUnit, product: Product): Double? = when (unit) {
        MeasureUnit.G, MeasureUnit.ML -> qty
        MeasureUnit.PCS -> product.gramsPerPiece?.let { qty * it }
    }
}

data class Shortage(val label: String, val missing: Double, val unit: MeasureUnit)

data class ConsumptionPlan(
    val deductions: List<Deduction>,
    val shortages: List<Shortage>,
    /** Предупреждения о пересчёте (например, у продукта нет веса штуки). */
    val notes: List<String> = emptyList(),
) {
    val touchedStockIds: Set<Long> get() = deductions.mapNotNull { it.stockItemId }.toSet()
}

/** Что есть на складе: остатки партий и заготовок. */
class StockSnapshot(
    stock: List<StockItem>,
    preps: List<Prep>,
    private val today: Long,
) {
    private val stockLeft = stock.associate { it.id to it.qty }.toMutableMap()
    private val stockByProduct: Map<Long, List<StockItem>> = stock
        .groupBy { it.productId }
        .mapValues { (_, items) -> items.sortedWith(compareBy<StockItem> { it.purchasedDay }.thenBy { it.expiresDay ?: Long.MAX_VALUE }.thenBy { it.id }) }
    private val prepLeft = preps.associate { it.id to it.portionsLeft }.toMutableMap()
    private val prepsByKey: Map<String, List<Prep>> = preps
        .filter { !it.discarded && it.expiresDay >= today }
        .groupBy { it.outputKey }
        .mapValues { (_, list) -> list.sortedWith(compareBy<Prep> { it.expiresDay }.thenBy { it.madeDay }.thenBy { it.id }) }

    fun total(productId: Long): Double = stockByProduct[productId].orEmpty().sumOf { stockLeft[it.id] ?: 0.0 }

    /** Порций заготовки (или граммов, если grams=true) в наличии. */
    fun prepAvailable(key: String, grams: Boolean): Double = prepsByKey[key].orEmpty().sumOf {
        val left = prepLeft[it.id] ?: 0.0
        if (grams) left * (it.portionGrams ?: 1.0) else left
    }

    /** Списание по FIFO. Возвращает недостающее количество. */
    fun takeStock(productId: Long, qty: Double, out: MutableList<Deduction>): Double {
        var need = qty
        for (item in stockByProduct[productId].orEmpty()) {
            if (need <= EPS) break
            val left = stockLeft[item.id] ?: 0.0
            if (left <= EPS) continue
            val take = minOf(left, need)
            stockLeft[item.id] = left - take
            need -= take
            out += Deduction(stockItemId = item.id, amount = take)
        }
        return need.coerceAtLeast(0.0)
    }

    /** Списание порций заготовки, сначала с ближайшим сроком. qty в граммах (grams=true) или порциях. */
    fun takePrep(key: String, qty: Double, grams: Boolean, out: MutableList<Deduction>): Double {
        var need = qty
        for (p in prepsByKey[key].orEmpty()) {
            if (need <= EPS) break
            val left = prepLeft[p.id] ?: 0.0
            if (left <= EPS) continue
            val perPortion = if (grams) (p.portionGrams ?: 1.0) else 1.0
            val portions = minOf(left, need / perPortion)
            prepLeft[p.id] = left - portions
            need -= portions * perPortion
            out += Deduction(prepId = p.id, amount = portions)
        }
        return need.coerceAtLeast(0.0)
    }
}

object Consumption {
    /**
     * План списания для ингредиентов блока × множитель порции.
     * Нехватка не блокирует запись — возвращается списком предупреждений.
     */
    fun plan(
        ingredients: List<BlockIngredient>,
        multiplier: Double,
        products: Map<Long, Product>,
        snapshot: StockSnapshot,
    ): ConsumptionPlan {
        val out = mutableListOf<Deduction>()
        val shortages = mutableListOf<Shortage>()
        val notes = mutableListOf<String>()
        for (ing in ingredients) {
            if (ing.toTaste) continue
            val qty = (ing.qty ?: continue) * multiplier
            val unit = ing.unit ?: continue
            val prepKey = ing.prepKey
            if (prepKey != null) {
                val grams = unit != MeasureUnit.PCS
                val missing = snapshot.takePrep(prepKey, qty, grams, out)
                if (missing > EPS) shortages += Shortage(ing.label, missing, unit)
                continue
            }
            val candidates = (listOfNotNull(ing.productId) + ing.altProductIds).mapNotNull { products[it] }
            val primary = candidates.firstOrNull() ?: continue
            if (primary.untracked) continue
            val converted = candidates.associateWith { UnitConv.toProductUnit(qty, unit, it) }
            val chosen = candidates.firstOrNull { p ->
                val need = converted[p] ?: return@firstOrNull false
                !p.untracked && snapshot.total(p.id) + EPS >= need
            } ?: primary
            val need = converted[chosen]
            if (need == null) {
                notes += "«${chosen.name}»: не задан вес штуки, списание пропущено"
                continue
            }
            val missing = snapshot.takeStock(chosen.id, need, out)
            if (missing > EPS) shortages += Shortage(ing.label, missing, chosen.unit)
        }
        return ConsumptionPlan(out, shortages, notes)
    }

    /** Фрукт к обеду: яблоко, иначе груша. */
    fun fruit(apple: Product?, pear: Product?, snapshot: StockSnapshot): ConsumptionPlan {
        val out = mutableListOf<Deduction>()
        val pick = listOfNotNull(apple, pear).firstOrNull { snapshot.total(it.id) >= 1.0 - EPS } ?: apple ?: pear
            ?: return ConsumptionPlan(emptyList(), emptyList())
        val missing = snapshot.takeStock(pick.id, 1.0, out)
        val shortages = if (missing > EPS) listOf(Shortage("фрукт к обеду", missing, MeasureUnit.PCS)) else emptyList()
        return ConsumptionPlan(out, shortages)
    }

    /** Хватает ли запасов на блок (для планировщика). */
    fun canMake(ingredients: List<BlockIngredient>, multiplier: Double, products: Map<Long, Product>, snapshot: StockSnapshot): Boolean =
        plan(ingredients, multiplier, products, snapshot).shortages.isEmpty()

    /** Сколько целых яиц в блоке (сырые и варёные) — для недельного лимита. */
    fun eggs(ingredients: List<BlockIngredient>, eggProductId: Long?, eggPrepKey: String): Double =
        ingredients.filter { !it.toTaste }.sumOf { ing ->
            when {
                ing.prepKey == eggPrepKey -> ing.qty ?: 0.0
                eggProductId != null && ing.productId == eggProductId && ing.unit == MeasureUnit.PCS -> ing.qty ?: 0.0
                else -> 0.0
            }
        }
}

enum class StockLevel { GREEN, YELLOW, RED, UNKNOWN }

data class ShoppingItem(
    val product: Product,
    val have: Double,
    val par: Double,
    val toBuy: Double,
    val percent: Double,
    val urgent: Boolean,
)

object Thresholds {
    fun percent(total: Double, par: Double?): Double? =
        if (par == null || par <= 0) null else total / par * 100.0

    fun level(total: Double, par: Double?, buyPct: Int, urgentPct: Int): StockLevel {
        val p = percent(total, par) ?: return StockLevel.UNKNOWN
        return when {
            p <= urgentPct -> StockLevel.RED
            p <= buyPct -> StockLevel.YELLOW
            else -> StockLevel.GREEN
        }
    }

    /** Всё, что ниже порога закупки, одним списком; срочные сверху. */
    fun shoppingList(products: List<Product>, totals: Map<Long, Double>, buyPct: Int, urgentPct: Int): List<ShoppingItem> =
        products.asSequence()
            .filter { !it.untracked }
            .mapNotNull { p ->
                val par = p.parLevel ?: return@mapNotNull null
                val have = totals[p.id] ?: 0.0
                val pct = percent(have, par) ?: return@mapNotNull null
                if (pct >= buyPct) return@mapNotNull null
                ShoppingItem(p, have, par, roundUp(par - have, p.unit), pct, pct <= urgentPct)
            }
            .sortedWith(compareByDescending<ShoppingItem> { it.urgent }.thenBy { it.percent })
            .toList()

    fun roundUp(v: Double, unit: MeasureUnit): Double = when (unit) {
        MeasureUnit.PCS -> Math.ceil(v - 1e-9)
        else -> Math.ceil(v / 10.0 - 1e-9) * 10.0
    }
}

/** Состояние сработавших порогов (хранится в DataStore). */
@kotlinx.serialization.Serializable
data class AlertState(
    val alertedBuy: Set<Long> = emptySet(),
    val alertedUrgent: Set<Long> = emptySet(),
    val lastUrgentNotifyDay: Long? = null,
)

data class ThresholdEvaluation(
    val state: AlertState,
    /** Продукты, впервые опустившиеся ниже порога закупки. */
    val crossedBuy: Set<Long>,
    /** Продукты, впервые опустившиеся до срочного порога. */
    val crossedUrgent: Set<Long>,
    /** Отправить «Пора в магазин» (не чаще раза в день). */
    val notify: Boolean,
)

object ThresholdTracker {
    /**
     * Пересечение порога срабатывает один раз; продукт «перевзводится», когда остаток
     * снова выше порога (обычно после покупки). Уведомление — не чаще одного в день.
     */
    fun evaluate(state: AlertState, buyIds: Set<Long>, urgentIds: Set<Long>, today: Long): ThresholdEvaluation {
        val keptBuy = state.alertedBuy intersect buyIds
        val keptUrgent = state.alertedUrgent intersect urgentIds
        val crossedBuy = buyIds - keptBuy
        val crossedUrgent = urgentIds - keptUrgent
        val canNotify = state.lastUrgentNotifyDay != today
        val notify = crossedUrgent.isNotEmpty() && canNotify
        val newState = AlertState(
            alertedBuy = keptBuy + buyIds,
            alertedUrgent = if (notify) keptUrgent + urgentIds else keptUrgent,
            lastUrgentNotifyDay = if (notify) today else state.lastUrgentNotifyDay,
        )
        return ThresholdEvaluation(newState, crossedBuy, if (notify) crossedUrgent else emptySet(), notify)
    }
}

/** Применение и отмена списаний к партиям и заготовкам. */
object Ledger {
    /** sign = -1 — списать, +1 — вернуть (отмена записи). */
    fun apply(
        stock: List<StockItem>, preps: List<Prep>, deductions: List<Deduction>, sign: Int,
    ): Pair<List<StockItem>, List<Prep>> {
        val byStock = deductions.filter { it.stockItemId != null }.groupBy { it.stockItemId!! }.mapValues { e -> e.value.sumOf { it.amount } }
        val byPrep = deductions.filter { it.prepId != null }.groupBy { it.prepId!! }.mapValues { e -> e.value.sumOf { it.amount } }
        val newStock = stock.map { s -> byStock[s.id]?.let { s.copy(qty = (s.qty + sign * it).coerceAtLeast(0.0)) } ?: s }
        val newPreps = preps.map { p ->
            byPrep[p.id]?.let { p.copy(portionsLeft = (p.portionsLeft + sign * it).coerceIn(0.0, p.portionsTotal)) } ?: p
        }
        return newStock to newPreps
    }
}
