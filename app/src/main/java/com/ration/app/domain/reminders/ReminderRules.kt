package com.ration.app.domain.reminders

import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType
import java.time.LocalDateTime
import java.time.ZoneId

object ReminderRules {
    /** Время внутри тихих часов (с учётом перехода через полночь). */
    fun inQuietHours(t: LocalDateTime, s: AppSettings): Boolean {
        val m = TimeUtil.minuteOf(t.toLocalTime())
        return if (s.quietStart > s.quietEnd) m >= s.quietStart || m < s.quietEnd
        else m >= s.quietStart && m < s.quietEnd
    }

    /** Перенос на конец тихих часов (07:00). Вечерний прогноз не переносится. */
    fun adjustForQuiet(t: LocalDateTime, s: AppSettings, exempt: Boolean = false): LocalDateTime {
        if (exempt || !inQuietHours(t, s)) return t
        val m = TimeUtil.minuteOf(t.toLocalTime())
        val day = if (s.quietStart > s.quietEnd && m >= s.quietStart) t.toLocalDate().plusDays(1) else t.toLocalDate()
        return day.atTime(TimeUtil.timeOf(s.quietEnd))
    }

    /**
     * Когда напомнить «Вы поели? Отметьте» для слота.
     * - только статус «запланирован»;
     * - первое напоминание через [AppSettings.markReminderDelayMin] после времени слота;
     * - «Через 30 минут» — единственный повтор;
     * - отметка любого приёма по слоту отменяет напоминание (статус ≠ PLANNED).
     * Возвращает null, если напоминать не нужно.
     */
    fun nextMarkReminder(slot: PlannedSlot, now: LocalDateTime, s: AppSettings, zone: ZoneId): LocalDateTime? {
        if (slot.status != SlotStatus.PLANNED) return null
        if (slot.blockId == null && slot.slot == SlotType.EVENING) return null
        val base = TimeUtil.slotDateTime(slot.day, slot.minuteOfDay)
        val snoozeAt = slot.snoozeUntilMillis
        val due: LocalDateTime = when {
            slot.reminderCount == 0 -> base.plusMinutes(s.markReminderDelayMin.toLong())
            slot.snoozeUsed && slot.reminderCount == 1 && snoozeAt != null -> TimeUtil.toLocal(snoozeAt, zone)
            else -> return null
        }
        val adjusted = adjustForQuiet(due, s)
        // Не напоминаем о слотах прошлых дней, если момент давно прошёл (например, после перезагрузки утром).
        if (adjusted.toLocalDate().isAfter(base.toLocalDate().plusDays(1))) return null
        if (adjusted.isBefore(now.minusHours(6))) return null
        return if (adjusted.isBefore(now)) now.plusMinutes(1) else adjusted
    }

    /** Состояние слота после показа напоминания. */
    fun afterFired(slot: PlannedSlot, nowMillis: Long): PlannedSlot =
        slot.copy(reminderCount = slot.reminderCount + 1, lastReminderAtMillis = nowMillis, snoozeUntilMillis = null)

    /** «Через 30 минут»: один раз. Возвращает null, если повтор уже использован. */
    fun snooze(slot: PlannedSlot, nowMillis: Long, s: AppSettings): PlannedSlot? {
        if (slot.snoozeUsed || slot.status != SlotStatus.PLANNED) return null
        return slot.copy(snoozeUsed = true, snoozeUntilMillis = nowMillis + s.snoozeMin * 60_000L)
    }

    /** Перенос времени слота сбрасывает напоминания по нему. */
    fun onTimeChanged(slot: PlannedSlot, newMinute: Int): PlannedSlot =
        slot.copy(minuteOfDay = newMinute, reminderCount = 0, snoozeUsed = false, snoozeUntilMillis = null, lastReminderAtMillis = null)
}
