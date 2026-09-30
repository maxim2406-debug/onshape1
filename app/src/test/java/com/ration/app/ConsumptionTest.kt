package com.ration.app

import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.seed.PrepKeys
import com.ration.app.domain.inventory.Consumption
import com.ration.app.domain.inventory.Ledger
import com.ration.app.domain.inventory.StockSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionTest {
    private val today = 20_000L
    private val products = TestData.products

    @Test fun prepBlockTakesEarliestExpiryFirst() {
        val oil = TestData.product("olive_oil")
        val lemon = TestData.product("lemon")
        val stock = listOf(StockItem(1, oil.id, 500.0, today - 3), StockItem(2, lemon.id, 3.0, today - 3))
        val preps = listOf(
            Prep(10, 1, PrepKeys.CHICKEN, "курица", today - 1, today + 3, 4.0, 4.0, 150.0),
            Prep(11, 1, PrepKeys.CHICKEN, "курица", today - 3, today + 1, 4.0, 1.0, 150.0),
            Prep(12, 2, PrepKeys.POTATO, "картофель", today - 1, today + 3, 3.0, 3.0, 250.0),
            Prep(13, 1, PrepKeys.CHICKEN, "курица просрочена", today - 6, today - 1, 4.0, 4.0, 150.0),
        )
        val plan = Consumption.plan(TestData.ingredients(TestData.block("С1")), 1.0, products, StockSnapshot(stock, preps, today))
        assertTrue(plan.shortages.isEmpty())
        val chicken = plan.deductions.filter { it.prepId in setOf(10L, 11L, 13L) }
        assertEquals(1, chicken.size)
        assertEquals(11L, chicken.single().prepId)
        assertEquals(1.0, chicken.single().amount, 1e-9)
        assertEquals(1.0, plan.deductions.single { it.prepId == 12L }.amount, 1e-9)
        assertEquals(13.0, plan.deductions.single { it.stockItemId == 1L }.amount, 1e-9)
        assertEquals(0.25, plan.deductions.single { it.stockItemId == 2L }.amount, 1e-9)
    }

    @Test fun rawBlockUsesRawWeightsFifo() {
        val salmon = TestData.product("salmon")
        val broccoli = TestData.product("broccoli")
        val stock = listOf(
            StockItem(1, salmon.id, 100.0, today - 5),
            StockItem(2, salmon.id, 500.0, today - 1),
            StockItem(3, broccoli.id, 400.0, today - 2),
            StockItem(4, TestData.product("olive_oil").id, 500.0, today - 2),
            StockItem(5, TestData.product("lemon").id, 2.0, today - 2),
        )
        val preps = listOf(Prep(20, 2, PrepKeys.BATAT, "батат", today, today + 4, 2.0, 2.0, 200.0))
        val plan = Consumption.plan(TestData.ingredients(TestData.block("У1")), 1.0, products, StockSnapshot(stock, preps, today))
        assertTrue(plan.shortages.isEmpty())
        assertEquals(100.0, plan.deductions.single { it.stockItemId == 1L }.amount, 1e-9)
        assertEquals(80.0, plan.deductions.single { it.stockItemId == 2L }.amount, 1e-9)
        assertEquals(100.0, plan.deductions.single { it.stockItemId == 3L }.amount, 1e-9)
    }

    @Test fun multiplierAndPieceConversion() {
        val tomato = TestData.product("tomato")
        val egg = TestData.product("egg")
        val stock = listOf(StockItem(1, tomato.id, 10.0, today), StockItem(2, egg.id, 12.0, today))
        val plan = Consumption.plan(TestData.ingredients(TestData.block("У9")), 0.5, products, StockSnapshot(stock, emptyList(), today))
        // 300 г помидоров × 0,5 = 150 г = 1,25 шт по 120 г
        assertEquals(1.25, plan.deductions.single { it.stockItemId == 1L }.amount, 1e-9)
        assertEquals(1.5, plan.deductions.single { it.stockItemId == 2L }.amount, 1e-9)
    }

    @Test fun shortageIsReportedButRecorded() {
        val salmon = TestData.product("salmon")
        val stock = listOf(StockItem(1, salmon.id, 50.0, today))
        val plan = Consumption.plan(TestData.ingredients(TestData.block("У1")), 1.0, products, StockSnapshot(stock, emptyList(), today))
        assertEquals(50.0, plan.deductions.single { it.stockItemId == 1L }.amount, 1e-9)
        val salmonShort = plan.shortages.single { it.label.startsWith("лосось") }
        assertEquals(130.0, salmonShort.missing, 1e-9)
        assertTrue(plan.shortages.any { it.label.startsWith("батат") })
    }

    @Test fun alternativeUsedWhenPrimaryMissing() {
        val seabass = TestData.product("seabass")
        val stock = listOf(StockItem(1, seabass.id, 400.0, today))
        val plan = Consumption.plan(TestData.ingredients(TestData.block("У5")), 1.0, products, StockSnapshot(stock, emptyList(), today))
        assertEquals(200.0, plan.deductions.single { it.stockItemId == 1L }.amount, 1e-9)
        assertTrue(plan.shortages.none { it.label.startsWith("дорада") })
    }

    @Test fun undoRestoresStock() {
        val salmon = TestData.product("salmon")
        val stock = listOf(StockItem(1, salmon.id, 100.0, today - 5), StockItem(2, salmon.id, 500.0, today - 1))
        val preps = listOf(Prep(20, 2, PrepKeys.BATAT, "батат", today, today + 4, 2.0, 2.0, 200.0))
        val plan = Consumption.plan(TestData.ingredients(TestData.block("У1")), 1.0, products, StockSnapshot(stock, preps, today))
        val (afterStock, afterPreps) = Ledger.apply(stock, preps, plan.deductions, -1)
        assertEquals(420.0, afterStock.sumOf { it.qty }, 1e-9)
        assertEquals(1.0, afterPreps.single().portionsLeft, 1e-9)
        val (undoStock, undoPreps) = Ledger.apply(afterStock, afterPreps, plan.deductions, +1)
        assertEquals(stock, undoStock)
        assertEquals(preps, undoPreps)
    }

    @Test fun untrackedAndToTasteAreSkipped() {
        val plan = Consumption.plan(TestData.ingredients(TestData.block("У6")), 1.0, products, StockSnapshot(emptyList(), emptyList(), today))
        assertTrue(plan.shortages.none { it.label.contains("чеснок") || it.label.contains("орегано") })
    }

    @Test fun eggsCount() {
        val egg = TestData.product("egg")
        assertEquals(2.0, Consumption.eggs(TestData.ingredients(TestData.block("З2")), egg.id, PrepKeys.EGGS), 0.0)
        assertEquals(3.0, Consumption.eggs(TestData.ingredients(TestData.block("У9")), egg.id, PrepKeys.EGGS), 0.0)
        assertEquals(0.0, Consumption.eggs(TestData.ingredients(TestData.block("П1")), egg.id, PrepKeys.EGGS), 0.0)
    }
}
