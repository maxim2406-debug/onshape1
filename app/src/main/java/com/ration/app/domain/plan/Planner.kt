package com.ration.app.domain.plan

import com.ration.app.data.db.entity.Block
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Tags
import kotlin.math.abs

/** Данные о наличии продуктов для блока. */
data class Availability(
    val inStock: Boolean,
    /** Использует продукт или заготовку со сроком в ближайшие 2 суток. */
    val expiringSoon: Boolean = false,
    /** Использует имеющуюся заготовку. */
    val usesPrep: Boolean = false,
)

data class SlotSpec(val slot: SlotType, val minute: Int, val optional: Boolean = false)

data class PlannerInput(
    val day: Long,
    val dayType: DayType,
    val road: Boolean,
    val settings: AppSettings,
    val blocks: List<Block>,
    val availability: (Block) -> Availability,
    /** Недавние назначения: день → слот → id блока (для разнообразия). */
    val recent: Map<Long, Map<SlotType, Long>> = emptyMap(),
    /** Недельные счётчики до этого дня (без него). */
    val week: WeekCounters = WeekCounters(),
    val freeLunchRequested: Boolean = false,
    /** Дней до конца недели, включая этот. */
    val daysLeftInWeek: Int = 7,
    /** Сколько яиц в блоке (для недельного лимита). */
    val eggsOf: (Block) -> Double = { 0.0 },
)

data class PlannedChoice(
    val spec: SlotSpec,
    val block: Block?,
    val multiplier: Double = 1.0,
    val needsPurchase: Boolean = false,
    val alternatives: List<Block> = emptyList(),
)

data class PlanResult(
    val choices: List<PlannedChoice>,
    /** Контрольная сумма 6.2 (без Е и фрукта). */
    val checksum: Double,
    val checksumRange: IntRange,
    val fruitKcal: Int,
    val totalKcal: Double,
    val totalProtein: Double,
    val violations: List<String>,
) {
    val totalWithoutExtras: Double get() = checksum
}

object Planner {
    const val TOP_N = 5
    private val LIGHT_SNACKS = setOf("П3", "П7")
    private val EVENING_FOR_A = setOf("Е3", "Е4")
    val PROTEIN_TOPUP = listOf("Е3", "Е4", "П1", "П5")

    fun slots(type: DayType, road: Boolean, s: AppSettings): List<SlotSpec> {
        val list = s.slotTimes(type).entries
            .map { (slot, minute) -> SlotSpec(slot, minute, optional = slot == SlotType.EVENING) }
            .toMutableList()
        if (road) list += SlotSpec(SlotType.ROAD_BAR, s.roadBarTime)
        return list.sortedBy { it.minute }
    }

    fun checksumRange(type: DayType, s: AppSettings): IntRange =
        if (type == DayType.A) s.checksumAMin..s.checksumAMax else s.checksumBMin..s.checksumBMax

    /** Проверка белка по правилу 6.3.1. */
    fun passesProteinRule(slot: SlotType, b: Block): Boolean = when {
        slot.isMain -> b.protein >= 30.0
        slot == SlotType.ROAD_BAR -> true
        slot.isSnack -> b.protein >= 12.0 || b.code in LIGHT_SNACKS || Tags.LIGHT in b.tags
        else -> true
    }

    /** Блок подходит слоту по типу и правилам (без оценки). */
    fun eligible(slot: SlotType, b: Block, input: PlannerInput): Boolean {
        if (!b.active || b.kind !in slot.kinds) return false
        val isBar = Tags.BAR in b.tags
        if (slot == SlotType.ROAD_BAR) return isBar
        if (isBar) return false
        if (Tags.FREE_LUNCH in b.tags) {
            if (!input.freeLunchRequested || input.week.freeLunch >= input.settings.freeLunchMax) return false
        }
        if (slot == SlotType.EVENING && input.dayType == DayType.A && b.code !in EVENING_FOR_A) return false
        if (Tags.RED_MEAT in b.tags && input.week.redMeat >= input.settings.redMeatMax) return false
        if (!passesProteinRule(slot, b)) return false
        // Не чаще 2 дней подряд в любом слоте (правило 6.3.5).
        val y1 = input.recent[input.day - 1]?.values.orEmpty()
        val y2 = input.recent[input.day - 2]?.values.orEmpty()
        if (b.id in y1 && b.id in y2) return false
        return true
    }

