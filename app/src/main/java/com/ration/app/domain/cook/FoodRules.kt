package com.ration.app.domain.cook

import com.ration.app.data.db.entity.Product
import com.ration.app.domain.model.CookMethod
import com.ration.app.domain.model.CookState
import com.ration.app.domain.model.FoodRole
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.Tags

/** Категории 13.2.5 и 15. */
object Categories {
    const val POULTRY = "птица"
    const val BEEF = "говядина"
    const val PORK_LAMB = "свинина и баранина"
    const val FISH = "рыба и морепродукты"
    const val DAIRY = "яйца и молочные"
    const val GRAINS = "крупы и гарниры"
    const val BREAD = "хлеб и выпечка"
    const val VEG = "овощи"
    const val FRUIT = "фрукты"
    const val NUTS_OILS = "орехи и масла"
    const val SAUCES_OTHER = "соусы и прочее"
    const val READY = "полуфабрикаты и фастфуд"
    const val DELI = "мясные деликатесы"
    const val CANNED = "консервы"
    const val SALADS = "готовые салаты"
    const val SAVORY_BAKE = "выпечка солёная"
    const val SWEETS = "выпечка и сладкое"
    const val CHEESE = "сыры"
    const val SALT_FISH = "солёная и копчёная рыба"
    const val SAUCES = "соусы"
    const val SNACKS = "закуски"
    const val PREPS = "заготовки"
    const val SPICES = "приправы"

    val START = listOf(POULTRY, BEEF, PORK_LAMB, FISH, DAIRY, GRAINS, BREAD, VEG, FRUIT, NUTS_OILS, SAUCES_OTHER,
        READY, DELI, CANNED, SALADS, SAVORY_BAKE, SWEETS, CHEESE, SALT_FISH, SAUCES, SNACKS, PREPS, SPICES)
}

object FoodRules {
    private fun has(p: Product, vararg words: String) = words.any { p.name.lowercase().contains(it) }

    /** Роль по категории и тегам (18.2), если не задана явно. */
    fun role(p: Product): FoodRole {
        p.role?.let { return it }
        return when (p.category) {
            Categories.POULTRY, Categories.BEEF, Categories.PORK_LAMB, Categories.FISH, Categories.SALT_FISH, Categories.DELI -> FoodRole.PROTEIN
            Categories.DAIRY -> when {
                Tags.EGG in p.tags || has(p, "яйц", "яичн") -> FoodRole.PROTEIN
                has(p, "масло", "сливк", "сметан") -> FoodRole.FAT
                else -> FoodRole.DAIRY
            }
            Categories.CHEESE -> FoodRole.DAIRY
            Categories.GRAINS, Categories.BREAD, Categories.SAVORY_BAKE -> FoodRole.CARB
            Categories.VEG -> if (has(p, "картоф", "батат", "кукуруз")) FoodRole.CARB else FoodRole.VEG
            Categories.FRUIT -> FoodRole.FRUIT
            Categories.NUTS_OILS -> FoodRole.FAT
            Categories.SAUCES, Categories.SAUCES_OTHER -> FoodRole.SAUCE
            Categories.CANNED -> when {
                Tags.FISH in p.tags || Tags.RED_MEAT in p.tags -> FoodRole.PROTEIN
                has(p, "нут", "фасол", "чечевиц", "кукуруз") -> FoodRole.CARB
                else -> FoodRole.VEG
            }
            Categories.PREPS -> if (p.proteinPer100 >= 15) FoodRole.PROTEIN else FoodRole.CARB
            Categories.READY, Categories.SALADS -> FoodRole.READY
            else -> FoodRole.SNACK
        }
    }

    /** Слоты, где продукт уместен (18.2): явно заданные или по роли. */
    fun slots(p: Product): Set<String> {
        if (p.slots.isNotEmpty()) return p.slots.toSet()
        return when (role(p)) {
            FoodRole.PROTEIN -> if (Tags.EGG in p.tags || has(p, "яйц")) setOf("З", "С", "О", "У", "П") else setOf("С", "О", "У")
            FoodRole.VEG -> setOf("З", "С", "О", "У", "Е", "П")
            FoodRole.CARB -> setOf("З", "С", "О", "У")
            FoodRole.FAT -> setOf("З", "С", "О", "У", "Е", "П")
            FoodRole.FRUIT -> setOf("З", "С", "П", "Е")
            FoodRole.DAIRY -> setOf("З", "С", "П", "Е")
            FoodRole.SAUCE -> setOf("С", "О", "У")
            FoodRole.SNACK -> setOf("П", "Е")
            FoodRole.READY -> setOf("С", "О", "У")
        }
    }

    /** Диапазон порции в единицах продукта (г/мл или шт). */
    fun portion(p: Product): ClosedFloatingPointRange<Double> {
        val min0 = p.minPortion; val max0 = p.maxPortion
        if (min0 != null && max0 != null && max0 >= min0) return min0..max0
        if (p.unit == MeasureUnit.PCS) {
            return when {
                Tags.EGG in p.tags -> 1.0..4.0
                (p.gramsPerPiece ?: 100.0) >= 150 -> 0.5..1.0
                else -> 1.0..2.0
            }
        }
        val dry = has(p, "сух", "зёрна", "зерна", "хлопья")
        return when (role(p)) {
            FoodRole.PROTEIN -> if (Tags.EGG in p.tags) 60.0..200.0 else 80.0..250.0
            FoodRole.VEG -> 50.0..300.0
            FoodRole.CARB -> if (dry) 30.0..90.0 else 50.0..250.0
            FoodRole.FAT -> if (p.unit == MeasureUnit.ML || has(p, "масло")) 5.0..15.0 else 10.0..40.0
            FoodRole.FRUIT -> 80.0..300.0
            FoodRole.DAIRY -> if (p.category == Categories.CHEESE) 20.0..100.0 else 100.0..300.0
            FoodRole.SAUCE -> 10.0..50.0
            FoodRole.SNACK -> 20.0..60.0
            FoodRole.READY -> 100.0..350.0
        }
    }

