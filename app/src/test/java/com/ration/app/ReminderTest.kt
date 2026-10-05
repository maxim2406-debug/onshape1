package com.ration.app

import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.reminders.ReminderRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTest {
    private val s = AppSettings()
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val date = LocalDate.of(2026, 10, 1)
    private val day = date.toEpochDay()
    private fun at(h: Int, m: Int = 0) = date.atTime(h, m)
    private val lunch = PlannedSlot(id = 1, day = day, slot = SlotType.LUNCH, minuteOfDay = 14 * 60, blockId = 5)

    @Test fun firesTwoHoursAfterSlot() {
        assertEquals(at(16), ReminderRules.nextMarkReminder(lunch, at(9), s, zone))
    }

    @Test fun markingCancels() {
        listOf(SlotStatus.EATEN, SlotStatus.REPLACED, SlotStatus.SKIPPED).forEach {
            assertNull(ReminderRules.nextMarkReminder(lunch.copy(status = it), at(15), s, zone))
        }
    }

    @Test fun slotTimeChangeRecalculates() {
        val fired = ReminderRules.afterFired(lunch, TimeUtil.toMillis(at(16), zone))
        assertNull(ReminderRules.nextMarkReminder(fired, at(16, 1), s, zone))
        val moved = ReminderRules.onTimeChanged(fired, 15 * 60)
        assertEquals(at(17), ReminderRules.nextMarkReminder(moved, at(16, 1), s, zone))
    }

    @Test fun snoozeOnlyOnce() {
        val fired = ReminderRules.afterFired(lunch, TimeUtil.toMillis(at(16), zone))
        val snoozed = ReminderRules.snooze(fired, TimeUtil.toMillis(at(16, 5), zone), s)!!
        assertEquals(at(16, 35), ReminderRules.nextMarkReminder(snoozed, at(16, 5), s, zone))
        val firedAgain = ReminderRules.afterFired(snoozed, TimeUtil.toMillis(at(16, 35), zone))
        assertNull(ReminderRules.nextMarkReminder(firedAgain, at(16, 36), s, zone))
        assertNull(ReminderRules.snooze(firedAgain, TimeUtil.toMillis(at(16, 40), zone), s))
    }

    @Test fun quietHoursMoveTo7() {
        val evening = PlannedSlot(id = 2, day = day, slot = SlotType.EVENING, minuteOfDay = 22 * 60, blockId = 30)
        assertEquals(date.plusDays(1).atTime(7, 0), ReminderRules.nextMarkReminder(evening, at(21), s, zone))
        assertEquals(at(23, 30), ReminderRules.adjustForQuiet(at(23, 30), s, exempt = true))
        assertEquals(at(7), ReminderRules.adjustForQuiet(at(3), s))
        assertEquals(at(22, 59), ReminderRules.adjustForQuiet(at(22, 59), s))
    }

    @Test fun pastReminderAfterRebootFiresSoon() {
        val now: LocalDateTime = at(17)
        assertEquals(now.plusMinutes(1), ReminderRules.nextMarkReminder(lunch, now, s, zone))
    }

    /** Перекус без напоминаний (по умолчанию для С, П, Е): optional = true. */
    @Test fun optionalSnackNoReminder() {
        val ev = PlannedSlot(id = 3, day = day, slot = SlotType.EVENING, minuteOfDay = 22 * 60, blockId = null, optional = true)
        assertNull(ReminderRules.nextMarkReminder(ev, at(10), s, zone))
    }
}
