package com.ration.app

import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.seed.PrepKeys
import com.ration.app.domain.backup.BackupCodec
import com.ration.app.domain.backup.BackupData
import com.ration.app.domain.inventory.Ledger
import com.ration.app.domain.inventory.StockSnapshot
import com.ration.app.domain.meal.CustomBlocks
import com.ration.app.domain.meal.FoodCatalog
import com.ration.app.domain.meal.ItemsConsumption
import com.ration.app.domain.meal.MealItems
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.Tags
import com.ration.app.domain.plan.WeekCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealBuilderTest {
    private val today = 20_000L
    private val cat = FoodCatalog(TestData.products, prepOutputs = TestData.seed.templates.flatMap { it.outputs }.associateBy { it.key })
    private fun p(key: String) = TestData.product(key)

    @Test fun shrimpCucumberTomatoMatchesReference() {
        val items = listOf(
            MealItems.ofProduct(p("shrimp"), 200.0, MeasureUnit.G),
            MealItems.ofProduct(p("cucumber"), 100.0, MeasureUnit.G),
            MealItems.ofProduct(p("tomato"), 100.0, MeasureUnit.G),
        )
        assertEquals(85 * 2.0 + 15 + 18, MealItems.sumKcal(items), 1e-9)
        assertEquals(18 * 2.0 + 0.7 + 0.9, MealItems.sumProtein(items), 1e-9)
    }

    @Test fun replaceZucchiniInU3() {
        val u3 = TestData.block("У3")
        val original = MealItems.ofBlock(TestData.ingredients(u3), cat)
        val zucchini = original.first { it.productId == p("zucchini").id }
        val edited = original - zucchini + MealItems.ofProduct(p("cucumber"), 100.0, MeasureUnit.G) + MealItems.ofProduct(p("tomato"), 100.0, MeasureUnit.G)
        val (k, pr) = MealItems.editedBlockTotals(u3, original, edited)
        assertEquals(600.0 - 17 + 15 + 18, k, 1e-9)
        assertEquals(42.0 - 1.2 + 0.7 + 0.9, pr, 1e-9)
        // без изменений — ровно табличные значения
        assertEquals(600.0, MealItems.editedBlockTotals(u3, original, original).first, 1e-9)

        val stock = listOf("shrimp", "rice", "cucumber", "tomato", "avocado", "olive_oil").mapIndexed { i, k ->
            StockItem(i + 1L, p(k).id, if (p(k).unit == MeasureUnit.PCS) 5.0 else 1000.0, today)
        }
        val r = ItemsConsumption.plan(edited, cat, StockSnapshot(stock, emptyList(), today))
        val cucumberStock = stock.first { it.productId == p("cucumber").id }.id
        assertEquals(100.0 / 120.0, r.deductions.single { it.stockItemId == cucumberStock }.amount, 1e-9)
        assertTrue(r.deductions.none { d -> stock.first { it.id == d.stockItemId }.productId == p("zucchini").id })
        val tags = MealItems.logTags(r.items, u3, compositionEdited = true)
        assertTrue(Tags.SEAFOOD in tags)
        assertTrue(Tags.HOME in tags)
    }

    @Test fun missingProductIsLoggedWithoutDeduction() {
        val stock = listOf(StockItem(1, p("salmon").id, 50.0, today))
        val items = listOf(MealItems.ofProduct(p("salmon"), 180.0, MeasureUnit.G), MealItems.ofProduct(p("broccoli"), 100.0, MeasureUnit.G))
        val r = ItemsConsumption.plan(items, cat, StockSnapshot(stock, emptyList(), today))
        assertEquals(50.0, r.items[0].deducted, 1e-9)
        assertEquals(0.0, r.items[1].deducted, 1e-9)
        assertEquals(100.0, ItemsConsumption.shortfall(r.items[1], cat), 1e-9)
        val (after, _) = Ledger.apply(stock, emptyList(), r.deductions, -1)
        assertEquals(0.0, after.single().qty, 1e-9)
        assertTrue(after.all { it.qty >= 0 })
    }

    @Test fun countersByIngredientTagsAndUndo() {
        val u1 = TestData.block("У1")
        val original = MealItems.ofBlock(TestData.ingredients(u1), cat)
        val salmon = original.first { it.productId == p("salmon").id }
        val edited = original - salmon + MealItems.ofProduct(p("chicken_thigh"), 180.0, MeasureUnit.G)
        val editedTags = MealItems.logTags(edited, u1, compositionEdited = true)
        assertFalse(Tags.FISH in editedTags)
        assertFalse(Tags.FATTY_FISH in editedTags)
        val plainTags = MealItems.logTags(original, u1, compositionEdited = false)
        assertTrue(Tags.FATTY_FISH in plainTags)
        fun log(tags: List<String>) = MealLog(0, today, 0, null, name = "x", kcal = 1.0, protein = 1.0, source = MealSource.CUSTOM, tags = tags)
        assertEquals(0, WeekCounters.of(listOf(log(editedTags)), emptyList()).fish)
        assertEquals(1, WeekCounters.of(listOf(log(plainTags)), emptyList()).fattyFish)

        val stock = listOf(StockItem(1, p("chicken_thigh").id, 500.0, today), StockItem(2, p("broccoli").id, 300.0, today))
        val preps = listOf(Prep(5, 2, PrepKeys.BATAT, "батат", today, today + 3, 3.0, 3.0, 200.0))
        val r = ItemsConsumption.plan(edited, cat, StockSnapshot(stock, preps, today))
        val (s1, p1) = Ledger.apply(stock, preps, r.deductions, -1)
        assertEquals(320.0, s1.first { it.id == 1L }.qty, 1e-9)
        assertEquals(2.0, p1.single().portionsLeft, 1e-9)
        val (s2, p2) = Ledger.apply(s1, p1, r.deductions, +1)
        assertEquals(stock, s2); assertEquals(preps, p2)
    }

    @Test fun eggsFromItems() {
        val items = listOf(MealItems.ofProduct(p("egg"), 3.0, MeasureUnit.PCS), MealItems.ofPrep(PrepKeys.EGGS, 2.0, MeasureUnit.PCS, cat)!!)
        assertEquals(5.0, MealItems.eggs(items, TestData.products), 1e-9)
    }

    @Test fun customBlockSaveAndBackup() {
        val items = listOf(
            MealItems.ofProduct(p("shrimp"), 200.0, MeasureUnit.G),
            MealItems.ofProduct(p("cucumber"), 150.0, MeasureUnit.G),
            MealItems.ofProduct(p("rice"), 200.0, MeasureUnit.G),
            MealItems.ofProduct(p("olive_oil"), 10.0, MeasureUnit.ML),
        )
        val code = CustomBlocks.nextCode(listOf("У1", "M1", "M7"))
        assertEquals("M8", code)
        val (block, ings) = CustomBlocks.build(code, "Креветки с рисом", MealKind.DINNER, items)
        assertTrue(block.custom)
        assertTrue(Tags.SEAFOOD in block.tags)
        assertEquals(Math.round(170 + 22.5 + 230 + 80.0).toDouble(), block.kcal, 0.0)
        assertTrue(CustomBlocks.fitsSlot(block, TestData.seed.blocks))
        val saved = block.copy(id = 500)
        val data = BackupData(products = TestData.seed.products, blocks = TestData.seed.blocks + saved,
            blockIngredients = ings.mapIndexed { i, it -> it.copy(id = 1000L + i, blockId = 500) })
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data, null), null))
    }
}