    fun score(slot: SlotType, b: Block, input: PlannerInput): Double {
        val a = input.availability(b)
        var sc = b.protein
        if (!a.inStock) sc -= 40
        if (a.expiringSoon) sc += 15
        if (a.usesPrep) sc += 10
        // Разнообразие: тот же блок в том же слоте не чаще раза в 3 дня.
        for (d in 1..2) if (input.recent[input.day - d]?.get(slot) == b.id) sc -= 25
        val w = input.week
        val s = input.settings
        val tags = b.tags
        val isFish = Tags.FISH in tags || Tags.SEAFOOD in tags
        if (isFish) {
            when {
                w.fish >= s.fishTargetMax -> sc -= 10
                w.fish < s.fishTargetMin -> sc += 8 + if (s.fishTargetMin - w.fish >= input.daysLeftInWeek) 10 else 0
            }
        }
        if (Tags.FATTY_FISH in tags && w.fattyFish < s.fattyFishMin) {
            sc += 10 + if (s.fattyFishMin - w.fattyFish >= input.daysLeftInWeek) 15 else 0
        }
        if (Tags.RED_MEAT in tags) sc -= 5
        if (Tags.STREET in tags && b.kind == MealKind.LUNCH_STREET) sc -= 6
        if (Tags.FREE_LUNCH in tags && input.freeLunchRequested) sc += 100
        if (input.eggsOf(b) > 0 && w.eggs + input.eggsOf(b) > s.eggsMaxWeek) sc -= 30
        return sc
    }

    fun candidates(spec: SlotSpec, input: PlannerInput, n: Int = TOP_N): List<Block> =
        input.blocks.filter { eligible(spec.slot, it, input) }
            .sortedWith(compareByDescending<Block> { score(spec.slot, it, input) }.thenBy { it.code })
            .take(n)

    private data class Eval(val violations: List<String>, val objective: Double, val kcal: Double, val protein: Double)

    private fun evaluate(
        specs: List<SlotSpec>, picks: List<Block?>, input: PlannerInput, fixedKcal: Double = 0.0, fixedProtein: Double = 0.0,
        checkChecksum: Boolean = true, multipliers: List<Double>? = null,
    ): Eval {
        val s = input.settings
        val v = mutableListOf<String>()
        var checksum = 0.0
        var kcal = fixedKcal
        var protein = fixedProtein
        var scoreSum = 0.0
        var lunch: Double? = null
        var dinner: Double? = null
        var red = input.week.redMeat
        var eggs = input.week.eggs
        var hasLunch = false
        picks.forEachIndexed { i, b ->
            val slot = specs[i].slot
            if (slot == SlotType.LUNCH) hasLunch = true
            if (b == null) return@forEachIndexed
            val m = multipliers?.get(i) ?: 1.0
            val k = b.kcal * m
            if (slot != SlotType.EVENING) checksum += k
            kcal += k
            protein += b.protein * m
            scoreSum += score(slot, b, input)
            if (slot == SlotType.LUNCH) lunch = k
            if (slot == SlotType.DINNER) dinner = k
            if (Tags.RED_MEAT in b.tags) red++
            eggs += input.eggsOf(b) * m
        }
        if (hasLunch) kcal += s.fruitKcal
        val distinct = picks.filterNotNull()
        if (distinct.size != distinct.map { it.id }.toSet().size) v += "Один блок дважды за день"
        if (checkChecksum) {
            val range = checksumRange(input.dayType, s)
            if (checksum < range.first || checksum > range.last) v += "Контрольная сумма ${checksum.toInt()} вне ${range.first}–${range.last}"
        }
        val l = lunch; val d = dinner
        if (l != null && d != null && d > l + 100) v += "Ужин тяжелее обеда больше чем на 100 ккал"
        if (red > s.redMeatMax) v += "Красное мясо больше ${s.redMeatMax} раз в неделю"
        if (kcal > s.kcalMax) v += "Итог дня ${kcal.toInt()} выше ${s.kcalMax} ккал"
        if (kcal < s.kcalMin) v += "Итог дня ${kcal.toInt()} ниже ${s.kcalMin} ккал"
        if (eggs > s.eggsMaxWeek) v += "Яиц за неделю больше ${s.eggsMaxWeek}"
        var obj = scoreSum - abs(kcal - s.kcalTarget) / 5.0
        if (protein < s.proteinMin) obj -= (s.proteinMin - protein) * 3
        if (protein > s.proteinMax) obj -= (protein - s.proteinMax) * 0.5
        return Eval(v, obj, kcal, protein)
    }

