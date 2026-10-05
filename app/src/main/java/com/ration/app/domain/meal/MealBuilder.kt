package com.ration.app.domain.meal

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.Deduction
import com.ration.app.data.db.entity.MealItem
import com.ration.app.data.db.entity.PrepOutput
import com.ration.app.data.db.entity.Product
import com.ration.app.domain.inventory.StockSnapshot
import com.ration.app.domain.inventory.UnitConv
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.Tags
import com.ration.app.domain.substitution.SubstitutionCalculator

private const val EPS = 1e-6

/** Справочники для расчёта строк состава. */
class FoodCatalog(
    val products: Map<Long, Product>,
    val customFoods: Map<Long, CustomFood> = emptyMap(),
    /** Выходы заготовок по ключу (порция, продукт-результат). */
    val prepOutputs: Map<String, PrepOutput> = emptyMap(),
) {
    fun prepProduct(key: String): Product? = prepOutputs[key]?.productId?.let(products::get)
}

object MealItems {
    /** Строка из продукта каталога: ккал и белок от съедобной части (edibleFraction). */
    fun ofProduct(p: Product, qty: Double, unit: MeasureUnit): MealItem {
        val grams = (UnitConv.grams(qty, unit, p) ?: qty) * p.edibleFraction
        return MealItem(
            name = p.name, productId = p.id, qty = qty, unit = unit, grams = grams,
            kcal = p.kcalPer100 * grams / 100, protein = p.proteinPer100 * grams / 100,
            tags = p.tags, untracked = p.untracked,
        )
    }

    fun ofCustom(f: CustomFood, grams: Double): MealItem {
        val (k, pr) = SubstitutionCalculator.per100(f)
        val tags = buildList { if (f.isBar) add(Tags.BAR); if (f.isProteinBar) add(Tags.PROTEIN_BAR) }
        return MealItem(name = f.name, customFoodId = f.id, qty = grams, unit = MeasureUnit.G, grams = grams,
            kcal = k * grams / 100, protein = pr * grams / 100, tags = tags)
    }

    /** Строка из заготовки: qty в г (или шт для штучных порций, например яйца). */
    fun ofPrep(key: String, qty: Double, unit: MeasureUnit, cat: FoodCatalog): MealItem? {
        val out = cat.prepOutputs[key] ?: return null
        val p = out.productId?.let(cat.products::get) ?: return null
        val grams = if (unit == MeasureUnit.PCS) qty * (p.gramsPerPiece ?: out.portionGrams ?: 60.0) else qty
        return MealItem(name = out.name, productId = p.id, prepKey = key, qty = qty, unit = unit, grams = grams,
            kcal = p.kcalPer100 * grams / 100, protein = p.proteinPer100 * grams / 100, tags = p.tags)
    }

    /** Состав блока как редактируемый список (сырые веса раздела 8). «По вкусу» — строки без списания. */
    fun ofBlock(ingredients: List<BlockIngredient>, cat: FoodCatalog, multiplier: Double = 1.0): List<MealItem> =
        ingredients.mapNotNull { ing ->
            val qty = (ing.qty ?: 0.0) * multiplier
            val unit = ing.unit ?: MeasureUnit.G
            when {
                ing.toTaste -> MealItem(name = ing.label, productId = ing.productId, qty = 0.0, unit = unit, grams = 0.0,
                    kcal = 0.0, protein = 0.0, untracked = true)
                ing.prepKey != null -> ofPrep(ing.prepKey, qty, unit, cat)?.copy(name = ing.label)
                ing.productId != null -> cat.products[ing.productId]?.let { ofProduct(it, qty, unit).copy(name = ing.label) }
                else -> null
            }
        }

    fun sumKcal(items: List<MealItem>) = items.sumOf { it.kcal }
    fun sumProtein(items: List<MealItem>) = items.sumOf { it.protein }

    /**
     * Итог блока с изменённым составом (12.2): табличные ккал/белок блока плюс разница
     * между фактическим и исходным составом. Без изменений итог равен таблице раздела 7.
     */
    fun editedBlockTotals(block: Block, original: List<MealItem>, edited: List<MealItem>, multiplier: Double = 1.0): Pair<Double, Double> {
        val k = block.kcal * multiplier + sumKcal(edited) - sumKcal(original)
        val p = block.protein * multiplier + sumProtein(edited) - sumProtein(original)
        return k.coerceAtLeast(0.0) to p.coerceAtLeast(0.0)
    }

    /** Яйца целиком в составе: шт или граммы / 60. */
    fun eggs(items: List<MealItem>, products: Map<Long, Product>): Double = items.filter { Tags.EGG in it.tags }.sumOf { i ->
        when (i.unit) {
            MeasureUnit.PCS -> i.qty
            else -> i.grams / (i.productId?.let(products::get)?.gramsPerPiece ?: 60.0)
        }
    }

