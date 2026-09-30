package com.ration.app

import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.seed.PrepKeys
import com.ration.app.domain.cook.Cookbook
import com.ration.app.domain.cook.FoodRules
import com.ration.app.domain.cook.SuggestRequest
import com.ration.app.domain.cook.Suggester
import com.ration.app.domain.inventory.Ledger
import com.ration.app.domain.inventory.StockSnapshot
import com.ration.app.domain.meal.FoodCatalog
import com.ration.app.domain.meal.ItemsConsumption
import com.ration.app.domain.model.CookMethod
import com.ration.app.domain.model.CookSlot
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.Tags
import com.ration.app.domain.plan.WeekCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SuggesterTest {
    private val today = 20_000L
    private val products = TestData.products
    private fun p(key: String) = TestData.seed.products.first { it.key == key }
    private fun byName(prefix: String) = TestData.seed.products.first { it.name.startsWith(prefix) }
    private val cookbook by lazy {
        val f = listOf(File("src/main/assets/cookbook.json"), File("app/src/main/assets/cookbook.json")).first { it.exists() }
        Cookbook.parse(f.readText())
    }
    private val outputs = TestData.seed.templates.flatMap { it.outputs }.associateBy { it.key }

    private fun suggester(stock: List<StockItem>, preps: List<Prep> = emptyList()) = Suggester(
        products, stock, preps, outputs, TestData.seed.blocks, TestData.seed.ingredients.groupBy { it.blockId },
        cookbook.recipes, cookbook.defaults, today,
    )

    private var nextId = 1L
    private fun s(key: String, qty: Double, expires: Long? = null) = StockItem(nextId++, p(key).id, qty, today - 1, expires)
    private fun sn(name: String, qty: Double, expires: Long? = null) = StockItem(nextId++, byName(name).id, qty, today - 1, expires)

    /** 6–8 продуктов в холодильнике. */
    private fun fridge(expiringChicken: Boolean = false) = listOf(
        s("salmon", 400.0), sn("Куриная грудка без кожи, сырая", 600.0, if (expiringChicken) today + 1 else null),
        s("broccoli", 400.0), s("zucchini", 300.0), s("potato", 1500.0), s("rice", 450.0),
        s("olive_oil", 500.0), s("egg", 10.0), s("cottage", 500.0), s("apple", 4.0),
    )

    private fun req(slot: CookSlot = CookSlot.DINNER, k: Double = 600.0, pr: Double = 40.0) = SuggestRequest(slot, k, pr)

    @Test fun emptyStockGivesReason() {
        val r = suggester(emptyList()).suggest(req())
        assertTrue(r.suggestions.isEmpty())
        assertNotNull(r.reason)
    }

    @Test fun dinnerVariantsFromFridgeFast() {
        val sg = suggester(fridge())
        val t0 = System.nanoTime()
        val r = sg.suggest(req())
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("вариантов: ${r.suggestions.size}", r.suggestions.size >= 3)
        assertTrue("время $ms мс", ms < 1000)
        r.suggestions.forEach { v ->
            assertTrue("${v.title}: ${v.kcal}", v.kcal in 540.0..660.0)
            assertTrue("${v.title}: ${v.protein}", v.protein >= 36.0 - 1e-6)
        }
    }

    @Test fun neverRequiresMissingStock() {
        val stock = fridge()
        val sg = suggester(stock)
        for (slot in CookSlot.entries) {
            for (v in sg.suggest(req(slot, if (slot == CookSlot.SNACK || slot == CookSlot.EVENING) 200.0 else 550.0,
                if (slot == CookSlot.SNACK || slot == CookSlot.EVENING) 12.0 else 35.0)).suggestions) {
                v.items.forEach { i -> assertTrue("${v.title}: ${i.entry.product.name}", i.qty <= i.entry.available + 1e-6) }
                val byRef = v.items.groupBy { it.entry.ref }
                byRef.forEach { (_, l) -> assertTrue(l.sumOf { it.qty } <= l.first().entry.available + 1e-6) }
            }
        }
    }

    @Test fun redMeatLimitRespected() {
        val stock = fridge() + s("beef", 500.0)
        val r = suggester(stock).suggest(req().copy(week = WeekCounters(redMeat = 2)))
        assertTrue(r.suggestions.none { v -> v.items.any { Tags.RED_MEAT in it.entry.product.tags } })
        val free = suggester(stock).suggest(req().copy(week = WeekCounters(redMeat = 0), limit = 50))
        assertTrue(free.suggestions.any { v -> v.items.any { Tags.RED_MEAT in it.entry.product.tags } })
    }

    @Test fun deterministicOrder() {
        val a = suggester(fridge()).suggest(req()).suggestions.map { it.key }
        val b = suggester(fridge()).suggest(req()).suggestions.map { it.key }
        assertEquals(a, b)
    }

    @Test fun expiringFirstRanksHigher() {
        val chicken = byName("Куриная грудка без кожи, сырая").id
        val fresh = suggester(fridge()).suggest(req().copy(expiringFirst = true, limit = 50)).suggestions
        val soon = suggester(fridge(expiringChicken = true)).suggest(req().copy(expiringFirst = true, limit = 50)).suggestions
        fun rank(list: List<com.ration.app.domain.cook.Suggestion>) = list.indexOfFirst { v -> v.items.any { it.entry.product.id == chicken } }
        assertTrue(rank(soon) >= 0)
        assertTrue("скоро ${rank(soon)} свежий ${rank(fresh)}", rank(soon) <= rank(fresh))
        assertTrue(soon.first().items.any { it.entry.product.id == chicken })
    }

    @Test fun recordingDeductsExactly() {
        val stock = fridge()
        val preps = listOf(Prep(90, 3, PrepKeys.EGGS, "яйца", today, today + 4, 10.0, 6.0, null))
        val sg = suggester(stock, preps)
        val v = sg.suggest(req()).suggestions.first()
        val items = v.items.map { it.toMealItem() }
        val cat = FoodCatalog(products, prepOutputs = outputs)
        val r = ItemsConsumption.plan(items, cat, StockSnapshot(stock, preps, today))
        val (after, _) = Ledger.apply(stock, preps, r.deductions, -1)
        v.items.filter { it.entry.prepKey == null }.forEach { i ->
            val before = stock.filter { it.productId == i.entry.product.id }.sumOf { it.qty }
            val now = after.filter { it.productId == i.entry.product.id }.sumOf { it.qty }
            assertEquals(i.qty, before - now, 1e-9)
        }
    }

    @Test fun untrackedAndHiddenExcluded() {
        val hiddenProducts = products.mapValues { (_, v) -> if (v.key == "salmon") v.copy(hidden = true) else v }
        val stock = fridge() + s("paprika", 50.0)
        val sg = Suggester(hiddenProducts, stock, emptyList(), outputs, TestData.seed.blocks, TestData.seed.ingredients.groupBy { it.blockId },
            cookbook.recipes, cookbook.defaults, today)
        val all = sg.suggest(req().copy(limit = 100)).suggestions
        assertTrue(all.none { v -> v.items.any { it.entry.product.untracked || it.entry.product.key == "salmon" } })
    }

    @Test fun cookbookIsValid() {
        assertTrue(cookbook.recipes.size >= 48)
        assertEquals(cookbook.recipes.size, cookbook.recipes.map { it.id }.toSet().size)
        val catalog = TestData.seed.products
        cookbook.recipes.forEach { r -> assertEquals("${r.id} ${r.title}", emptyList<String>(), Cookbook.validate(r, catalog)) }
        fun count(vararg slots: String) = cookbook.recipes.count { r -> r.slots.any { it in slots } }
        assertTrue(count("З") >= 10)
        assertTrue(cookbook.recipes.count { Tags.FISH in it.tags || Tags.SEAFOOD in it.tags } >= 14)
    }

    @Test fun roleSubstitutionKeepsMethod() {
        val rawChicken = byName("Куриная грудка без кожи, сырая")
        assertFalse(FoodRules.compatible(rawChicken, CookMethod.RAW))
        val drum = byName("Куриная голень")
        assertFalse(FoodRules.compatible(drum, CookMethod.PAN))
        assertFalse("poultry" in FoodRules.groups(drum))
        assertTrue(FoodRules.compatible(p("tuna"), CookMethod.RAW))
        // рецепт с сырой курицей «без готовки» не собирается: ни один вариант RAW не содержит сырое мясо
        val sg = suggester(fridge())
        for (slot in CookSlot.entries) sg.suggest(req(slot).copy(limit = 100)).suggestions
            .filter { it.method == CookMethod.RAW && it.recipe != null }
            .forEach { v -> v.items.forEach { assertTrue(v.title, FoodRules.compatible(it.entry.product, CookMethod.RAW)) } }
    }

    @Test fun recipeWithUnfilledRoleIsNotShown() {
        val stock = listOf(s("salmon", 400.0), s("olive_oil", 100.0))
        val v = suggester(stock).suggest(req().copy(limit = 100)).suggestions
        assertTrue(v.none { it.recipe?.id == "f07" })
    }

    @Test fun cookbookUpdateKeepsUserEdits() {
        val user = Cookbook.userCopy(cookbook.recipes.first(), "user-1", steps = listOf("а", "б", "в"))
        val existing = cookbook.recipes + user
        val (del, ins) = Cookbook.merge(existing, cookbook.copy(version = 2))
        assertFalse("user-1" in del)
        assertTrue(ins.none { it.id == "user-1" })
        assertTrue(ins.all { it.version == 2 })
    }

    @Test fun leftovers() {
        val stock = fridge(expiringChicken = true)
        val plan = suggester(stock).useLeftovers(SuggestRequest(CookSlot.LUNCH, 600.0, 40.0), 2,
            mapOf(CookSlot.LUNCH to (600.0 to 40.0), CookSlot.DINNER to (550.0 to 35.0)))
        assertTrue(plan.size in 1..5)
        val chicken = byName("Куриная грудка без кожи, сырая").id
        assertTrue(plan.first().second.items.any { it.entry.product.id == chicken })
    }

    @Test fun portionUnits() {
        assertEquals(1.0, FoodRules.step(p("egg")), 0.0)
        assertEquals(0.5, FoodRules.step(p("avocado")), 0.0)
        assertEquals(MeasureUnit.PCS, p("egg").unit)
    }
}
