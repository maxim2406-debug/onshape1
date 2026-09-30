package com.ration.app

import com.ration.app.data.db.entity.Deduction
import com.ration.app.data.db.entity.StockItem
import com.ration.app.domain.inventory.InventoryMode
import com.ration.app.domain.inventory.InventoryParser
import com.ration.app.domain.inventory.InventoryPlanner
import com.ration.app.domain.inventory.Ledger
import com.ration.app.domain.inventory.StockSnapshot
import com.ration.app.domain.inventory.UnitConv
import com.ration.app.domain.model.MeasureUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InventoryTest {
    private val today = LocalDate.of(2026, 10, 1)
    private fun p(key: String) = TestData.product(key)

    @Test fun parse() {
        val r = InventoryParser.parse(
            """
            Коттедж 5% | 250 | г
            Яйцо | 8 | шт | ≈
            Лосось, сырой | 0,4 | кг | приблизительно | до 05.10
            # непонятная банка
            Картофель 1.5кг ≈
            Непонятно | много | шт
            """.trimIndent(), today,
        )
        assertEquals(4, r.lines.size)
        assertFalse(r.lines[0].approx)
        assertTrue(r.lines[1].approx)
        assertEquals(400.0, r.lines[2].qty, 1e-9)
        assertTrue(r.lines[2].approx)
        assertEquals(LocalDate.of(2026, 10, 5).toEpochDay(), r.lines[2].expiresDay)
        assertEquals(1500.0, r.lines[3].qty, 1e-9)
        assertTrue(r.lines[3].approx)
        assertEquals(1, r.errors.size)
        assertEquals(1, r.comments.size)
    }

    private val totals = mapOf(p("cottage").id to 500.0, p("egg").id to 2.0, p("tomato").id to 3.0)

    private fun lines(mode: InventoryMode) = InventoryParser.parse("Коттедж 5% | 250 | г\nЯйцо | 10 | шт | ≈", today).lines.map { l ->
        val prod = InventoryParser.match(l, TestData.seed.products).first!!
        Triple(prod, l, UnitConv.toProductUnit(l.qty, l.unit, prod)!!)
    }.let { InventoryPlanner.plan(mode, it, totals, TestData.seed.products) }

    @Test fun modes() {
        val add = lines(InventoryMode.ADD)
        assertEquals(750.0, add.first { it.product.key == "cottage" }.after, 0.0)
        assertEquals(12.0, add.first { it.product.key == "egg" }.after, 0.0)
        val set = lines(InventoryMode.SET)
        assertEquals(250.0, set.first { it.product.key == "cottage" }.after, 0.0)
        assertTrue(set.first { it.product.key == "egg" }.approx)
        assertTrue(set.none { it.product.key == "tomato" })
        val zero = lines(InventoryMode.SET_ZERO)
        val tomato = zero.single { it.product.key == "tomato" }
        assertTrue(tomato.zeroed)
        assertEquals(0.0, tomato.after, 0.0)
    }

    @Test fun applyAndUndo() {
        // SET: списать все партии и положить одну новую; отмена — вернуть списанное и убрать новую.
        val stock = listOf(StockItem(1, p("cottage").id, 300.0, 1), StockItem(2, p("cottage").id, 200.0, 2))
        val out = mutableListOf<Deduction>()
        StockSnapshot(stock, emptyList(), today.toEpochDay()).takeStock(p("cottage").id, 500.0, out)
        val (after, _) = Ledger.apply(stock, emptyList(), out, -1)
        val withNew = after + StockItem(3, p("cottage").id, 250.0, today.toEpochDay(), approx = true)
        assertEquals(250.0, withNew.sumOf { it.qty }, 1e-9)
        val (undone, _) = Ledger.apply(withNew.filter { it.id != 3L }, emptyList(), out, +1)
        assertEquals(stock, undone)
    }

    @Test fun unknownLineMatchesNothing() {
        val l = InventoryParser.parse("Квас хлебный | 1 | л", today).lines.single()
        assertEquals(null, InventoryParser.match(l, TestData.seed.products).first)
        assertEquals(MeasureUnit.ML, l.unit)
    }
}
