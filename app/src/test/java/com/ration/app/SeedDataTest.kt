package com.ration.app

import com.ration.app.domain.model.MeasureUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedDataTest {
    /** Таблицы раздела 7: код → ккал, белок. */
    private val expected = mapOf(
        "З1" to (490 to 40), "З2" to (510 to 43), "З3" to (500 to 43), "З4" to (530 to 41), "З5" to (550 to 41),
        "С1" to (620 to 43), "С2" to (600 to 44), "С3" to (620 to 43), "С4" to (650 to 46),
        "О1" to (600 to 42), "О2" to (580 to 44), "О3" to (600 to 45), "О4" to (600 to 42), "О5" to (550 to 35), "F" to (750 to 40),
        "П1" to (205 to 19), "П2" to (210 to 21), "П3" to (200 to 4), "П4" to (160 to 12), "П5" to (160 to 17), "П6" to (200 to 17), "П7" to (100 to 2),
        "У1" to (550 to 36), "У2" to (520 to 43), "У3" to (600 to 42), "У4" to (540 to 38), "У5" to (500 to 41), "У6" to (580 to 46),
        "У7" to (470 to 37), "У9" to (620 to 35), "У10" to (560 to 33),
        "Е1" to (190 to 7), "Е2" to (225 to 4), "Е3" to (170 to 17), "Е4" to (250 to 19), "Е5" to (220 to 6), "Е6" to (230 to 6),
    )

    @Test fun blocksMatchSection7() {
        val blocks = TestData.seed.blocks.associateBy { it.code }
        assertEquals(expected.keys, blocks.keys)
        expected.forEach { (code, kp) ->
            val b = blocks.getValue(code)
            assertEquals("$code ккал", kp.first.toDouble(), b.kcal, 0.0)
            assertEquals("$code белок", kp.second.toDouble(), b.protein, 0.0)
        }
    }

    @Test fun u8IsNotUsed() {
        assertNull(TestData.seed.blocks.firstOrNull { it.code == "У8" })
    }

    @Test fun streetBlocksDoNotDeduct() {
        listOf("О1", "О2", "О3", "О4", "О5", "F").forEach {
            val b = TestData.block(it)
            assertFalse(b.deductStock)
            assertTrue(TestData.ingredients(b).isEmpty())
        }
        assertTrue("ask_meat" in TestData.block("О2").tags)
        assertTrue("ask_meat" in TestData.block("О5").tags)
    }

    @Test fun rawWeightsFromSection8() {
        fun qty(code: String, key: String) = TestData.ingredients(TestData.block(code))
            .first { it.productId == TestData.product(key).id && !it.toTaste }.qty
        assertEquals(180.0, qty("У1", "salmon")!!, 0.0)
        assertEquals(100.0, qty("У1", "broccoli")!!, 0.0)
        assertEquals(200.0, qty("У3", "shrimp")!!, 0.0)
        assertEquals(100.0, qty("У3", "zucchini")!!, 0.0)
        assertEquals(160.0, qty("У4", "beef")!!, 0.0)
        assertEquals(100.0, qty("У4", "mushrooms")!!, 0.0)
        assertEquals(200.0, qty("У5", "dorado")!!, 0.0)
        assertEquals(200.0, qty("У6", "cod")!!, 0.0)
        assertEquals(60.0, qty("У6", "pasta")!!, 0.0)
        assertEquals(150.0, qty("У6", "crushed_tomatoes")!!, 0.0)
        assertEquals(180.0, qty("У7", "pork")!!, 0.0)
        assertEquals(200.0, qty("У7", "batat")!!, 0.0)
        assertEquals(3.0, qty("У9", "egg")!!, 0.0)
        assertEquals(300.0, qty("У9", "tomato")!!, 0.0)
        assertEquals(0.5, qty("У9", "onion")!!, 0.0)
        assertEquals(160.0, qty("У10", "mackerel")!!, 0.0)
    }

    @Test fun productsAndReferences() {
        val s = TestData.seed
        assertEquals(s.products.size, s.products.map { it.id }.toSet().size)
        val ids = s.products.map { it.id }.toSet()
        s.ingredients.forEach { i ->
            i.productId?.let { assertTrue(it in ids) }
            i.altProductIds.forEach { assertTrue(it in ids) }
            if (!i.toTaste) { assertNotNull(i.qty); assertNotNull(i.unit) }
        }
        val egg = TestData.product("egg")
        assertEquals(MeasureUnit.PCS, egg.unit)
        assertEquals(60.0, egg.gramsPerPiece!!, 0.0)
        assertNull(TestData.product("tuna").gramsPerPiece)
        assertEquals(10.0, TestData.product("rice_cakes").gramsPerPiece!!, 0.0)
        assertEquals(95.0, TestData.product("cottage").kcalPer100, 0.0)
        assertEquals(56.0, TestData.product("protein_yogurt").kcalPer100, 0.0)
        assertTrue(TestData.product("paprika").untracked)
        assertFalse(TestData.product("olive_oil").untracked)
        assertFalse(TestData.product("lemon").untracked)
        assertTrue("קוטג'" in TestData.product("cottage").aliases)
    }

    @Test fun prepTemplates() {
        val t = TestData.seed.templates.associateBy { it.key }
        assertEquals(setOf("chicken_sv", "potato_batat", "eggs_boiled", "veg_cut"), t.keys)
        assertEquals(800.0, t.getValue("chicken_sv").inputs.single().qty, 0.0)
        assertEquals(4.0, t.getValue("chicken_sv").outputs.single().defaultPortions, 0.0)
        assertEquals(150.0, t.getValue("chicken_sv").outputs.single().portionGrams!!, 0.0)
        assertEquals(13.0, t.getValue("potato_batat").inputs.first { it.productId == TestData.product("olive_oil").id }.qty, 0.0)
        assertEquals(10.0, t.getValue("eggs_boiled").inputs.single().qty, 0.0)
        assertEquals(3, t.getValue("veg_cut").shelfDays)
        assertEquals(3, t.values.count { it.inSaturdayBatch })
    }

    @Test fun dinnersHaveRecipes() {
        listOf("У1", "У2", "У3", "У4", "У5", "У6", "У7", "У9", "У10", "З4", "З5", "Е2").forEach {
            assertTrue("$it рецепт", TestData.block(it).recipeSteps.isNotEmpty())
        }
    }
}
