package com.ration.app.domain.plan

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.MeatChoice
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.model.Tags

/** Недельные счётчики (раздел 6.5). */
data class WeekCounters(
    val fish: Int = 0,
    val fattyFish: Int = 0,
    val redMeat: Int = 0,
    val freeLunch: Int = 0,
    val proteinBars: Int = 0,
    val bars: Int = 0,
    val eggs: Double = 0.0,
    val alcohol: Int = 0,
) {
    operator fun plus(o: WeekCounters) = WeekCounters(
        fish + o.fish, fattyFish + o.fattyFish, redMeat + o.redMeat, freeLunch + o.freeLunch,
        proteinBars + o.proteinBars, bars + o.bars, eggs + o.eggs, alcohol + o.alcohol,
    )

    companion object {
        fun ofTags(tags: Collection<String>, meat: MeatChoice? = null, eggs: Double = 0.0) = WeekCounters(
            fish = if (Tags.FISH in tags || Tags.SEAFOOD in tags) 1 else 0,
            fattyFish = if (Tags.FATTY_FISH in tags) 1 else 0,
            redMeat = if (Tags.RED_MEAT in tags || meat == MeatChoice.RED) 1 else 0,
            freeLunch = if (Tags.FREE_LUNCH in tags) 1 else 0,
            proteinBars = if (Tags.PROTEIN_BAR in tags) 1 else 0,
            bars = if (Tags.BAR in tags) 1 else 0,
            eggs = eggs,
        )

        fun ofBlock(b: Block, eggs: Double = 0.0) = ofTags(b.tags, null, eggs)

        fun of(logs: List<MealLog>, quick: List<QuickLog>): WeekCounters =
            logs.fold(WeekCounters()) { acc, l -> acc + ofTags(l.tags, l.meatChoice, l.eggs) } +
                WeekCounters(alcohol = quick.count { it.type == QuickType.ALCOHOL })
    }
}

enum class CounterState { OK, WARN, BAD }

data class CounterRow(val title: String, val value: String, val target: String, val state: CounterState)

object WeekRules {
    fun rows(c: WeekCounters, s: AppSettings, daysLeft: Int): List<CounterRow> {
        val fishState = when {
            c.fish in s.fishTargetMin..s.fishTargetMax -> CounterState.OK
            c.fish > s.fishTargetMax -> CounterState.WARN
            c.fish + daysLeft >= s.fishTargetMin -> CounterState.WARN
            else -> CounterState.BAD
        }
        return listOf(
            CounterRow("Рыба и морепродукты", "${c.fish}", "цель ${s.fishTargetMin}–${s.fishTargetMax}", fishState),
            CounterRow("Жирная рыба", "${c.fattyFish}", "не меньше ${s.fattyFishMin}",
                when {
                    c.fattyFish >= s.fattyFishMin -> CounterState.OK
                    c.fattyFish + daysLeft >= s.fattyFishMin -> CounterState.WARN
                    else -> CounterState.BAD
                }),
            CounterRow("Красное мясо", "${c.redMeat}", "не больше ${s.redMeatMax}", limitState(c.redMeat, s.redMeatMax)),
            CounterRow("Свободный обед", "${c.freeLunch}", "не больше ${s.freeLunchMax}", limitState(c.freeLunch, s.freeLunchMax)),
            CounterRow("Протеиновые батончики", "${c.proteinBars}", "не больше ${s.maxProteinBarsPerWeek}", limitState(c.proteinBars, s.maxProteinBarsPerWeek)),
            CounterRow("Яйца целиком", com.ration.app.domain.TimeUtil.num(c.eggs), "не больше ${s.eggsMaxWeek}",
                limitState(Math.round(c.eggs).toInt(), s.eggsMaxWeek)),
            CounterRow("Алкоголь", "${c.alcohol}", "0", if (c.alcohol == 0) CounterState.OK else CounterState.BAD),
        )
    }

    private fun limitState(v: Int, max: Int) = when {
        v < max -> CounterState.OK
        v == max -> CounterState.WARN
        else -> CounterState.BAD
    }
}
