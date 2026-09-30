package com.ration.app.data.repo

import androidx.room.withTransaction
import com.ration.app.data.calendar.CalendarReader
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.DayPlan
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.db.entity.Product
import com.ration.app.data.seed.PrepKeys
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.inventory.Consumption
import com.ration.app.domain.inventory.StockSnapshot
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.plan.Availability
import com.ration.app.domain.plan.DayTypeHint
import com.ration.app.domain.plan.Forecast
import com.ration.app.domain.plan.ForecastNotes
import com.ration.app.domain.plan.PlanResult
import com.ration.app.domain.plan.PlannedChoice
import com.ration.app.domain.plan.Planner
import com.ration.app.domain.plan.PlannerInput
import com.ration.app.domain.plan.SlotSpec
import com.ration.app.domain.plan.WeekCounters
import com.ration.app.domain.reminders.ReminderRules
import com.ration.app.notifications.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class CatalogData(
    val blocks: List<Block>,
    val ingredients: Map<Long, List<BlockIngredient>>,
    val products: Map<Long, Product>,
)

@Singleton
class PlanRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val calendar: CalendarReader,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    fun observePlan(day: Long): Flow<DayPlan?> = db.plans().observeDayPlan(day)
    fun observeSlots(day: Long): Flow<List<PlannedSlot>> = db.plans().observeSlots(day)

    fun today(): Long = LocalDate.now(clock).toEpochDay()

    suspend fun catalog(): CatalogData {
        val blocks = db.blocks().getAll()
        return CatalogData(blocks, db.blocks().allIngredients().groupBy { it.blockId }, db.products().getAll().associateBy { it.id })
    }

    /** Недельные счётчики с начала недели до дня (включительно или нет). */
    suspend fun weekCounters(day: Long, includeDay: Boolean): WeekCounters {
        val s = settings.current()
        val start = TimeUtil.weekStart(LocalDate.ofEpochDay(day), s.weekStart).toEpochDay()
        val end = if (includeDay) day else day - 1
        if (end < start) return WeekCounters()
        return WeekCounters.of(db.meals().range(start, end), db.quick().range(start, end))
    }

    suspend fun plannerInput(day: Long, plan: DayPlan, s: AppSettings = settings.current(), cat: CatalogData? = null): PlannerInput {
        val c = cat ?: catalog()
        val today = today()
        val stock = db.stock().getAll()
        val preps = db.preps().active()
        val soonProducts = stock.filter { it.qty > 0 && it.expiresDay != null && it.expiresDay <= today + 2 }.map { it.productId }.toSet()
        val soonPreps = preps.filter { it.expiresDay <= today + 2 }.map { it.outputKey }.toSet()
        val availablePreps = preps.map { it.outputKey }.toSet()
        val availability = mutableMapOf<Long, Availability>()
        for (b in c.blocks) {
            val ings = c.ingredients[b.id].orEmpty().filter { !it.toTaste }
            // Отдельный снимок на блок: canMake «списывает» внутри снимка.
            val inStock = !b.deductStock || Consumption.canMake(ings, 1.0, c.products, StockSnapshot(stock, preps, today))
            availability[b.id] = Availability(
                inStock = inStock,
                expiringSoon = ings.any { (it.productId != null && it.productId in soonProducts) || (it.prepKey != null && it.prepKey in soonPreps) },
                usesPrep = ings.any { it.prepKey != null && it.prepKey in availablePreps },
            )
        }
        val recentSlots = db.plans().slotsRange(day - 3, day - 1)
        val recent = recentSlots.filter { it.blockId != null && it.status != SlotStatus.SKIPPED }
            .groupBy { it.day }.mapValues { (_, l) -> l.associate { it.slot to it.blockId!! } }
        val eggId = c.products.values.firstOrNull { it.key == "egg" }?.id
        val weekStart = TimeUtil.weekStart(LocalDate.ofEpochDay(day), s.weekStart)
        val daysLeft = (weekStart.plusDays(6).toEpochDay() - day + 1).toInt()
        return PlannerInput(
            day = day, dayType = plan.dayType, road = plan.road, settings = s, blocks = c.blocks,
            availability = { availability[it.id] ?: Availability(false) },
            recent = recent,
            week = weekCounters(day, includeDay = false),
            freeLunchRequested = plan.freeLunchRequested,
            daysLeftInWeek = daysLeft,
            eggsOf = { b -> Consumption.eggs(c.ingredients[b.id].orEmpty(), eggId, PrepKeys.EGGS) },
        )
    }

    /** Тип дня по умолчанию: подсказка календаря (если включена и есть разрешение) или Б. */
    suspend fun suggestType(day: Long): Pair<DayType, String> {
        val s = settings.current()
        if (!s.calendarHint) return DayType.B to "Тип по умолчанию."
        val events = calendar.eventsOn(LocalDate.ofEpochDay(day), clock.zone)
            ?: return DayType.B to "Нет доступа к календарю — тип по умолчанию."
        return DayTypeHint.suggest(events, s)
    }

    private val lock = Mutex()

    suspend fun ensurePlan(day: Long): DayPlan = lock.withLock { ensurePlanInner(day) }

    private suspend fun ensurePlanInner(day: Long): DayPlan {
        db.plans().dayPlan(day)?.let { return it }
        val (type, reason) = suggestType(day)
        val plan = DayPlan(day = day, dayType = type, reason = reason)
        db.plans().upsertPlan(plan)
        rebuildInner(day)
        return plan
    }

    /** Пересобрать план дня: закрытые слоты сохраняются, запланированные подбираются заново. */
    suspend fun rebuild(day: Long): PlanResult? = lock.withLock { rebuildInner(day) }

    private suspend fun rebuildInner(day: Long): PlanResult? {
        val plan = db.plans().dayPlan(day) ?: return null
        val s = settings.current()
        val input = plannerInput(day, plan, s)
        val result = Planner.plan(input)
        writeChoices(day, result.choices, replaceSlotSet = true)
        scheduler.rescheduleAll()
        return result
    }

    private suspend fun writeChoices(day: Long, choices: List<PlannedChoice>, replaceSlotSet: Boolean) {
        db.withTransaction {
            val existing = db.plans().slots(day).associateBy { it.slot }
            val wanted = choices.associateBy { it.spec.slot }
            if (replaceSlotSet) {
                // слоты, которых нет в новом типе дня и которые ещё не закрыты, удаляются
                val keep = existing.values.filter { it.slot in wanted || it.status != SlotStatus.PLANNED }
                if (keep.size != existing.size) {
                    db.plans().deleteSlots(day)
                    db.plans().insertSlots(keep.map { it.copy(id = 0) })
                }
            }
            val current = db.plans().slots(day).associateBy { it.slot }
            val inserts = mutableListOf<PlannedSlot>()
            val updates = mutableListOf<PlannedSlot>()
            for ((slot, c) in wanted) {
                val ex = current[slot]
                if (ex == null) {
                    inserts += PlannedSlot(day = day, slot = slot, minuteOfDay = c.spec.minute, blockId = c.block?.id,
                        multiplier = c.multiplier, optional = c.spec.optional, needsPurchase = c.needsPurchase)
                } else if (ex.status == SlotStatus.PLANNED) {
                    var u = ex.copy(blockId = c.block?.id, multiplier = c.multiplier, optional = c.spec.optional, needsPurchase = c.needsPurchase)
                    if (u.minuteOfDay != c.spec.minute) u = ReminderRules.onTimeChanged(u, c.spec.minute)
                    updates += u
                }
            }
            if (inserts.isNotEmpty()) db.plans().insertSlots(inserts)
            if (updates.isNotEmpty()) db.plans().updateSlots(updates)
        }
    }

    /** Смена типа дня одним нажатием: блоки пересобираются, напоминания перестраиваются. */
    suspend fun setDayType(day: Long, type: DayType, reason: String = "Выбрано вручную.") = lock.withLock {
        val plan = ensurePlanInner(day)
        db.plans().upsertPlan(plan.copy(dayType = type, reason = reason))
        rebuildInner(day)
    }

    suspend fun setRoad(day: Long, road: Boolean) = lock.withLock {
        val plan = ensurePlanInner(day)
        db.plans().upsertPlan(plan.copy(road = road))
        rebuildInner(day)
    }

    suspend fun setFreeLunch(day: Long, requested: Boolean) = lock.withLock {
        val plan = ensurePlanInner(day)
        db.plans().upsertPlan(plan.copy(freeLunchRequested = requested))
        rebuildInner(day)
    }

    suspend fun confirm(day: Long) = lock.withLock {
        val plan = ensurePlanInner(day)
        db.plans().upsertPlan(plan.copy(confirmed = true))
        scheduler.rescheduleAll()
    }

    suspend fun setSlotBlock(slotId: Long, blockId: Long?) {
        val slot = db.plans().slot(slotId) ?: return
        db.plans().updateSlot(slot.copy(blockId = blockId, multiplier = 1.0))
        scheduler.rescheduleAll()
    }

    suspend fun setSlotTime(slotId: Long, minute: Int) {
        val slot = db.plans().slot(slotId) ?: return
        db.plans().updateSlot(ReminderRules.onTimeChanged(slot, minute))
        scheduler.rescheduleAll()
    }

    suspend fun slot(id: Long) = db.plans().slot(id)
    suspend fun slots(day: Long) = db.plans().slots(day)
    suspend fun plan(day: Long) = db.plans().dayPlan(day)
    suspend fun updateSlot(s: PlannedSlot) = db.plans().updateSlot(s)

    /** Пересчёт незакрытых слотов по фактически съеденному. Возвращает советы. */
    suspend fun replan(day: Long): List<String> = lock.withLock { replanInner(day) }

    private suspend fun replanInner(day: Long): List<String> {
        val plan = db.plans().dayPlan(day) ?: return emptyList()
        val s = settings.current()
        val cat = catalog()
        val input = plannerInput(day, plan, s, cat)
        val slots = db.plans().slots(day)
        val blocks = cat.blocks.associateBy { it.id }
        val logs = db.meals().forDay(day)
        val remaining = slots.filter { it.status == SlotStatus.PLANNED }.map {
            PlannedChoice(SlotSpec(it.slot, it.minuteOfDay, it.optional), it.blockId?.let(blocks::get), it.multiplier)
        }
        val closed = slots.filter { it.status != SlotStatus.PLANNED }
        val plannedSoFar = closed.sumOf { (it.blockId?.let(blocks::get)?.kcal ?: 0.0) * it.multiplier } +
            (if (closed.any { it.slot == SlotType.LUNCH }) s.fruitKcal else 0)
        val r = Planner.replan(input, remaining, Planner.Fixed(logs.sumOf { it.kcal }, logs.sumOf { it.protein }), plannedSoFar)
        writeChoices(day, r.choices, replaceSlotSet = false)
        scheduler.rescheduleAll()
        return r.advice
    }

    suspend fun alternatives(day: Long): Map<SlotType, List<Block>> {
        val plan = db.plans().dayPlan(day) ?: return emptyMap()
        val input = plannerInput(day, plan)
        val slots = db.plans().slots(day)
        return slots.associate { sl ->
            sl.slot to Planner.alternatives(SlotSpec(sl.slot, sl.minuteOfDay, sl.optional), input, input.blocks.firstOrNull { it.id == sl.blockId })
        }
    }

    suspend fun forecast(day: Long): ForecastNotes {
        val cat = catalog()
        val blocks = cat.blocks.associateBy { it.id }
        val choices = db.plans().slots(day).map {
            PlannedChoice(SlotSpec(it.slot, it.minuteOfDay, it.optional), it.blockId?.let(blocks::get), it.multiplier, it.needsPurchase)
        }
        return Forecast.notes(choices, cat.ingredients, cat.products)
    }
}
