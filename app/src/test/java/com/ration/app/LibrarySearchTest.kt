package com.ration.app

import com.ration.app.data.db.entity.Product
import com.ration.app.data.seed.SeedData
import com.ration.app.domain.library.CatalogSync
import com.ration.app.domain.library.FoodEntry
import com.ration.app.domain.library.FoodFilters
import com.ration.app.domain.library.FoodSearch
import com.ration.app.domain.library.LibraryParser
import com.ration.app.domain.library.LibraryPrompts
import com.ration.app.domain.library.NutritionBasis
import com.ration.app.domain.library.NutritionInput
import com.ration.app.domain.library.SortMode
import com.ration.app.domain.meal.MealItems
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.ProductSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    private val all = TestData.seed.products
    private fun entries(list: List<Product> = all) = list.map { FoodEntry.of(it, false) }

    @Test fun normalization() {
        assertEquals(FoodSearch.normalize("Тёмный  ШОКОЛАД!"), FoodSearch.normalize("темный шоколад"))
        assertEquals(FoodSearch.normalize("שלום"), FoodSearch.normalize("שָׁלוֹם"))
        assertEquals(FoodSearch.normalize("חזה עופ"), FoodSearch.normalize("חזה עוף"))
        assertEquals(FoodSearch.normalize("קוטג"), FoodSearch.normalize("קוטג'"))
    }

    @Test fun search() {
        val e = entries()
        val r1 = FoodSearch.search(e, "курица грудка", SortMode.FREQUENT).map { it.entry.name }
        assertTrue(r1.first().startsWith("Куриная грудка"))
        val r2 = FoodSearch.search(e, "חזה עוף", SortMode.FREQUENT).map { it.entry.name }
        assertTrue(r2.first().startsWith("Куриная грудка без кожи, сырая"))
        assertTrue(FoodSearch.search(e, "лос", SortMode.FREQUENT).first().entry.name.startsWith("Лосось"))
        assertTrue(FoodSearch.search(e, "ё", SortMode.FREQUENT).isNotEmpty())
        assertTrue(FoodSearch.search(e, "несуществующийпродукт", SortMode.FREQUENT).isEmpty())
        val h = FoodSearch.search(e, "грудка", SortMode.FREQUENT).first()
        assertTrue(h.highlights.isNotEmpty())
    }

    @Test fun sorting() {
        val e = entries()
        val kcalAsc = FoodSearch.search(e, "", SortMode.KCAL_ASC).map { it.entry.kcal100!! }
        assertEquals(kcalAsc.sorted(), kcalAsc)
        val kcalDesc = FoodSearch.search(e, "", SortMode.KCAL_DESC).map { it.entry.kcal100!! }
        assertEquals(kcalDesc.sortedDescending(), kcalDesc)
        val ppk = FoodSearch.search(e, "", SortMode.PROTEIN_PER_KCAL)
        val vals = ppk.mapNotNull { it.entry.proteinPer100Kcal }
        assertEquals(vals.sortedDescending(), vals)
        // продукт без данных — в конце; стабильность: при равных ккал — алфавит
        val noData = FoodEntry.of(all.first(), false).copy(id = "x", name = "Аааа без данных", kcal100 = null, protein100 = null)
        val withNull = FoodSearch.search(e + noData, "", SortMode.KCAL_ASC)
        assertEquals("x", withNull.last().entry.id)
        val equal = listOf(noData.copy(id = "b", name = "Банан", kcal100 = 10.0), noData.copy(id = "a", name = "Апельсин", kcal100 = 10.0))
        assertEquals(listOf("a", "b"), FoodSearch.search(equal, "", SortMode.KCAL_DESC).map { it.entry.id })
        val alpha = FoodSearch.search(listOf(noData.copy(id = "1", name = "Ёлка"), noData.copy(id = "2", name = "Елка"), noData.copy(id = "3", name = "Жук")), "", SortMode.ALPHA_ASC)
        assertEquals(listOf("2", "1", "3"), alpha.map { it.entry.id })
    }

    @Test fun filtersCombine() {
        val e = entries()
        val fish = FoodSearch.search(e, "лосось", SortMode.KCAL_ASC, FoodFilters(tags = setOf("salty")))
        assertTrue(fish.isNotEmpty() && fish.all { "salty" in it.entry.tags })
        val kcal = fish.map { it.entry.kcal100!! }
        assertEquals(kcal.sorted(), kcal)
        val hidden = e.map { if (it.name.startsWith("Лосось, сырой")) it.copy(hidden = true) else it }
        assertTrue(FoodSearch.search(hidden, "лосось сырой", SortMode.FREQUENT).none { it.entry.name == "Лосось, сырой" })
        assertTrue(FoodSearch.search(hidden, "лосось сырой", SortMode.FREQUENT, FoodFilters(showHidden = true)).any { it.entry.name == "Лосось, сырой" })
        val cat = FoodSearch.search(e, "", SortMode.ALPHA_ASC, FoodFilters(categories = setOf("сыры")))
        assertTrue(cat.size >= 20 && cat.all { it.entry.category == "сыры" })
    }

    @Test fun catalogHasNoDuplicatesAndSyncIsIdempotent() {
        val names = all.map { FoodSearch.normalize(it.name) }
        assertEquals(names.size, names.toSet().size)
        assertEquals(all.size, all.map { it.key }.toSet().size)
        assertTrue(all.size > 250)
        val first = CatalogSync.plan(emptyList(), all, SeedData.OLD_CATEGORIES)
        assertEquals(all.size, first.inserts.size)
        val existing = all.mapIndexed { i, p -> p.copy(id = i + 1L) }
        val again = CatalogSync.plan(existing, all, SeedData.OLD_CATEGORIES)
        assertTrue(again.inserts.isEmpty())
        assertTrue(again.updates.isEmpty())
    }

    @Test fun syncDoesNotOverwriteUserOrLabel() {
        val base = all.mapIndexed { i, p -> p.copy(id = i + 1L) }
        val edited = base.map {
            when (it.key) {
                "salmon" -> it.copy(kcalPer100 = 180.0, source = ProductSource.USER, category = "Рыба", tags = emptyList())
                "cottage" -> it.copy(category = "Молочное")
                else -> it
            }
        }
        val plan = CatalogSync.plan(edited, all, SeedData.OLD_CATEGORIES)
        assertTrue(plan.updates.none { it.key == "salmon" || it.key == "cottage" })
        // старый справочный продукт с категорией v1 получает новую категорию и теги
        val v1 = base.map { if (it.key == "tuna") it.copy(category = "Консервы", tags = emptyList()) else it }
        val upd = CatalogSync.plan(v1, all, SeedData.OLD_CATEGORIES).updates.single()
        assertEquals("консервы", upd.category)
        assertTrue("fish" in upd.tags)
        assertEquals(180.0.let { 116.0 }, upd.kcalPer100, 0.0)
    }

    @Test fun perPortionConversion() {
        assertEquals(400.0, NutritionInput.per100(200.0, NutritionBasis.PER_PORTION, 50.0)!!, 1e-9)
        assertEquals(56.0, NutritionInput.per100(56.0, NutritionBasis.PER_100ML, null)!!, 0.0)
        assertNull(NutritionInput.per100(200.0, NutritionBasis.PER_PORTION, null))
    }

    @Test fun bulkAdd() {
        val text = """
            Творог 9% | 159 | 16.7 | яйца и молочные
            Лосось, сырой | 208 | 20
            Лососй, сырой | 200 | 20
            Кускус дорогой | | 12 # не уверен
            Творог 9% | 159 | 16.7
            Хумус с перцем | 250 | 7 | соусы | 
            мусор
        """.trimIndent()
        val r = LibraryParser.parse(text, all)
        assertEquals(listOf("Творог 9%", "Хумус с перцем"), r.ok.map { it.name })
        assertTrue(r.errors.any { it.message.contains("уже есть") })
        assertTrue(r.errors.any { it.message.contains("не уверен") })
        assertTrue(r.errors.any { it.message.contains("повтор") })
        assertTrue(r.errors.any { it.raw == "мусор" })
        assertEquals("Лосось, сырой", r.similar.single().existing.name)
    }

    @Test fun similarAndRawCooked() {
        assertNotNull(LibraryParser.findSimilar("Гречка варёная", all))
        val buckwheatCooked = all.first { it.name == "Гречка, варёная" }
        assertEquals("Гречка, сухая", LibraryParser.rawCookedCounterpart(buckwheatCooked, all)?.name)
    }

    @Test fun edibleFractionAndSnapshot() {
        val drum = all.first { it.name.startsWith("Куриная голень") }
        val item = MealItems.ofProduct(drum, 200.0, MeasureUnit.G)
        assertEquals(130.0, item.grams, 1e-9)
        assertEquals(160 * 1.3, item.kcal, 1e-9)
        val changed = drum.copy(kcalPer100 = 999.0)
        assertEquals(160 * 1.3, item.kcal, 1e-9)
        assertFalse(MealItems.ofProduct(changed, 200.0, MeasureUnit.G).kcal == item.kcal)
    }

    @Test fun promptExcludesNothingExisting() {
        val p = LibraryPrompts.fridgeList(all)
        assertFalse(p.contains(LibraryPrompts.PLACEHOLDER))
        assertTrue(p.contains("\nЛосось, сырой\n"))
        assertTrue(LibraryPrompts.fridgePhoto(all).contains("приблизительно"))
    }
}
