package com.ration.app

import com.ration.app.data.db.entity.MealLog
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.report.NutritionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NutritionStatsTest {
    private val s = AppSettings() // ккал 1900–2100 (ориентир 2000), белок 120–140
    private val monday = LocalDate.of(2026, 9, 28).toEpochDay()
    private var id = 1L
    private fun log(day: Long, kcal: Double, protein: Double, slot: SlotType? = SlotType.LUNCH) =
        MealLog(id++, day, day * 86_400_000L, slot, null, null, null, "x", 1.0, kcal, protein, MealSource.CUSTOM)

    @Test fun emptyPeriod() {
        val r = NutritionStats.summary(emptyList(), monday, monday + 6, s)
        assertEquals(7, r.days.size)
        assertEquals(0, r.loggedDays)
        assertNull(r.avgKcal)
        assertNull(r.proteinKcalPct)
        assertEquals(0, r.proteinStreak)
    }

    /** Дни без записей не занижают средние; диапазоны и красные дни считаются по настройкам. */
    @Test fun averagesSkipEmptyDays() {
        val logs = listOf(
            log(monday, 1200.0, 80.0, SlotType.LUNCH), log(monday, 800.0, 50.0, SlotType.DINNER), // 2000 / 130
            log(monday + 1, 2400.0, 100.0),                                                      // красный день, белок мало
            log(monday + 3, 1600.0, 125.0),
        )
        val r = NutritionStats.summary(logs, monday, monday + 6, s)
        assertEquals(3, r.loggedDays)
        assertEquals((2000.0 + 2400 + 1600) / 3, r.avgKcal!!, 1e-9)
        assertEquals((130.0 + 100 + 125) / 3, r.avgProtein!!, 1e-9)
        assertEquals(1, r.kcalInRange)
        assertEquals(1, r.redDays)
        assertEquals(2, r.proteinOk)
        assertEquals(1, r.proteinStreak) // последний день с записями в норме, предыдущий — нет
        assertEquals(monday + 3, r.minKcal!!.day)
        assertEquals(monday + 1, r.maxKcal!!.day)
        assertEquals(r.avgProtein!! * 4 / r.avgKcal!! * 100, r.proteinKcalPct!!, 1e-9)
    }

    @Test fun weeksAndSlots() {
        val logs = listOf(
            log(monday - 1, 1800.0, 120.0), // воскресенье прошлой недели
            log(monday, 1000.0, 60.0, SlotType.LUNCH), log(monday, 1000.0, 60.0, SlotType.DINNER),
            log(monday + 2, 2200.0, 140.0, SlotType.DINNER),
        )
        val r = NutritionStats.summary(logs, monday - 1, monday + 6, s)
        assertEquals(2, r.weeks.size)
        assertEquals(1800.0, r.weeks[0].avgKcal, 1e-9)
        assertEquals(2100.0, r.weeks[1].avgKcal, 1e-9)
        assertEquals(LocalDate.ofEpochDay(monday), r.weeks[1].weekStart)
        val shares = r.bySlot.associateBy { it.slot }
        assertEquals(100.0, r.bySlot.sumOf { it.sharePct }, 1e-9)
        assertTrue(shares.getValue(SlotType.DINNER).sharePct > shares.getValue(SlotType.LUNCH).sharePct)
        // средние по слоту — на день с записями
        assertEquals(3200.0 / 3, shares.getValue(SlotType.DINNER).avgKcal, 1e-9)
    }

    @Test fun periodBoundsRespected() {
        val logs = listOf(log(monday - 10, 5000.0, 10.0), log(monday, 2000.0, 130.0))
        val r = NutritionStats.summary(logs, monday, monday, s)
        assertEquals(1, r.loggedDays)
        assertEquals(2000.0, r.avgKcal!!, 1e-9)
    }
}
