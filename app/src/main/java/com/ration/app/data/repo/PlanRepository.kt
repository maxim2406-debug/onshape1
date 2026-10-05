package com.ration.app.data.repo

import androidx.room.withTransaction
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.DayPlan
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.db.entity.SlotState
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.day.SlotChanges
import com.ration.app.domain.day.SlotStates
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.MealSlotStatus
import com.ration.app.domain.model.SlotSchedule
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType
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

/**
 * День из шести приёмов (19.1) с одним расписанием (19.8).
 * slot_state — статусы приёмов на «Сегодня»; planned_slot — время слота и напоминания «нет записи»,
 * его статус зеркалит slot_state (EMPTY → запланирован, SKIPPED → пропущен, LOGGED → съеден).
 * day_plan хранит только флаг «план на завтра подтверждён»; колонка dayType больше не читается
 * (для новых строк пишется заглушка Б, потому что колонка NOT NULL).
 */
@Singleton
class PlanRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    fun observePlan(day: Long): Flow<DayPlan?> = db.plans().observeDayPlan(day)
    fun observeSlots(day: Long): Flow<List<PlannedSlot>> = db.plans().observeSlots(day)
    fun observeStates(day: Long): Flow<List<SlotState>> = db.slotStates().observeDay(day)

    fun today(): Long = LocalDate.now(clock).toEpochDay()

    /** Недельные счётчики с начала недели до дня (включительно или нет). Пропуск слотов их не меняет. */
    suspend fun weekCounters(day: Long, includeDay: Boolean): WeekCounters {
        val s = settings.current()
        val start = TimeUtil.weekStart(LocalDate.ofEpochDay(day), s.weekStart).toEpochDay()
        val end = if (includeDay) day else day - 1
        if (end < start) return WeekCounters()
        return WeekCounters.of(db.meals().range(start, end), db.quick().range(start, end))
    }

    private val lock = Mutex()

    /**
     * Перенос расписания типов дня в одно расписание (19.8): один раз, по типу дня, выбранному на сегодня.
     * Если выбора нет — расписание Б (значение по умолчанию прежнего кода).
     */
    suspend fun settingsWithSchedule(): AppSettings {
        val s = settings.current()
        if (s.slotTimes != null) return s
        val todayType: DayType? = db.plans().dayPlan(today())?.dayType
        settings.update { SlotSchedule.migrate(it, todayType) }
        return settings.current()
    }

    suspend fun ensurePlan(day: Long): DayPlan = lock.withLock { ensurePlanInner(day) }

    private suspend fun ensurePlanInner(day: Long): DayPlan {
        val plan = db.plans().dayPlan(day) ?: DayPlan(day = day, dayType = DayType.B).also { db.plans().upsertPlan(it) }
        syncSlots(day, settingsWithSchedule())
        return plan
    }

    /**
     * Шесть строк расписания дня: время из настроек, без назначенных блоков (готовых сетов нет, 19.2),
     * статус — зеркало slot_state. Изменение времени сбрасывает напоминания слота.
     */
    private suspend fun syncSlots(day: Long, s: AppSettings) {
        val times = s.times()
        val states = db.slotStates().forDay(day).associateBy { it.slot }
        db.withTransaction {
            val existing = db.plans().slots(day).associateBy { it.slot }
            val inserts = mutableListOf<PlannedSlot>()
            val updates = mutableListOf<PlannedSlot>()
            for (slot in SlotType.entries) {
                val minute = times[slot] ?: continue
                val status = SlotStates.reminderStatus(SlotStates.status(states, slot))
                val optional = slot.isSnack && !s.snackReminders
                val ex = existing[slot]
                if (ex == null) {
                    inserts += PlannedSlot(day = day, slot = slot, minuteOfDay = minute, blockId = null, status = status, optional = optional)
                } else {
                    var u = ex.copy(status = status, optional = optional, blockId = null, multiplier = 1.0, needsPurchase = false)
                    if (u.minuteOfDay != minute) u = ReminderRules.onTimeChanged(u, minute)
                    if (u != ex) updates += u
                }
            }
            if (inserts.isNotEmpty()) db.plans().insertSlots(inserts)
            if (updates.isNotEmpty()) db.plans().updateSlots(updates)
        }
    }

    /** После правки расписания в настройках: сегодня и завтра пересобираются, напоминания перестраиваются. */
    suspend fun applySchedule() = lock.withLock {
        val s = settingsWithSchedule()
        val today = today()
        listOf(today, today + 1).forEach { d -> if (db.plans().dayPlan(d) != null) syncSlots(d, s) else ensurePlanInner(d) }
        scheduler.rescheduleAll()
    }

    suspend fun states(day: Long): Map<SlotType, SlotState> = db.slotStates().forDay(day).associateBy { it.slot }

    /** Записать изменения статусов (одной транзакцией) и отразить их в расписании напоминаний. */
    suspend fun applyChanges(day: Long, changes: SlotChanges) {
        if (changes.upserts.isEmpty() && changes.deletes.isEmpty()) return
        db.withTransaction {
            if (changes.deletes.isNotEmpty()) db.slotStates().delete(day, changes.deletes.map { it.name })
            if (changes.upserts.isNotEmpty()) db.slotStates().upsertAll(changes.upserts)
            val states = db.slotStates().forDay(day).associateBy { it.slot }
            val touched = (changes.deletes + changes.upserts.map { it.slot }).toSet()
            val updates = db.plans().slots(day).filter { it.slot in touched }.mapNotNull { sl ->
                val status = SlotStates.reminderStatus(SlotStates.status(states, sl.slot))
                if (sl.status != status) sl.copy(status = status) else null
            }
            if (updates.isNotEmpty()) db.plans().updateSlots(updates)
        }
    }

    /** «Пропустить» вручную (19.2): склад, итоги и недельные счётчики не меняются. */
    suspend fun skip(day: Long, slot: SlotType) {
        lock.withLock { ensurePlanInner(day) }
        applyChanges(day, SlotStates.skip(day, slot, states(day), clock.millis()))
        scheduler.rescheduleAll()
    }

    /** «Отменить пропуск»: снова «не отмечен». */
    suspend fun unskip(day: Long, slot: SlotType) {
        applyChanges(day, SlotStates.unskip(slot, states(day)))
        scheduler.rescheduleAll()
    }

    suspend fun confirm(day: Long) = lock.withLock {
        val plan = ensurePlanInner(day)
        db.plans().upsertPlan(plan.copy(confirmed = true))
        scheduler.rescheduleAll()
    }

    suspend fun slot(id: Long) = db.plans().slot(id)
    suspend fun slots(day: Long) = db.plans().slots(day)
    suspend fun plan(day: Long) = db.plans().dayPlan(day)
    suspend fun updateSlot(s: PlannedSlot) = db.plans().updateSlot(s)

    /**
     * Ближайший неотмеченный слот (18.1) и цель на приём: остаток дневной цели / число слотов EMPTY впереди (19.8).
     */
    suspend fun nearestEmpty(day: Long, nowMinute: Int): SlotType? {
        val s = settingsWithSchedule()
        val states = states(day)
        val ahead = SlotStates.emptyAhead(s.times(), states, nowMinute)
        return ahead.firstOrNull() ?: SlotType.entries.lastOrNull { SlotStates.status(states, it) == MealSlotStatus.EMPTY }
    }

    suspend fun targetsFor(day: Long, nowMinute: Int): Pair<Double, Double> {
        val s = settingsWithSchedule()
        val logs = db.meals().forDay(day)
        val ahead = SlotStates.emptyAhead(s.times(), states(day), nowMinute).size
        return SlotStates.perSlotTarget(s.kcalTarget - logs.sumOf { it.kcal }, ahead) to
            SlotStates.perSlotTarget(s.proteinTarget - logs.sumOf { it.protein }, ahead)
    }

    /** Есть ли не отмеченные слоты, которые уже прошли (для «Сегодня»). */
    fun isPast(slot: PlannedSlot): Boolean = slot.status == SlotStatus.PLANNED &&
        TimeUtil.slotDateTime(slot.day, slot.minuteOfDay).isBefore(java.time.LocalDateTime.now(clock))
}
