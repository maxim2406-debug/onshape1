package com.ration.app

import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.LabResult
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.data.db.entity.Workout
import com.ration.app.domain.health.Condition
import com.ration.app.domain.health.Energy
import com.ration.app.domain.health.FormAnalysis
import com.ration.app.domain.health.FormTargets
import com.ration.app.domain.health.HealthRules
import com.ration.app.domain.health.LabStatus
import com.ration.app.domain.health.Labs
import com.ration.app.domain.health.Profile
import com.ration.app.domain.health.ProfileCheck
import com.ration.app.domain.health.Trends
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.KcalSource
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.Sex
import com.ration.app.domain.model.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Раздел 20: формулы, баланс, прогноз, рекомендации, тренды, анализы (тесты 20.9). */
class Section20Test {
    private val s = AppSettings()
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val today = LocalDate.of(2026, 10, 8).toEpochDay()
    private val rules: HealthRules by lazy {
        HealthRules.parse(listOf(File("src/main/assets/health_rules.json"), File("app/src/main/assets/health_rules.json")).first { it.exists() }.readText())
    }
    private var id = 1L
    private fun meal(day: Long, kcal: Double, protein: Double = 130.0) =
        MealLog(id++, day, day * 86_400_000L, null, null, null, null, "x", 1.0, kcal, protein, MealSource.CUSTOM)
    private fun workout(day: Long, net: Double, min: Double = 40.0) =
        Workout(id++, WorkoutType.WALK, day, day * 86_400_000L, min, kcalSource = KcalSource.CALC, kcalEntered = net, kcalNet = net)
    private fun ms(day: Long, hour: Int = 8) = LocalDate.ofEpochDay(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun acsmWalk() {
        val e = Energy.walk(5.0, 5.0, 95.0, 60.0)
        assertEquals(451.3, e.kcalNet, 0.5)
        assertEquals(7.52, e.kcalNet / 60, 0.01)
        assertTrue(e.warnings.isEmpty())
        assertTrue(Energy.walk(7.0, 0.0, 95.0, 30.0).warnings.any { it.contains("бег") })
    }

    @Test fun swim() {
        val e = Energy.swim(1500.0, 40.0, 95.0)!!
        assertEquals(160.0, Energy.swimPaceSec(1500.0, 40.0)!!, 1e-9) // 2:40 на 100 м
        assertEquals(8.3, Energy.swimMet(160.0), 1e-9)
        assertEquals(462.3, e.kcalNet, 0.5)
        assertEquals(5.8, Energy.swimMet(190.0), 1e-9)
        assertEquals(9.8, Energy.swimMet(110.0), 1e-9)
    }

    @Test fun bmr() {
        assertEquals(1868.75, Energy.bmr(Sex.M, 95.0, 179.0, 41), 1e-9)
        assertEquals(1868.75 - 166, Energy.bmr(Sex.F, 95.0, 179.0, 41), 1e-9)
    }

    @Test fun totalToNet() {
        // 60 минут, BMR 1868,75 → 77,86 ккал покоя; 500 общих → 422,1 чистых
        assertEquals(500 - 1868.75 / 24, Energy.netFromTotal(500.0, 1868.75, 60.0), 1e-9)
        assertEquals(0.0, Energy.netFromTotal(10.0, 1868.75, 60.0), 1e-9)
    }

    @Test fun walkSolve() {
        val p = Energy.solveWalk(30.0, 5.0, null)!!
        assertEquals(2500.0, p.distanceM, 1e-9)
        assertEquals(6.0, Energy.solveWalk(null, null, null)?.speedKmh ?: 6.0, 1e-9)
        assertEquals(5.0, Energy.solveWalk(30.0, null, 2500.0)!!.speedKmh, 1e-9)
        assertEquals(30.0, Energy.solveWalk(null, 5.0, 2500.0)!!.minutes, 1e-9)
        assertNull(Energy.solveWalk(30.0, null, null))
    }

    @Test fun forecastFromBalance() {
        assertEquals(-0.4545, FormAnalysis.forecastKgWeek(-500.0), 1e-3)
    }

    /** Дни без еды не попадают в средний баланс; TDEE = BMR·PAL + тренировки. */
    @Test fun averageSkipsDaysWithoutFood() {
        val weights = listOf(WeightLog(1, today - 20, 95.0))
        val logs = listOf(meal(today - 2, 2000.0), meal(today, 1800.0))
        val r = FormAnalysis.report(today - 6, today, s, weights, logs, listOf(workout(today, 300.0)), emptyList())
        assertEquals(7, r.days.size)
        assertEquals(2, r.foodDays)
        val base = 1868.75 * 1.3
        assertEquals(((2000 - base) + (1800 - base - 300)) / 2, r.avgBalance!!, 1e-6)
        assertEquals(base + 300, r.days.last().tdee, 1e-9)
        assertEquals(1868.75 * 0.3, r.days.last().household, 1e-6)
    }

    @Test fun missingProfileData() {
        val (p, missing) = ProfileCheck.profile(s, emptyList(), today)
        assertNull(p)
        assertTrue(missing.any { it.startsWith("вес") })
        assertTrue(FormAnalysis.report(today - 6, today, s, emptyList(), listOf(meal(today, 2000.0)), emptyList(), emptyList()).days.isEmpty())
    }

    /** Маркер «По форме»: не ниже 1500 и BMR; 50% тренировки; белок 1,6–2,0 г/кг целевого веса, шаг 5 г. */
    @Test fun formTarget() {
        val p = Profile(1868.75, 1.3, 95.0, 80.0)
        val t = FormTargets.today(p, s, 0.0)
        assertEquals(1868.75 * 1.3 - 550, t.kcal, 1e-6)
        assertEquals(130, t.proteinLow); assertEquals(160, t.proteinHigh)
        assertEquals(1868.75 * 1.3 + 200 - 550, FormTargets.today(p, s, 400.0).kcal, 1e-6)
        val low = FormTargets.today(Profile(1300.0, 1.2, 60.0, 55.0), s.copy(lossPaceKgPerWeek = 1.0), 0.0)
        assertEquals(1500.0, low.kcal, 1e-9)
        val bmrFloor = FormTargets.today(Profile(1700.0, 1.2, 90.0, 80.0), s.copy(lossPaceKgPerWeek = 1.0), 0.0)
        assertEquals(1700.0, bmrFloor.kcal, 1e-9)
        assertEquals(2000, s.kcalTarget) // цель дня сама не меняется
    }

    @Test fun adviceRules() {
        val weights = (0..13).map { WeightLog(100L + it, today - 13 + it, 95.0 - it * 0.2) } // −1,4 кг/нед (>1%)
        val logs = (0..6).map { meal(today - it, 1400.0, 80.0) }
        val r = FormAnalysis.report(today - 6, today, s, weights, logs, emptyList(), emptyList())
        val texts = r.advice.map { it.text }
        assertTrue(texts.any { it.startsWith("Слишком быстро") })
        assertTrue(texts.any { it.contains("белок") })
        assertTrue(texts.any { it.contains("воды") })
        assertTrue(texts.any { it.contains("тренировку") })
        assertTrue(r.advice.all { it.why.isNotBlank() })
    }

    @Test fun calibrationSuggestsPal() {
        // 14 дней еды по 2500 ккал, вес стоит → наблюдаемый расход ≈ 2500, расчёт 2429 (<10%) → без предложения
        val weights = (0..6).map { WeightLog(200L + it, today - 13 + it * 2, 95.0) }
        val logs = (0..13).map { meal(today - it, 2500.0) }
        val c = FormAnalysis.report(today - 13, today, s, weights, logs, emptyList(), emptyList()).calibration!!
        assertEquals(2500.0, c.observedTdee, 1e-6)
        assertNull(c.suggestedPal)
        val logs2 = (0..13).map { meal(today - it, 3000.0) }
        val c2 = FormAnalysis.report(today - 13, today, s, weights, logs2, emptyList(), emptyList()).calibration!!
        assertEquals(1.5, c2.suggestedPal!!, 1e-9) // 3000/1868,75 = 1,6 → ограничено 1,5
        // мало дней с едой — калибровки нет
        assertNull(FormAnalysis.report(today - 13, today, s, weights, logs.take(5), emptyList(), emptyList()).calibration)
    }

    // ---- 20.6 ----

    private fun bpSeries(values: List<Pair<Int, Int>>): List<BpLog> {
        val win = Trends.windows(today)
        return win.flatMapIndexed { i, r -> (0 until 3).map { k -> BpLog(id++, ms(r.first + k), values[i].first, values[i].second) } }
    }

    @Test fun threeWorseWindowsWarn() {
        val bp = bpSeries(listOf(125 to 80, 130 to 83, 136 to 86))
        val c = Trends.check(today, zone, rules, true, emptyList(), bp, emptyList(), emptyList(), emptyList())
        assertTrue(c.warnings.any { it.key == "bp" })
    }

    @Test fun twoWorseOneBetterNoWarning() {
        val bp = bpSeries(listOf(125 to 80, 130 to 83, 128 to 81))
        assertFalse(Trends.check(today, zone, rules, true, emptyList(), bp, emptyList(), emptyList(), emptyList()).warnings.any { it.key == "bp" })
        // ухудшения в пределах шума — тоже нет
        val noise = bpSeries(listOf(125 to 80, 127 to 81, 129 to 82))
        assertFalse(Trends.check(today, zone, rules, true, emptyList(), noise, emptyList(), emptyList(), emptyList()).warnings.any { it.key == "bp" })
    }

    @Test fun lowDataNoWarning() {
        val bp = bpSeries(listOf(125 to 80, 130 to 83, 136 to 86)).drop(1) // в W0 только 2 измерения
        val c = Trends.check(today, zone, rules, true, emptyList(), bp, emptyList(), emptyList(), emptyList())
        assertFalse(c.warnings.any { it.key == "bp" })
        assertTrue("давление" in c.lowData)
    }

    @Test fun weightRiseWarns() {
        val win = Trends.windows(today)
        val w = win.flatMapIndexed { i, r -> (0 until 3).map { k -> WeightLog(id++, r.first + k, 95.0 + i * 0.5) } }
        assertTrue(Trends.check(today, zone, rules, true, w, emptyList(), emptyList(), emptyList(), emptyList()).warnings.any { it.key == "weight" })
    }

    @Test fun urgentImmediately() {
        val now = ms(today, 9)
        val u = Trends.urgent(rules, listOf(BpLog(1, now - 60_000, 182, 100)), emptyList(), now)
        assertEquals(1, u.size)
        assertTrue(u.single().advice.contains("101"))
        val lab = LabResult(5, today, "Калий", 6.5, "ммоль/л")
        assertTrue(Trends.urgent(rules, emptyList(), listOf(lab), now).any { it.key.startsWith("urgent:lab") })
        assertTrue(Trends.urgent(rules, listOf(BpLog(1, now, 150, 95)), emptyList(), now).isEmpty())
    }

    // ---- анализы ----

    @Test fun labImport() {
        val existing = listOf(LabResult(1, LocalDate.of(2026, 9, 1).toEpochDay(), "АЛТ", 52.0, "Ед/л"))
        val text = """
            АЛТ | 52 | Ед/л | 0 | 41 | 01.09.2026
            ALT | 48 | Ед/л | 0 | 41 | 01.10.2026
            АЛТ | 48 | Ед/л | 0 | 41 | 01.10.2026
            Глюкоза | ? | мг/дл | 70 | 100 | 01.10.2026
            ЛПНП | 120 | мг/дл | | | 2026-10-01
            Мой показатель | 3,5 | ед | | | 01.10.2026
        """.trimIndent()
        val rows = Labs.parse(text, rules, existing)
        assertEquals(6, rows.size)
        assertTrue(rows[0].duplicate)             // уже есть в базе
        assertEquals("АЛТ", rows[1].indicator)    // алиас ALT → АЛТ
        assertFalse(rows[1].duplicate)
        assertTrue(rows[2].duplicate)             // повтор внутри файла
        assertNotNull(rows[3].error)              // «?»
        assertNotNull(rows[4].error)              // неверная дата
        assertNull(rows[5].error); assertFalse(rows[5].known)
        assertEquals(2, Labs.toResults(rows).size)
    }

    @Test fun labStatusAndTrend() {
        val r1 = LabResult(1, today - 60, "АЛТ", 30.0)
        val r2 = LabResult(2, today, "АЛТ", 55.0)
        assertEquals(LabStatus.HIGH, Labs.eval(r2, rules).status)
        assertFalse(Labs.eval(r2, rules).fromBlank)
        assertEquals(LabStatus.NORMAL, Labs.eval(r2.copy(refHigh = 60.0), rules).status)
        assertEquals("↑", Labs.trend(listOf(r1, r2), r2)!!.arrow)
        assertEquals(LabStatus.LOW, Labs.eval(LabResult(3, today, "ЛПВП", 35.0), rules).status)
    }

    @Test fun conditionReport() {
        val bp = (0..6).flatMap { d -> listOf(BpLog(id++, ms(today - d), 145, 92), BpLog(id++, ms(today - d, 20), 142, 91)) }
        val labs = listOf(LabResult(1, today - 10, "АЛТ", 60.0, "Ед/л"))
        val r = Condition.report(today, zone, s, rules, emptyList(), bp, emptyList(), emptyList(), emptyList(), labs)
        assertEquals(listOf("Питание", "Вес", "Давление", "Активность", "Анализы"), r.areas.map { it.title })
        assertEquals(com.ration.app.domain.health.AreaColor.RED, r.areas[2].color)
        assertTrue(r.advice.size <= 5)
        assertTrue(r.advice.any { it.text.contains("врачом") })
        assertTrue(r.advice.any { it.text.contains("умеренная нагрузка") }) // давление повышено → только умеренно
        // без лекарств и дозировок
        assertTrue(r.advice.none { a -> listOf("мг", "таблет", "препарат", "доз").any { a.text.contains(it, ignoreCase = true) } })
    }

    @Test fun rulesFileValid() {
        assertEquals(18, rules.indicators.size)
        assertTrue(rules.indicators.all { it.worse in setOf("up", "down", "range") })
        assertEquals(180, rules.bp.urgentSys)
    }
}