    /** Перебор сочетаний (декартово произведение кандидатов). */
    private fun <T> forEachCombo(options: List<List<T>>, block: (List<T>) -> Unit) {
        if (options.isEmpty()) { block(emptyList()); return }
        val idx = IntArray(options.size)
        if (options.any { it.isEmpty() }) return
        while (true) {
            block(options.indices.map { options[it][idx[it]] })
            var k = options.size - 1
            while (k >= 0) {
                idx[k]++
                if (idx[k] < options[k].size) break
                idx[k] = 0
                k--
            }
            if (k < 0) return
        }
    }

    fun plan(input: PlannerInput): PlanResult {
        val s = input.settings
        val specs = slots(input.dayType, input.road, s)
        val cands = specs.map { spec ->
            val c: List<Block?> = candidates(spec, input)
            if (spec.optional) c + listOf(null) else c.ifEmpty { listOf(null) }
        }
        var best: Pair<List<Block?>, Eval>? = null
        forEachCombo(cands) { picks ->
            val e = evaluate(specs, picks, input)
            val b = best
            if (b == null || better(e, b.second)) best = picks to e
        }
        val (picks, eval) = best ?: (specs.map { null } to Eval(listOf("Нет подходящих блоков"), 0.0, 0.0, 0.0))
        val choices = specs.mapIndexed { i, spec ->
            val b = picks[i]
            PlannedChoice(
                spec, b, 1.0,
                needsPurchase = b != null && !input.availability(b).inStock,
                alternatives = alternatives(spec, input, b),
            )
        }
        val checksum = choices.filter { it.spec.slot != SlotType.EVENING }.sumOf { it.block?.kcal ?: 0.0 }
        return PlanResult(
            choices, checksum, checksumRange(input.dayType, s),
            if (specs.any { it.slot == SlotType.LUNCH }) s.fruitKcal else 0,
            eval.kcal, eval.protein, eval.violations,
        )
    }

    fun alternatives(spec: SlotSpec, input: PlannerInput, chosen: Block?, n: Int = 3): List<Block> =
        input.blocks.filter { eligible(spec.slot, it, input) && it.id != chosen?.id }
            .sortedWith(compareByDescending<Block> { score(spec.slot, it, input) }.thenBy { it.code })
            .take(n)

    private fun better(a: Eval, b: Eval): Boolean =
        if (a.violations.size != b.violations.size) a.violations.size < b.violations.size else a.objective > b.objective

    /** Уже съеденное / закрытое за день. */
    data class Fixed(val kcal: Double, val protein: Double)

    data class ReplanResult(
        val choices: List<PlannedChoice>,
        val projectedKcal: Double,
        val projectedProtein: Double,
        val advice: List<String>,
    )

