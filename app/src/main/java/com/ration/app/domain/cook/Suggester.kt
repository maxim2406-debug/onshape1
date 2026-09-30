package com.ration.app.domain.cook

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.MealItem
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.PrepOutput
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.Recipe
import com.ration.app.data.db.entity.StockItem
import com.ration.app.domain.inventory.Consumption
import com.ration.app.domain.inventory.StockSnapshot
import com.ration.app.domain.inventory.UnitConv
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.CookMethod
import com.ration.app.domain.model.CookSlot
import com.ration.app.domain.model.FoodRole
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.Tags
import com.ration.app.domain.plan.WeekCounters
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Позиция остатка: партия продукта или заготовка. qty — в единицах unit. */
data class PantryEntry(
    val ref: String,
    val product: Product,
    val available: Double,
    val unit: MeasureUnit,
    val approx: Boolean,
    val expiresDay: Long?,
    val prepKey: String? = null,
    val oldestPurchaseDay: Long? = null,
) {
    fun grams(qty: Double): Double = (if (unit == MeasureUnit.PCS) qty * (product.gramsPerPiece ?: 60.0) else qty)
    fun kcal(qty: Double) = product.kcalPer100 * grams(qty) * product.edibleFraction / 100
    fun protein(qty: Double) = product.proteinPer100 * grams(qty) * product.edibleFraction / 100
}

data class SuggestItem(val entry: PantryEntry, val qty: Double) {
    val kcal get() = entry.kcal(qty)
    val protein get() = entry.protein(qty)
    fun toMealItem(): MealItem {
        val p = entry.product
        return MealItem(
            name = p.name, productId = p.id, prepKey = entry.prepKey, qty = qty, unit = entry.unit,
            grams = entry.grams(qty) * p.edibleFraction, kcal = kcal, protein = protein, tags = p.tags,
        )
    }
}

data class Suggestion(
    val key: String,
    val title: String,
    val items: List<SuggestItem>,
    val kcal: Double,
    val protein: Double,
    val score: Double,
    val level: Int,
    val recipe: Recipe? = null,
    val block: Block? = null,
    val multiplier: Double = 1.0,
    val method: CookMethod? = null,
    val defaultSteps: List<String> = emptyList(),
) {
    val approx: Boolean get() = items.any { it.entry.approx }
    fun expiringSoon(today: Long): Boolean = items.any { it.entry.expiresDay?.let { d -> d - today <= 2 } == true }
    val mainProtein: Product? get() = items.map { it.entry.product }.firstOrNull { FoodRules.role(it) == FoodRole.PROTEIN }
    val generated: Boolean get() = recipe?.source == "generated"
}

data class SuggestRequest(
    val slot: CookSlot,
    val targetKcal: Double,
    val targetProtein: Double,
    val onlyOwn: Boolean = false,
    val allowProcessed: Boolean = false,
    val respectWeekly: Boolean = true,
    val expiringFirst: Boolean = false,
    val methods: Set<CookMethod>? = null,
    val weekdayEvening: Boolean = false,
    val week: WeekCounters = WeekCounters(),
    val settings: AppSettings = AppSettings(),
    val lastMainProteinId: Long? = null,
    val hiddenKeys: Set<String> = emptySet(),
    /** Хотя бы один из этих продуктов должен войти в вариант (режим «Использовать остатки»). */
    val mustInclude: Set<Long>? = null,
    val limit: Int = 10,
)

data class SuggestResult(val suggestions: List<Suggestion>, val reason: String?)

