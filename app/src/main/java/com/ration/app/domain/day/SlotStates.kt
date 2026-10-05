package com.ration.app.domain.day

import com.ration.app.data.db.entity.SlotState
import com.ration.app.domain.model.MealSlotStatus
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType

/** Изменения состояний слотов одного дня: [upserts] записать, [deletes] удалить (строки нет = EMPTY). */
data class SlotChanges(
    val upserts: List<SlotState> = emptyList(),
    val deletes: List<SlotType> = emptyList(),
    /** Слоты, пропущенные автоматически этой записью (сохраняются в MealLog.autoSkipped). */
    val autoSkipped: List<SlotType> = emptyList(),
) {
    fun apply(states: Map<SlotType, SlotState>): Map<SlotType, SlotState> =
        (states - deletes.toSet()) + upserts.associateBy { it.slot }
}

/**
 * Правила статусов приёмов (19.2–19.3). Чистые функции: состояние дня на входе, изменения на выходе.
 */
object SlotStates {
    fun status(states: Map<SlotType, SlotState>, slot: SlotType): MealSlotStatus = states[slot]?.status ?: MealSlotStatus.EMPTY

    /**
     * Запись в слот: слот становится LOGGED; все более ранние слоты того же дня со статусом EMPTY
     * получают SKIPPED с autoSkipped = true. LOGGED и SKIPPED не меняются, более поздние не затрагиваются.
     */
    fun onLog(day: Long, slot: SlotType, states: Map<SlotType, SlotState>, now: Long): SlotChanges {
        val upserts = mutableListOf<SlotState>()
        if (status(states, slot) != MealSlotStatus.LOGGED) upserts += SlotState(day, slot, MealSlotStatus.LOGGED, false, now)
        val auto = SlotType.entries.filter { it.ordinal < slot.ordinal && status(states, it) == MealSlotStatus.EMPTY }
        auto.forEach { upserts += SlotState(day, it, MealSlotStatus.SKIPPED, autoSkipped = true, updatedAt = now) }
        return SlotChanges(upserts, emptyList(), auto)
    }

    /**
     * Отмена записи: слот без оставшихся записей снова EMPTY; автопропущенные этой записью слоты возвращаются в EMPTY,
     * если они всё ещё автопропущены (вручную изменённые не трогаются).
     */
    fun onUndo(slot: SlotType?, slotStillHasLogs: Boolean, autoSkipped: List<SlotType>, states: Map<SlotType, SlotState>): SlotChanges {
        val deletes = mutableListOf<SlotType>()
        if (slot != null && !slotStillHasLogs && status(states, slot) == MealSlotStatus.LOGGED) deletes += slot
        autoSkipped.forEach { s ->
            val st = states[s]
            if (st != null && st.status == MealSlotStatus.SKIPPED && st.autoSkipped) deletes += s
        }
        return SlotChanges(emptyList(), deletes.distinct())
    }

    /** «Пропустить» вручную: только из EMPTY. Склад и счётчики не трогаются. */
    fun skip(day: Long, slot: SlotType, states: Map<SlotType, SlotState>, now: Long): SlotChanges =
        if (status(states, slot) == MealSlotStatus.EMPTY) SlotChanges(listOf(SlotState(day, slot, MealSlotStatus.SKIPPED, false, now)))
        else SlotChanges()

    /** «Отменить пропуск»: SKIPPED → EMPTY (и автопропуск, и ручной). */
    fun unskip(slot: SlotType, states: Map<SlotType, SlotState>): SlotChanges =
        if (status(states, slot) == MealSlotStatus.SKIPPED) SlotChanges(deletes = listOf(slot)) else SlotChanges()

    /** Статус для напоминаний «нет записи» (PlannedSlot): напоминает только EMPTY. */
    fun reminderStatus(status: MealSlotStatus): SlotStatus = when (status) {
        MealSlotStatus.EMPTY -> SlotStatus.PLANNED
        MealSlotStatus.SKIPPED -> SlotStatus.SKIPPED
        MealSlotStatus.LOGGED -> SlotStatus.EATEN
    }

    /**
     * Цель на приём (18.1, 19.8): остаток дневной цели делится на число слотов EMPTY впереди по времени
     * (текущий слот, если он ещё не отмечен, считается). Пропущенные и записанные слоты не делят остаток.
     */
    fun perSlotTarget(remaining: Double, emptyAhead: Int): Double = if (emptyAhead <= 0) remaining.coerceAtLeast(0.0) else (remaining / emptyAhead).coerceAtLeast(0.0)

    /** Слоты EMPTY, время которых ещё не прошло больше чем на [graceMin] минут. */
    fun emptyAhead(times: Map<SlotType, Int>, states: Map<SlotType, SlotState>, nowMinute: Int, graceMin: Int = 60): List<SlotType> =
        SlotType.entries.filter { status(states, it) == MealSlotStatus.EMPTY && (times[it] ?: 0) + graceMin >= nowMinute }
}
