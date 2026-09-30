package com.ration.app

import com.ration.app.data.db.entity.Product
import com.ration.app.domain.inventory.AlertState
import com.ration.app.domain.inventory.StockLevel
import com.ration.app.domain.inventory.ThresholdTracker
import com.ration.app.domain.inventory.Thresholds
import com.ration.app.domain.model.MeasureUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdTest {
    private fun p(id: Long, par: Double, unit: MeasureUnit = MeasureUnit.G, untracked: Boolean = false) =
        Product(id = id, name = "p$id", unit = unit, kcalPer100 = 0.0, proteinPer100 = 0.0, parLevel = par, untracked = untracked)

    @Test fun levels() {
        assertEquals(StockLevel.GREEN, Thresholds.level(41.0, 100.0, 40, 20))
        assertEquals(StockLevel.YELLOW, Thresholds.level(40.0, 100.0, 40, 20))
        assertEquals(StockLevel.YELLOW, Thresholds.level(21.0, 100.0, 40, 20))
        assertEquals(StockLevel.RED, Thresholds.level(20.0, 100.0, 40, 20))
        assertEquals(StockLevel.UNKNOWN, Thresholds.level(20.0, null, 40, 20))
    }

    @Test fun singleShoppingListBelow40() {
        val products = listOf(p(1, 1000.0), p(2, 10.0, MeasureUnit.PCS), p(3, 500.0), p(4, 100.0, untracked = true), p(5, 100.0))
        val totals = mapOf(1L to 390.0, 2L to 2.0, 3L to 450.0, 4L to 0.0, 5L to 0.0)
        val list = Thresholds.shoppingList(products, totals, 40, 20)
        assertEquals(listOf(5L, 2L, 1L), list.map { it.product.id })
        assertTrue(list.first { it.product.id == 2L }.urgent)
        assertFalse(list.first { it.product.id == 1L }.urgent)
        assertEquals(8.0, list.first { it.product.id == 2L }.toBuy, 0.0)
        assertEquals(610.0, list.first { it.product.id == 1L }.toBuy, 0.0)
    }

    @Test fun crossingFiresOnceAndRearmsAfterPurchase() {
        var state = AlertState()
        // день 1: продукт 1 опустился ниже 40%
        var e = ThresholdTracker.evaluate(state, buyIds = setOf(1), urgentIds = emptySet(), today = 1)
        assertEquals(setOf(1L), e.crossedBuy); assertFalse(e.notify); state = e.state
        e = ThresholdTracker.evaluate(state, setOf(1), emptySet(), 1)
        assertTrue(e.crossedBuy.isEmpty()); state = e.state
        // опустился до 20% — уведомление
        e = ThresholdTracker.evaluate(state, setOf(1), setOf(1), 1)
        assertTrue(e.notify); assertEquals(setOf(1L), e.crossedUrgent); state = e.state
        // повторная проверка в тот же день — тишина
        e = ThresholdTracker.evaluate(state, setOf(1), setOf(1), 1)
        assertFalse(e.notify); state = e.state
        // следующий день, продукт всё ещё на 20% — повторно не срабатывает
        e = ThresholdTracker.evaluate(state, setOf(1), setOf(1), 2)
        assertFalse(e.notify); state = e.state
        // покупка: продукт выше 40% — перевзвод
        e = ThresholdTracker.evaluate(state, emptySet(), emptySet(), 2); state = e.state
        assertTrue(state.alertedUrgent.isEmpty() && state.alertedBuy.isEmpty())
        // снова упал до 20% на следующий день — срабатывает снова
        e = ThresholdTracker.evaluate(state, setOf(1), setOf(1), 3)
        assertTrue(e.notify); assertEquals(setOf(1L), e.crossedBuy)
    }

    @Test fun atMostOneNotificationPerDay() {
        var e = ThresholdTracker.evaluate(AlertState(), setOf(1), setOf(1), 5)
        assertTrue(e.notify)
        e = ThresholdTracker.evaluate(e.state, setOf(1, 2), setOf(1, 2), 5)
        assertFalse(e.notify)
        // новый продукт 2 будет сообщён на следующий день
        e = ThresholdTracker.evaluate(e.state, setOf(1, 2), setOf(1, 2), 6)
        assertTrue(e.notify)
        assertEquals(setOf(2L), e.crossedUrgent)
    }
}
