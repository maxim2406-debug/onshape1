package com.ration.app.domain.meal

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.Dish
import com.ration.app.data.db.entity.MealItem
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.Tags

/**
 * Разбиение блоков З1–Е6 на отдельные блюда (19.4). Идемпотентно: ключ (legacyBlockId, componentIndex),
 * componentIndex — номер ингредиента в составе блока (порядок по id). Свои блоки M… не разбиваются.
 */
object DishSplitter {
    /** Теги блока, которые нужны счётчикам и предупреждениям для блюда без состава. */
    private val FIXED_TAGS = Tags.FOOD_TAGS + setOf(Tags.FREE_LUNCH, Tags.BAR, Tags.PROTEIN_BAR, Tags.CEREAL_BAR, Tags.LIGHT)

    fun split(blocks: List<Block>, ingredients: Map<Long, List<BlockIngredient>>, cat: FoodCatalog): List<Dish> =
        blocks.filter { !it.custom }.flatMap { b -> splitBlock(b, ingredients[b.id].orEmpty().sortedBy { it.id }, cat) }

    fun splitBlock(b: Block, ings: List<BlockIngredient>, cat: FoodCatalog): List<Dish> {
        if (!b.deductStock || ings.none { !it.toTaste && it.qty != null }) {
            // «на улице», батончики и блоки без весов: одно блюдо с ккал и белком из таблицы, без списания
            return listOf(Dish(legacyBlockId = b.id, componentIndex = 0, name = b.name, qty = 1.0, unit = MeasureUnit.PCS,
                kcal = b.kcal, protein = b.protein, tags = b.tags.filter { it in FIXED_TAGS }.sorted(), blockCode = b.code))
        }
        return ings.mapIndexedNotNull { i, ing ->
            val qty = ing.qty ?: return@mapIndexedNotNull null
            if (ing.toTaste) return@mapIndexedNotNull null
            val unit = ing.unit ?: MeasureUnit.G
            val item = when {
                ing.prepKey != null -> MealItems.ofPrep(ing.prepKey, qty, unit, cat)
                ing.productId != null -> cat.products[ing.productId]?.let { MealItems.ofProduct(it, qty, unit) }
                else -> null
            } ?: return@mapIndexedNotNull null
            Dish(legacyBlockId = b.id, componentIndex = i, name = dishName(ing, item, cat), productId = if (ing.prepKey == null) ing.productId else null,
                prepKey = ing.prepKey, qty = qty, unit = unit, kcal = item.kcal, protein = item.protein, tags = item.tags, blockCode = b.code)
        }
    }

    /** Название блюда: продукт или заготовка без граммовки («Лосось, сырой», «Курица су-вид из заготовки»). */
    private fun dishName(ing: BlockIngredient, item: MealItem, cat: FoodCatalog): String =
        ing.prepKey?.let { cat.prepOutputs[it]?.name } ?: ing.productId?.let { cat.products[it]?.name } ?: item.name

    /** Одинаковые блюда разных блоков (яйца вкрутую 2 шт в З2, С3, П4 …) показываются в списке один раз. */
    fun distinctForList(dishes: List<Dish>): List<Dish> =
        dishes.filter { !it.hidden }.distinctBy { listOf(it.productId, it.prepKey, it.qty, it.unit, if (it.productId == null && it.prepKey == null) it.name else "") }
}

/** Строка состава из блюда: продукт и заготовка — как обычно (со списанием), фиксированное блюдо — пропорционально порциям. */
fun MealItems.ofDish(d: Dish, qty: Double, cat: FoodCatalog): MealItem? = when {
    d.prepKey != null -> ofPrep(d.prepKey, qty, d.unit, cat)?.copy(name = d.name, dishId = d.id)
    d.productId != null -> cat.products[d.productId]?.let { ofProduct(it, qty, d.unit).copy(name = d.name, dishId = d.id) }
    else -> {
        val f = if (d.qty > 0) qty / d.qty else 1.0
        MealItem(name = d.name, qty = qty, unit = d.unit, grams = 0.0, kcal = d.kcal * f, protein = d.protein * f, tags = d.tags, dishId = d.id)
    }
}
