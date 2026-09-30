package com.ration.app

import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.plan.DayTypeHint
import com.ration.app.domain.plan.WeekCounters
import com.ration.app.domain.report.HealthStats
import com.ration.app.domain.rules.DayRules
import com.ration.app.domain.substitution.NutrientTarget
import com.ration.app.domain.substitution.SubstitutionCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SubstitutionAndRulesTest {
    private val s = AppSettings()
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val date = LocalDate.of(2026, 10, 1)
    private fun ms(h: Int, m: Int = 0) = TimeUtil.toMillis(date.atTime(h, m), zone)
    private fun log(h: Int, kcal: Double, protein: Double, tags: List<String> = emptyList()) =
        MealLog(0, date.toEpochDay(), ms(h), null, name = "x", kcal = kcal, protein = protein, source = MealSource.CUSTOM, tags = tags)

    @Test fun substitutionMatchesProteinAndKcal() {
        // Заменяем 150 г курицы су-вид (262,5 ккал, 39 г белка) на индейку 105/18.
        val target = NutrientTarget(262.5, 39.0)
        val p = SubstitutionCalculator.propose(target, 105.0, 18.0)
        assertEquals(0.0, p.grams % 5.0, 0.0)
        assertTrue(p.grams in 180.0..240.0)
        assertFalse(p.lowProtein)
    }

    @Test fun lowProteinSubstitutionWarns() {
        val p = SubstitutionCalculator.propose(NutrientTarget(262.5, 39.0), 400.0, 5.0)
        assertTrue(p.lowProtein)
    }

    @Test fun per100FromPortion() {
        val (k, pr) = SubstitutionCalculator.per100(CustomFood(name = "b", kcalPer100 = 0.0, proteinPer100 = 0.0, portionGrams = 50.0, kcalPerPortion = 200.0, proteinPerPortion = 17.0))
        assertEquals(400.0, k, 1e-9); assertEquals(34.0, pr, 1e-9)
    }

    @Test fun warnings() {
        val logs = listOf(log(9, 900.0, 20.0), log(13, 600.0, 2.0, listOf("bar", "cereal_bar")),
            log(14, 200.0, 17.0, listOf("bar", "protein_bar")), log(16, 200.0, 17.0, listOf("bar", "protein_bar")),
            log(18, 300.0, 5.0))
        val quick = listOf(QuickLog(0, date.toEpochDay(), ms(15), QuickType.ESPRESSO), QuickLog(0, date.toEpochDay(), ms(20), QuickType.ALCOHOL))
        val ids = DayRules.warnings(date.atTime(19, 30), zone, logs, quick, s, WeekCounters.of(logs, quick)).map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("over_max", "early_over", "low_protein", "bars_day", "pbar_day", "cereal_bar", "coffee", "alcohol")))
        assertFalse("morning_over" in ids)
    }

    @Test fun morningOver60() {
        val ids = DayRules.warnings(date.atTime(11, 30), zone, listOf(log(10, 1250.0, 50.0)), emptyList(), s, WeekCounters()).map { it.id }
        assertTrue("morning_over" in ids)
        assertFalse("low_protein" in ids)
    }

    @Test fun weeklyLimitsOnLog() {
        val w = DayRules.onLogWarnings(WeekCounters(redMeat = 2, freeLunch = 1), WeekCounters(redMeat = 3, freeLunch = 2), s)
        assertEquals(2, w.size)
        assertTrue(DayRules.isRedDay(2201.0, s))
        assertFalse(DayRules.isRedDay(2200.0, s))
    }

    @Test fun calendarHint() {
        assertEquals(DayType.A, DayTypeHint.suggest(listOf(DayTypeHint.Event(9 * 60, 60)), s).first)
        assertEquals(DayType.B, DayTypeHint.suggest(listOf(DayTypeHint.Event(9 * 60, 30)), s).first)
        assertEquals(DayType.B, DayTypeHint.suggest(listOf(DayTypeHint.Event(11 * 60, 90)), s).first)
        assertEquals(DayType.B, DayTypeHint.suggest(emptyList(), s).first)
    }

    @Test fun weightRate() {
        val d = 20_000L
        val w = listOf(WeightLog(1, d - 21, 95.0), WeightLog(2, d - 14, 94.4), WeightLog(3, d - 7, 93.9), WeightLog(4, d, 93.3))
        assertEquals(-0.55, HealthStats.weightRatePerWeek(w, d, 15)!!, 0.01)
        assertEquals(-0.56, HealthStats.weightRatePerWeek(w, d, 29)!!, 0.02)
    }
}
