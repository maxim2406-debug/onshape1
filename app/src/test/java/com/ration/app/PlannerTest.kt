package com.ration.app

import com.ration.app.data.seed.PrepKeys
import com.ration.app.domain.inventory.Consumption
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Tags
import com.ration.app.domain.plan.Availability
import com.ration.app.domain.plan.Planner
import com.ration.app.domain.plan.PlannerInput
import com.ration.app.domain.plan.WeekCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val today = 20_000L
    private val s = AppSettings()
    private val egg = TestData.product("egg")

    private fun input(type: DayType, road: Boolean = false, week: WeekCounters = WeekCounters(), freeLunch: Boolean = false,
                      recent: Map<Long, Map<SlotType, Long>> = emptyMap()): PlannerInput {
        val (stock, preps) = TestData.fullStock(today)
        return PlannerInput(
            day = today, dayType = type, road = road, settings = s, blocks = TestData.seed.blocks,
            availability = TestData.availability(today, stock, preps), recent = recent, week = week,
            freeLunchRequested = freeLunch,
            eggsOf = { Consumption.eggs(TestData.ingredients(it), egg.id, PrepKeys.EGGS) },
        )
    }

    @Test fun dayBChecksumInRange() {
        val r = Planner.plan(input(DayType.B))
        assertEquals(listOf(SlotType.BREAKFAST, SlotType.LUNCH, SlotType.DINNER, SlotType.EVENING), r.choices.map { it.spec.slot })
        assertTrue("violations: ${r.violations}", r.violations.isEmpty())
        assertTrue(r.checksum in 1550.0..1750.0)
        assertTrue(r.totalKcal in 1900.0..2100.0)
        assertTrue(r.totalProtein >= 120.0)
        val lunch = r.choices.first { it.spec.slot == SlotType.LUNCH }.block!!
        val dinner = r.choices.first { it.spec.slot == SlotType.DINNER }.block!!
        assertTrue(dinner.kcal <= lunch.kcal + 100)
        r.choices.filter { it.spec.slot.isMain }.forEach { assertTrue(it.block!!.protein >= 30) }
        r.choices.forEach { assertTrue(it.alternatives.size <= 3) }
        assertEquals(3, r.choices.first { it.spec.slot == SlotType.LUNCH }.alternatives.size)
    }

    @Test fun dayAChecksumInRange() {
        val r = Planner.plan(input(DayType.A))
        assertEquals(listOf(SlotType.LUNCH, SlotType.SNACK_1, SlotType.SNACK_2, SlotType.DINNER, SlotType.EVENING), r.choices.map { it.spec.slot })
        assertTrue("violations: ${r.violations}", r.violations.isEmpty())
        assertTrue(r.checksum in 1450.0..1650.0)
        assertTrue(r.totalKcal in 1900.0..2100.0)
        val evening = r.choices.first { it.spec.slot == SlotType.EVENING }.block
        if (evening != null) assertTrue(evening.code in setOf("Е3", "Е4"))
        r.choices.filter { it.spec.slot.isSnack }.forEach { val b = it.block!!; assertTrue(b.protein >= 12 || b.code == "П3") }
    }

    @Test fun roadDayIncludesBarInChecksum() {
        val r = Planner.plan(input(DayType.B, road = true))
        val bar = r.choices.first { it.spec.slot == SlotType.ROAD_BAR }.block
        assertNotNull(bar)
        assertTrue(Tags.BAR in bar!!.tags)
        val sum = r.choices.filter { it.spec.slot != SlotType.EVENING }.sumOf { it.block?.kcal ?: 0.0 }
        assertEquals(sum, r.checksum, 0.0)
    }

    @Test fun barsNeverPlannedInRegularSlots() {
        listOf(DayType.A, DayType.B).forEach { t ->
            val r = Planner.plan(input(t))
            r.choices.filter { it.spec.slot != SlotType.ROAD_BAR }.forEach { assertFalse(Tags.BAR in (it.block?.tags ?: emptyList())) }
        }
    }

    @Test fun redMeatLimit() {
        val r = Planner.plan(input(DayType.B, week = WeekCounters(redMeat = 2)))
        r.choices.forEach { assertFalse(Tags.RED_MEAT in (it.block?.tags ?: emptyList())) }
        assertTrue(Planner.candidates(Planner.slots(DayType.B, false, s).first { it.slot == SlotType.DINNER },
            input(DayType.B, week = WeekCounters(redMeat = 2)), 100).none { Tags.RED_MEAT in it.tags })
    }

    @Test fun freeLunchOnlyOnRequestAndOncePerWeek() {
        val normal = Planner.plan(input(DayType.B))
        assertFalse(normal.choices.any { it.block?.code == "F" })
        val requested = Planner.plan(input(DayType.B, freeLunch = true))
        assertEquals("F", requested.choices.first { it.spec.slot == SlotType.LUNCH }.block?.code)
        val secondThisWeek = Planner.plan(input(DayType.B, freeLunch = true, week = WeekCounters(freeLunch = 1)))
        assertFalse(secondThisWeek.choices.any { it.block?.code == "F" })
    }

    @Test fun fattyFishPreferredWhenBehind() {
        val r = Planner.plan(input(DayType.B, week = WeekCounters(fish = 1, fattyFish = 0)).copy(daysLeftInWeek = 2))
        val dinner = r.choices.first { it.spec.slot == SlotType.DINNER }.block!!
        assertTrue(Tags.FATTY_FISH in dinner.tags)
    }

    @Test fun sameBlockNotThreeDaysInARow() {
        val first = Planner.plan(input(DayType.B))
        val dinner = first.choices.first { it.spec.slot == SlotType.DINNER }.block!!
        val recent = mapOf(today - 1 to mapOf(SlotType.DINNER to dinner.id), today - 2 to mapOf(SlotType.DINNER to dinner.id))
        val r = Planner.plan(input(DayType.B, recent = recent))
        assertTrue(r.choices.none { it.block?.id == dinner.id })
    }

    @Test fun missingStockIsPenalizedAndMarked() {
        val base = input(DayType.B)
        val onlyStreet = base.copy(availability = { b -> Availability(inStock = !b.deductStock) })
        val r = Planner.plan(onlyStreet)
        assertEquals(true, r.choices.first { it.spec.slot == SlotType.LUNCH }.block?.tags?.contains(Tags.STREET))
        assertTrue(r.choices.filter { it.spec.slot == SlotType.DINNER }.all { it.needsPurchase })
    }

    @Test fun replanAfterMorningOvereating() {
        val inp = input(DayType.B)
        val plan = Planner.plan(inp)
        val remaining = plan.choices.filter { it.spec.slot != SlotType.BREAKFAST }
        val plannedBreakfast = plan.choices.first { it.spec.slot == SlotType.BREAKFAST }.block!!
        val before = Planner.replan(inp, remaining, Planner.Fixed(plannedBreakfast.kcal, plannedBreakfast.protein), plannedBreakfast.kcal)
        // Съеден тяжёлый завтрак: 1100 ккал вместо ~500.
        val after = Planner.replan(inp, remaining, Planner.Fixed(1100.0, 45.0), plannedBreakfast.kcal)
        val restBefore = before.choices.sumOf { (it.block?.kcal ?: 0.0) * it.multiplier }
        val restAfter = after.choices.sumOf { (it.block?.kcal ?: 0.0) * it.multiplier }
        assertTrue("остаток должен уменьшиться: $restBefore → $restAfter", restAfter < restBefore - 200)
        assertTrue(after.advice.any { it.contains("облегчены") })
        assertTrue(after.projectedKcal <= s.kcalMax + 1)
    }

    @Test fun replanSuggestsProteinWhenLow() {
        val inp = input(DayType.B)
        val plan = Planner.plan(inp)
        val r = Planner.replan(inp, emptyList(), Planner.Fixed(1500.0, 60.0), plan.totalKcal)
        assertTrue(r.advice.any { it.contains("белка") && it.contains("Е3") && it.contains("П5") })
    }
}