    /**
     * Теги записи для недельных счётчиков (12.3.3): по тегам ингредиентов.
     * Если состав не менялся, добавляются и теги блока (например, «сэндвич с тунцом» — fish).
     */
    fun logTags(items: List<MealItem>, block: Block?, compositionEdited: Boolean): List<String> {
        val fromItems = items.flatMap { it.tags }.filter { it in Tags.FOOD_TAGS }.toSet()
        val fromBlock = block?.tags.orEmpty().toSet()
        val result = if (block == null) fromItems
        else if (compositionEdited) fromItems + fromBlock.filter { it in Tags.NON_FOOD_TAGS }
        else fromItems + fromBlock
        return result.toList().sorted()
    }
}

data class ItemsDeduction(val items: List<MealItem>, val deductions: List<Deduction>)

object ItemsConsumption {
    /**
     * Списание фактического состава по FIFO, включая заготовки (12.3.1).
     * Нет или мало на складе — списывается доступное, остаток не уходит в минус; строка помечается.
     */
    fun plan(items: List<MealItem>, cat: FoodCatalog, snapshot: StockSnapshot): ItemsDeduction {
        val out = mutableListOf<Deduction>()
        val result = items.map { item ->
            if (item.untracked || item.qty <= EPS) return@map item.copy(deducted = 0.0)
            val key = item.prepKey
            if (key != null) {
                val grams = item.unit != MeasureUnit.PCS
                val local = mutableListOf<Deduction>()
                val missing = snapshot.takePrep(key, item.qty, grams, local)
                out += local
                return@map item.copy(deducted = item.qty - missing)
            }
            val p = item.productId?.let(cat.products::get) ?: return@map item.copy(deducted = 0.0)
            if (p.untracked) return@map item.copy(deducted = 0.0, untracked = true)
            val need = UnitConv.toProductUnit(item.qty, item.unit, p) ?: return@map item.copy(deducted = 0.0)
            val local = mutableListOf<Deduction>()
            val missing = snapshot.takeStock(p.id, need, local)
            out += local
            item.copy(deducted = need - missing)
        }
        return ItemsDeduction(result, out)
    }

    /** Строка «без списания» или «частично». */
    fun shortfall(item: MealItem, cat: FoodCatalog): Double {
        if (item.untracked || item.qty <= EPS) return 0.0
        // свой продукт и блюдо «на улице» не хранятся на складе — нехватки нет
        if (item.productId == null && item.prepKey == null) return 0.0
        val p = item.productId?.let(cat.products::get)
        val need = if (item.prepKey != null || p == null) item.qty else UnitConv.toProductUnit(item.qty, item.unit, p) ?: item.qty
        return (need - item.deducted).coerceAtLeast(0.0)
    }
}

object CustomBlocks {
    fun nextCode(existing: Collection<String>): String {
        val max = existing.mapNotNull { Regex("""^M(\d+)$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "M${max + 1}"
    }

    /** Свой блок из фактического состава (12.4). Теги подставляются по составу. */
    fun build(code: String, name: String, kind: MealKind, items: List<MealItem>, extraTags: List<String> = emptyList()): Pair<Block, List<BlockIngredient>> {
        val tags = (items.flatMap { it.tags }.filter { it in Tags.FOOD_TAGS } + extraTags +
            (if (items.any { it.prepKey != null }) listOf(Tags.PREP) else emptyList())).distinct().sorted()
        val block = Block(
            code = code, kind = kind, name = name, kcal = Math.round(MealItems.sumKcal(items)).toDouble(),
            protein = Math.round(MealItems.sumProtein(items)).toDouble(), tags = tags, deductStock = true,
            composition = items.joinToString("; ") { "${it.name} ${fmt(it.qty)} ${it.unit.label}" }, custom = true,
        )
        val ings = items.map { i ->
            BlockIngredient(
                blockId = 0, label = "${i.name} ${fmt(i.qty)} ${i.unit.label}", productId = if (i.prepKey == null) i.productId else null,
                prepKey = i.prepKey, qty = i.qty.takeIf { !i.untracked && i.customFoodId == null }, unit = i.unit,
                toTaste = i.untracked || i.customFoodId != null,
            )
        }
        return block to ings
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

    /** Попадает ли свой блок в диапазоны слота (12.4.2): ккал в пределах блоков засева этого типа ±10%, белок по 6.3.1. */
    fun fitsSlot(b: Block, seedBlocks: List<Block>): Boolean {
        val same = seedBlocks.filter { !it.custom && it.kind == b.kind && com.ration.app.domain.model.Tags.BAR !in it.tags }
        if (same.isEmpty()) return false
        val lo = same.minOf { it.kcal } * 0.9
        val hi = same.maxOf { it.kcal } * 1.1
        val minProtein = when (b.kind) {
            MealKind.BREAKFAST, MealKind.LUNCH_CARRY, MealKind.LUNCH_STREET, MealKind.DINNER -> 30.0
            MealKind.SNACK -> 12.0
            MealKind.EVENING -> 0.0
        }
        return b.kcal in lo..hi && b.protein >= minProtein
    }
}