class Suggester(
    private val products: Map<Long, Product>,
    stock: List<StockItem>,
    preps: List<Prep>,
    private val prepOutputs: Map<String, PrepOutput>,
    private val blocks: List<Block>,
    private val ingredients: Map<Long, List<BlockIngredient>>,
    private val recipes: List<Recipe>,
    private val defaults: Map<String, DefaultMethod>,
    private val today: Long,
) {
    private val stock = stock
    private val preps = preps.filter { !it.discarded && it.expiresDay >= today && it.portionsLeft > 1e-6 }
    val pantry: List<PantryEntry> = buildPantry()

    private fun buildPantry(): List<PantryEntry> {
        val out = mutableListOf<PantryEntry>()
        stock.filter { it.qty > 1e-6 }.groupBy { it.productId }.forEach { (pid, items) ->
            val p = products[pid] ?: return@forEach
            if (p.untracked || p.hidden) return@forEach
            out += PantryEntry("p:$pid", p, items.sumOf { it.qty }, p.unit, items.any { it.approx },
                items.mapNotNull { it.expiresDay }.minOrNull(), oldestPurchaseDay = items.minOf { it.purchasedDay })
        }
        preps.groupBy { it.outputKey }.forEach { (key, list) ->
            val out0 = prepOutputs[key] ?: return@forEach
            val p = out0.productId?.let(products::get) ?: return@forEach
            val grams = out0.portionGrams != null
            val available = list.sumOf { it.portionsLeft * (if (grams) it.portionGrams ?: out0.portionGrams!! else 1.0) }
            out += PantryEntry("prep:$key", p.copy(name = out0.name), available, if (grams) MeasureUnit.G else MeasureUnit.PCS,
                false, list.minOf { it.expiresDay }, prepKey = key)
        }
        return out
    }

    private fun excluded(p: Product, req: SuggestRequest): Boolean {
        if (p.untracked || p.hidden) return true
        if (!req.allowProcessed && (Tags.PROCESSED in p.tags || FoodRules.role(p) == FoodRole.READY)) return true
        if (req.respectWeekly && Tags.RED_MEAT in p.tags && req.week.redMeat >= req.settings.redMeatMax) return true
        return false
    }

    private fun objective(k: Double, p: Double, req: SuggestRequest): Double {
        val kk = max(req.targetKcal, 1.0); val pp = max(req.targetProtein, 1.0)
        return abs(k - req.targetKcal) / kk + abs(p - req.targetProtein) / pp
    }

    fun withinTolerance(k: Double, p: Double, req: SuggestRequest): Boolean =
        abs(k - req.targetKcal) <= req.targetKcal * 0.10 + 1e-6 && p >= req.targetProtein * 0.9 - 1e-6

    private fun eggsOf(items: List<SuggestItem>) = items.filter { Tags.EGG in it.entry.product.tags }.sumOf {
        if (it.entry.unit == MeasureUnit.PCS) it.qty else it.qty / (it.entry.product.gramsPerPiece ?: 60.0)
    }

    private fun score(items: List<SuggestItem>, k: Double, p: Double, req: SuggestRequest, bonus: Double): Double {
        var s = 1000.0 - 300.0 * objective(k, p, req) + bonus
        val s0 = req.settings
        for (it in items) {
            val pr = it.entry.product
            val days = it.entry.expiresDay?.let { d -> d - today }
            if (days != null && days <= 2) s += if (req.expiringFirst) 200.0 else 5.0
            if (Tags.PROCESSED in pr.tags) s -= 40
        }
        val main = items.map { it.entry.product }.firstOrNull { FoodRules.role(it) == FoodRole.PROTEIN }
        if (main != null && main.id == req.lastMainProteinId) s -= 50
        if (items.any { Tags.FISH in it.entry.product.tags || Tags.SEAFOOD in it.entry.product.tags } && req.week.fish < s0.fishTargetMin) s += 25
        if (items.any { Tags.FATTY_FISH in it.entry.product.tags } && req.week.fattyFish < s0.fattyFishMin) s += 25
        return s
    }

    private fun violatesWeekly(items: List<SuggestItem>, req: SuggestRequest): Boolean {
        if (!req.respectWeekly) return false
        if (items.any { Tags.RED_MEAT in it.entry.product.tags } && req.week.redMeat >= req.settings.redMeatMax) return true
        if (req.week.eggs + eggsOf(items) > req.settings.eggsMaxWeek) return true
        return false
    }

    private fun sortedCandidates(list: List<PantryEntry>, req: SuggestRequest, n: Int): List<PantryEntry> =
        list.sortedWith(
            compareBy<PantryEntry> { if (req.expiringFirst) (it.expiresDay ?: Long.MAX_VALUE) else 0L }
                .thenByDescending { it.product.id in (req.mustInclude ?: emptySet()) }
                .thenByDescending { it.grams(it.available) }
                .thenBy { it.product.name },
        ).take(n)

    /** Допустимые количества позиции: шаг порции, диапазон, не больше остатка. */
    fun grid(e: PantryEntry, maxValues: Int = 20): List<Double> {
        val p = e.product
        val range = FoodRules.portion(p)
        val stepBase = FoodRules.step(p)
        val lo = range.start
        val hi = min(range.endInclusive, e.available)
        if (hi + 1e-9 < lo) return emptyList()
        var step = stepBase
        while ((hi - lo) / step + 1 > maxValues) step += stepBase
        val start = Math.ceil(lo / stepBase - 1e-9) * stepBase
        val out = mutableListOf<Double>()
        var q = start
        while (q <= hi + 1e-9) { out += q; q += step }
        if (out.isEmpty() && hi >= lo) out += floor(hi / stepBase) * stepBase
        return out.filter { it > 0 }
    }

    // ---------- Уровень 1: готовые блоки (свои M… и раздела 7) ----------

    private fun level1(req: SuggestRequest): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val cands = blocks.filter { b ->
            b.active && b.deductStock && b.kind in req.slot.mealKinds && Tags.BAR !in b.tags && (!req.onlyOwn || b.custom)
        }
        for (b in cands) {
            val ings = ingredients[b.id].orEmpty().filter { !it.toTaste }
            if (ings.isEmpty()) continue
            if (req.respectWeekly && Tags.RED_MEAT in b.tags && req.week.redMeat >= req.settings.redMeatMax) continue
            var best: Pair<Double, Double>? = null
            for (m in listOf(0.5, 0.75, 1.0, 1.25, 1.5)) {
                if (!Consumption.canMake(ings, m, products, StockSnapshot(stock, preps, today))) continue
                val k = b.kcal * m; val p = b.protein * m
                if (!withinTolerance(k, p, req)) continue
                val obj = objective(k, p, req)
                if (best == null || obj < best.second) best = m to obj
            }
            val m = best?.first ?: continue
            val items = blockItems(ings, m) ?: continue
            if (items.any { excluded(it.entry.product, req) && it.entry.prepKey == null }) continue
            if (req.mustInclude != null && items.none { it.entry.product.id in req.mustInclude }) continue
            if (violatesWeekly(items, req)) continue
            val k = b.kcal * m; val p = b.protein * m
            out += Suggestion("block:${b.code}:$m", "${b.code} ${b.name}", items, k, p, score(items, k, p, req, 30.0), 1, block = b, multiplier = m)
        }
        return out
    }

    private fun blockItems(ings: List<BlockIngredient>, m: Double): List<SuggestItem>? = ings.map { ing ->
        val qty = (ing.qty ?: return null) * m
        val unit = ing.unit ?: return null
        val prepKey = ing.prepKey
        if (prepKey != null) {
            val e = pantry.firstOrNull { it.prepKey == prepKey } ?: return null
            return@map SuggestItem(e, qty)
        }
        val ids = listOfNotNull(ing.productId) + ing.altProductIds
        val e = ids.asSequence().mapNotNull { id -> pantry.firstOrNull { it.prepKey == null && it.product.id == id } }
            .firstOrNull { e -> (UnitConv.toProductUnit(qty, unit, e.product) ?: Double.MAX_VALUE) <= e.available + 1e-6 }
            ?: return@map null
        SuggestItem(e, UnitConv.toProductUnit(qty, unit, e.product) ?: return null)
    }.filterNotNull().takeIf { it.isNotEmpty() }

    // ---------- Уровень 2: рецепты базы и шаблоны слота ----------

    private fun recipeAllowed(r: Recipe, req: SuggestRequest): Boolean {
        if (r.hidden || req.slot.letter !in r.slots) return false
        if (req.onlyOwn && r.source != "user") return false
        if (req.methods != null && r.method !in req.methods) return false
        if (r.activeMin > 20 && (req.slot in setOf(CookSlot.CARRY, CookSlot.SNACK, CookSlot.EVENING) || req.weekdayEvening)) return false
        if (req.respectWeekly && Tags.RED_MEAT in r.tags && req.week.redMeat >= req.settings.redMeatMax) return false
        return true
    }

    private fun baseQty(ing: com.ration.app.data.db.entity.RecipeIngredient, e: PantryEntry): Double? {
        val gpp = e.product.gramsPerPiece ?: if (e.unit == MeasureUnit.PCS) 60.0 else null
        return when {
            e.unit == MeasureUnit.PCS && ing.pieces != null -> ing.pieces
            e.unit == MeasureUnit.PCS && ing.grams != null -> gpp?.let { ing.grams / it }
            ing.grams != null -> ing.grams
            ing.pieces != null -> gpp?.let { ing.pieces * it } ?: (ing.pieces * 60.0)
            else -> null
        }
    }

    private fun roundTo(q: Double, e: PantryEntry): Double {
        val st = FoodRules.step(e.product)
        return max(st, (q / st).roundToInt() * st)
    }

    private fun fromRecipes(req: SuggestRequest): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        for (r in recipes.filter { recipeAllowed(it, req) }) {
            val options = r.ingredients.map { ing ->
                pantry.filter { e ->
                    Cookbook.matches(ing.ref, e.product) && !excluded(e.product, req) && Tags.SALTY !in e.product.tags &&
                        FoodRules.compatible(e.product, r.method)
                }.let { sortedCandidates(it, req, 3) }
            }
            if (r.ingredients.indices.any { !r.ingredients[it].optional && options[it].isEmpty() }) continue
            // перебор назначений (обязательные — все варианты, необязательные — лучший или без него)
            val assignments = mutableListOf<List<PantryEntry?>>()
            fun rec(i: Int, acc: List<PantryEntry?>) {
                if (assignments.size >= 27) return
                if (i == r.ingredients.size) { assignments += acc; return }
                val opts: List<PantryEntry?> = if (r.ingredients[i].optional) (options[i].take(1) + listOf(null)).distinct() else options[i]
                for (o in opts) if (o == null || acc.none { it?.ref == o.ref }) rec(i + 1, acc + o)
            }
            rec(0, emptyList())
            for (a in assignments) {
                var best: Pair<List<SuggestItem>, Double>? = null
                var f = 0.5
                while (f <= 1.5001) {
                    val items = a.mapIndexedNotNull { i, e ->
                        e ?: return@mapIndexedNotNull null
                        val base = baseQty(r.ingredients[i], e) ?: return@mapIndexedNotNull null
                        val fixed = r.ingredients[i].ref.startsWith("fat:oil")
                        val q = roundTo(if (fixed) base else base * f, e)
                        if (q > e.available + 1e-6) null else SuggestItem(e, q)
                    }
                    val requiredOk = a.indices.all { i -> r.ingredients[i].optional || items.any { it.entry.ref == a[i]?.ref } }
                    if (requiredOk) {
                        val k = items.sumOf { it.kcal }; val p = items.sumOf { it.protein }
                        if (withinTolerance(k, p, req)) {
                            val o = objective(k, p, req)
                            if (best == null || o < best.second) best = items to o
                        }
                    }
                    f += 0.05
                }
                val items = best?.first ?: continue
                if (req.mustInclude != null && items.none { it.entry.product.id in req.mustInclude }) continue
                if (violatesWeekly(items, req)) continue
                val k = items.sumOf { it.kcal }; val p = items.sumOf { it.protein }
                out += Suggestion("recipe:${r.id}:" + items.joinToString(",") { it.entry.ref }, r.title, items, k, p,
                    score(items, k, p, req, 40.0), 2, recipe = r, method = r.method)
            }
        }
        return out
    }

    private fun templates(slot: CookSlot): List<List<String>> = when (slot) {
        CookSlot.LUNCH, CookSlot.DINNER -> listOf(listOf("protein", "veg", "carb"), listOf("protein", "veg", "fat"), listOf("protein", "veg"))
        CookSlot.BREAKFAST -> listOf(listOf("bprotein", "carb", "fruit"), listOf("bprotein", "carb"), listOf("bprotein", "fruit"))
        CookSlot.CARRY -> listOf(listOf("dairy"), listOf("fruit"), listOf("snack"), listOf("fruit", "dairy"),
            listOf("protein", "veg", "carb"), listOf("protein", "veg"))
        CookSlot.SNACK, CookSlot.EVENING -> listOf(listOf("dairy"), listOf("fruit"), listOf("snack"), listOf("fruit", "dairy"))
    }

    private fun roleEntries(role: String, req: SuggestRequest): List<PantryEntry> {
        val letter = req.slot.letter
        val base = pantry.filter { e ->
            val p = e.product
            !excluded(p, req) && letter in FoodRules.slots(p) && when (role) {
                "bprotein" -> "egg" in FoodRules.groups(p) || (FoodRules.role(p) == FoodRole.DAIRY && p.proteinPer100 >= 8)
                "protein" -> FoodRules.role(p) == FoodRole.PROTEIN
                else -> FoodRules.role(p).name.equals(role, true)
            }
        }
        val n = when (role) { "veg", "fruit", "fat" -> 4; else -> 12 }
        return sortedCandidates(base, req, n)
    }

    private fun shortName(p: Product): String = p.name.substringBefore(',').substringBefore('(').trim()

    private fun defaultFor(items: List<SuggestItem>): Pair<CookMethod?, List<String>> {
        val main = items.firstOrNull()?.entry?.product ?: return null to emptyList()
        val g = FoodRules.groups(main)
        val key = listOf("poultry_bone", "red_meat", "seafood", "fish", "poultry", "egg", "potato", "grain").firstOrNull { it in g }
            ?: when (FoodRules.role(main)) {
                FoodRole.DAIRY -> "dairy"; FoodRole.FRUIT -> "fruit"; FoodRole.VEG -> "veg"; FoodRole.CARB -> "grain"; else -> null
            }
        val d = key?.let(defaults::get) ?: return null to emptyList()
        return d.method to d.steps
    }

    private fun fromTemplates(req: SuggestRequest): Pair<List<Suggestion>, String?> {
        val out = mutableListOf<Suggestion>()
        var reason: String? = null
        var bestProteinSeen = 0.0
        for (tpl in templates(req.slot)) {
            val lists = tpl.map { roleEntries(it, req) }
            if (lists.any { it.isEmpty() }) {
                if (tpl.first() in setOf("protein", "bprotein") && lists.first().isEmpty() && reason == null) reason = "не хватает белкового продукта"
                continue
            }
            val combos = mutableListOf<List<PantryEntry>>()
            fun rec(i: Int, acc: List<PantryEntry>) {
                if (i == lists.size) { combos += acc; return }
                for (e in lists[i]) if (acc.none { it.ref == e.ref || it.product.id == e.product.id }) rec(i + 1, acc + e)
            }
            rec(0, emptyList())
            for (combo in combos) {
                if (req.methods != null) {
                    val m = defaultFor(combo.map { SuggestItem(it, 1.0) }).first
                    if (m != null && m !in req.methods) continue
                }
                val grids = combo.mapIndexed { i, e ->
                    val g = grid(e)
                    // вторичные позиции (овощи, фрукты, жиры) — 2 значения для скорости
                    if (i >= 2 || tpl[i] in setOf("veg", "fruit", "fat")) {
                        if (g.size <= 2) g else listOf(g[g.size / 3], g[2 * g.size / 3])
                    } else g
                }
                if (grids.any { it.isEmpty() }) continue
                var best: Pair<List<Double>, Double>? = null
                fun search(i: Int, acc: List<Double>, k: Double, p: Double) {
                    if (i == combo.size) {
                        bestProteinSeen = max(bestProteinSeen, p)
                        if (withinTolerance(k, p, req)) {
                            val o = objective(k, p, req)
                            if (best == null || o < best!!.second) best = acc to o
                        }
                        return
                    }
                    for (q in grids[i]) {
                        val kk = k + combo[i].kcal(q)
                        if (kk > req.targetKcal * 1.1 + 1) break
                        search(i + 1, acc + q, kk, p + combo[i].protein(q))
                    }
                }
                search(0, emptyList(), 0.0, 0.0)
                val qs = best?.first ?: continue
                val items = combo.mapIndexed { i, e -> SuggestItem(e, qs[i]) }
                if (req.mustInclude != null && items.none { it.entry.product.id in req.mustInclude }) continue
                if (violatesWeekly(items, req)) continue
                val k = items.sumOf { it.kcal }; val p = items.sumOf { it.protein }
                val (method, steps) = defaultFor(items)
                out += Suggestion("combo:" + items.map { it.entry.ref }.sorted().joinToString(","),
                    items.joinToString(" + ") { shortName(it.entry.product) }, items, k, p,
                    score(items, k, p, req, 0.0), 2, method = method, defaultSteps = steps)
            }
        }
        if (out.isEmpty() && reason == null) {
            reason = if (bestProteinSeen < req.targetProtein * 0.9) "остатки не дают цели по белку" else "остатки не дают цели по калориям"
        }
        return out to reason
    }

    fun suggest(req: SuggestRequest): SuggestResult {
        if (pantry.isEmpty()) return SuggestResult(emptyList(), "склад пуст: внесите покупку или проведите инвентаризацию")
        val l1 = level1(req)
        val rec = fromRecipes(req)
        val (tpl, reason) = fromTemplates(req)
        val recipeSets = rec.map { s -> s.items.map { it.entry.ref }.toSet() }.toSet()
        val all = (l1 + rec + tpl.filter { s -> s.items.map { it.entry.ref }.toSet() !in recipeSets })
            .filter { it.key !in req.hiddenKeys }
        // лучший вариант на каждый набор продуктов
        val unique = all.groupBy { s -> s.items.map { it.entry.ref }.sorted().joinToString(",") + "|" + (s.recipe?.id ?: s.block?.code ?: "") }
            .map { (_, v) -> v.maxByOrNull { it.score }!! }
        val sorted = unique.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.title }).take(req.limit)
        return SuggestResult(sorted, if (sorted.isEmpty()) reason ?: "подходящих вариантов нет" else null)
    }

    /** «Использовать остатки» (18.4): 3–5 блюд на 2 дня, по одному на О и У, из залежавшихся продуктов. */
    fun useLeftovers(base: SuggestRequest, days: Int = 2, targets: Map<CookSlot, Pair<Double, Double>>): List<Pair<String, Suggestion>> {
        val leftover = pantry.filter { e ->
            val exp = e.expiresDay?.let { it - today <= 3 } == true
            val par = e.product.parLevel
            val much = par != null && par > 0 && e.available >= par * 1.5
            val old = e.oldestPurchaseDay?.let { today - it >= 7 } == true
            (exp || much || old) && FoodRules.role(e.product) in setOf(FoodRole.PROTEIN, FoodRole.VEG, FoodRole.CARB, FoodRole.DAIRY)
        }.map { it.product.id }.toSet()
        if (leftover.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<String, Suggestion>>()
        var week = base.week
        var lastProtein = base.lastMainProteinId
        val used = mutableSetOf<String>()
        for (d in 0 until days) for (slot in listOf(CookSlot.LUNCH, CookSlot.DINNER)) {
            val (k, p) = targets[slot] ?: (600.0 to 40.0)
            val req = base.copy(slot = slot, targetKcal = k, targetProtein = p, expiringFirst = true, week = week,
                lastMainProteinId = lastProtein, mustInclude = leftover, limit = 10)
            val pick = suggest(req).suggestions.firstOrNull { s -> s.key !in used && s.mainProtein?.id != lastProtein } ?: continue
            used += pick.key
            lastProtein = pick.mainProtein?.id
            week = week + WeekCounters.ofTags(pick.items.flatMap { it.entry.product.tags }.toSet())
            result += "${if (d == 0) "Сегодня" else "Завтра"}, ${slot.label.lowercase()}" to pick
        }
        return result.take(5)
    }
}

/** Цели по умолчанию (18.1): остаток дня / число неотмеченных слотов. */
object CookTargets {
    fun default(s: AppSettings, eatenKcal: Double, eatenProtein: Double, openSlots: Int): Pair<Double, Double> {
        val n = max(openSlots, 1)
        val k = max(150.0, (s.kcalTarget - eatenKcal) / n)
        val p = max(10.0, (s.proteinTarget - eatenProtein) / n)
        return Math.round(k / 10.0) * 10.0 to Math.round(p).toDouble()
    }
}