    /**
     * Пересчёт оставшихся слотов после фактически съеденного (раздел 4).
     * [remaining] — ещё не закрытые слоты с текущим назначением; [consumed] — всё записанное за день.
     */
    fun replan(input: PlannerInput, remaining: List<PlannedChoice>, consumed: Fixed, plannedSoFarKcal: Double): ReplanResult {
        val s = input.settings
        val advice = mutableListOf<String>()
        if (remaining.isEmpty()) {
            if (consumed.protein < s.proteinMin) advice += proteinAdvice(input)
            return ReplanResult(emptyList(), consumed.kcal, consumed.protein, advice)
        }
        val specs = remaining.map { it.spec }
        val options = remaining.map { r ->
            val base = candidates(r.spec, input).toMutableList()
            r.block?.let { cur -> if (base.none { it.id == cur.id } && eligible(r.spec.slot, cur, input)) base.add(0, cur) }
            val muls = if (r.spec.slot.isMain) listOf(1.0, 0.75, 0.5) else listOf(1.0)
            val opts = mutableListOf<Pair<Block?, Double>>()
            for (b in base) for (m in muls) opts += b to m
            if (r.spec.optional) opts += null to 0.0
            opts.ifEmpty { listOf(r.block to r.multiplier) }
        }
        val fruitLeft = remaining.any { it.spec.slot == SlotType.LUNCH }
        var best: Triple<List<Pair<Block?, Double>>, Eval, Double>? = null
        forEachCombo(options) { picks ->
            val e = evaluate(
                specs, picks.map { it.first }, input,
                fixedKcal = consumed.kcal - if (fruitLeft) 0.0 else 0.0,
                fixedProtein = consumed.protein, checkChecksum = false, multipliers = picks.map { it.second },
            )
            // Предпочитаем полные порции и прежние блоки.
            var obj = e.objective
            picks.forEachIndexed { i, (b, m) ->
                if (b != null && b.id == remaining[i].block?.id) obj += 8
                if (m < 1.0) obj -= (1.0 - m) * 20
                if (b == null) obj -= 5
            }
            val hardOver = e.kcal > s.kcalMax
            val adj = obj - if (hardOver) 1000 else 0
            val cur = best
            if (cur == null || adj > cur.third) best = Triple(picks, e, adj)
        }
        val (picks, eval, _) = best!!
        val choices = remaining.mapIndexed { i, r ->
            val (b, m) = picks[i]
            PlannedChoice(r.spec, b, if (b == null) 1.0 else m,
                needsPurchase = b != null && !input.availability(b).inStock,
                alternatives = alternatives(r.spec, input, b))
        }
        if (consumed.kcal > plannedSoFarKcal + 100) {
            advice += "Съедено больше плана: следующие приёмы облегчены."
            if (choices.any { it.multiplier < 1.0 }) advice += "Порции уменьшены."
            if (choices.any { it.spec.optional && it.block == null }) advice += "Вечерний блок лучше пропустить."
        }
        if (eval.kcal > s.kcalMax) advice += "Даже с облегчёнными приёмами день выше ${s.kcalMax} ккал."
        if (eval.protein < s.proteinMin) advice += proteinAdvice(input)
        return ReplanResult(choices, eval.kcal, eval.protein, advice)
    }

    private fun proteinAdvice(input: PlannerInput): String {
        val names = PROTEIN_TOPUP.mapNotNull { code -> input.blocks.firstOrNull { it.code == code } }
            .joinToString(", ") { "${it.code} ${it.name}" }
        return "Не хватает белка: добрать $names."
    }
}

/** Подсказка типа дня по календарю: событие длиннее N минут, начинающееся до порога → А. */
object DayTypeHint {
    data class Event(val startMinute: Int, val durationMin: Int)

    fun suggest(events: List<Event>, s: AppSettings): Pair<DayType, String> {
        val hit = events.filter { it.startMinute < s.calendarCutoff && it.durationMin > s.calendarMinEventMin }
            .minByOrNull { it.startMinute }
        return if (hit != null) {
            DayType.A to "В календаре событие в ${com.ration.app.domain.TimeUtil.hm(hit.startMinute)} " +
                "(${hit.durationMin} мин) — утро занято, завтрак пропускается."
        } else {
            DayType.B to "До ${com.ration.app.domain.TimeUtil.hm(s.calendarCutoff)} встреч длиннее ${s.calendarMinEventMin} минут нет — завтрак дома."
        }
    }
}