    /** Шаг порции (18.3): 10 г мясо и рыба, 5 г остальное, целое для штучных. */
    fun step(p: Product): Double = when {
        p.unit == MeasureUnit.PCS -> if ((p.gramsPerPiece ?: 0.0) >= 150 && Tags.EGG !in p.tags) 0.5 else 1.0
        role(p) == FoodRole.PROTEIN -> 10.0
        else -> 5.0
    }

    /** Группы для ссылок рецептов «роль:группа» (18.8). */
    fun groups(p: Product): Set<String> {
        val g = mutableSetOf<String>()
        val r = role(p)
        g += r.name.lowercase()
        g += "any"
        val n = p.name.lowercase()
        if (Tags.FISH in p.tags || Tags.FATTY_FISH in p.tags) g += if (Tags.FATTY_FISH in p.tags) "fish_fatty" else "fish_white"
        if (Tags.FISH in p.tags || Tags.FATTY_FISH in p.tags) g += "fish"
        if (Tags.SEAFOOD in p.tags) g += "seafood"
        if (Tags.RED_MEAT in p.tags) g += "red_meat"
        if (Tags.EGG in p.tags || n.startsWith("яйц")) g += "egg"
        if (p.category == Categories.POULTRY || n.contains("куриц") || n.contains("индейк")) {
            if (!n.contains("печен")) g += if (p.edibleFraction < 0.99) "poultry_bone" else "poultry"
        }
        if (p.category == Categories.PREPS && p.proteinPer100 >= 15) g += "poultry"
        if (n.contains("консерв") || n.contains("в воде") || n.contains("в масле") || n.contains("в томате")) g += "canned"
        if (r == FoodRole.DAIRY) {
            if (p.category == Categories.CHEESE || n.contains("сыр") && !n.contains("творож")) g += "cheese" else g += "soft"
        }
        if (r == FoodRole.CARB) {
            when {
                n.contains("картоф") || n.contains("батат") -> g += "potato"
                n.contains("хлеб") || n.contains("лаваш") || n.contains("пит") || n.contains("хлебц") || n.contains("тортиль") -> g += "bread"
                n.contains("нут") || n.contains("чечевиц") || n.contains("фасол") -> g += "legume"
                else -> g += "grain"
            }
        }
        if (r == FoodRole.VEG) {
            if (n.contains("салат") || n.contains("рукол") || n.contains("зелен") || n.contains("шпинат") || n.contains("петруш")) g += "leafy"
            if (n.contains("помид") || n.contains("томат")) g += "tomato"
        }
        if (r == FoodRole.FAT) {
            if (p.unit == MeasureUnit.ML || n.contains("масло")) g += "oil" else g += "nuts"
        }
        if (r == FoodRole.FRUIT && (n.contains("ягод") || n.contains("клубн") || n.contains("голуб"))) g += "berries"
        if (r == FoodRole.SAUCE && (n.contains("томат") || n.contains("помид"))) g += "tomato"
        return g
    }

    /** Готов ли продукт к еде без термообработки. */
    fun readyToEat(p: Product): Boolean {
        val r = role(p)
        if (r in setOf(FoodRole.FRUIT, FoodRole.DAIRY, FoodRole.VEG, FoodRole.READY, FoodRole.SNACK, FoodRole.SAUCE)) {
            return !(r == FoodRole.VEG && (p.name.lowercase().contains("картоф") || p.name.lowercase().contains("батат")))
        }
        if (p.cooked == CookState.COOKED) return true
        val n = p.name.lowercase()
        if (n.contains("сыр") && r == FoodRole.PROTEIN) return false
        if (n.contains("варён") || n.contains("готов") || n.contains("консерв") || n.contains("в воде") || n.contains("в масле") ||
            n.contains("копч") || n.contains("солён") || n.contains("нарезк") || n.contains("из заготовки") || n.contains("хумус")) return true
        if (r == FoodRole.FAT) return true
        if (r == FoodRole.CARB) return n.contains("хлеб") || n.contains("лаваш") || n.contains("пит") || n.contains("хлебц") || n.contains("из пакета")
        return false
    }

    /**
     * Совместимость продукта со способом (18.8.3): сырое мясо, рыбу и яйца нельзя «без готовки»;
     * курицу с костью не ставить в рецепты для филе (группа poultry её не включает) и на сковороду/су-вид.
     */
    fun compatible(p: Product, method: CookMethod): Boolean {
        if (method == CookMethod.RAW) return readyToEat(p) || role(p) == FoodRole.VEG && !p.name.lowercase().contains("картоф")
        if ("poultry_bone" in groups(p) && (method == CookMethod.PAN || method == CookMethod.SOUSVIDE)) return false
        return true
    }
}
