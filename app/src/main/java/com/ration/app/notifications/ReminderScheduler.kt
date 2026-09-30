package com.ration.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.reminders.ReminderRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/** Виды будильников. Коды запросов PendingIntent стабильны. */
enum class AlarmKind(val code: Int) {
    MARK(0), FORECAST(1), SHOPPING_CHECK(2), EAT_TODAY(3), PREP_WEEK(4), WEIGH(5), BP_MORNING(6), BP_EVENING(7),
    WATER(8), FISH(9), LOW_PROTEIN(10), MIDNIGHT(11),
}

/**
 * Расписание: AlarmManager.setExactAndAllowWhileIdle, если разрешены точные будильники,
 * иначе неточный setAndAllowWhileIdle (приложение сообщает об этом в Настройках).
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    private fun intent(kind: AlarmKind, slotId: Long = 0): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
            putExtra(AlarmReceiver.EXTRA_KIND, kind.name)
            putExtra(AlarmReceiver.EXTRA_SLOT_ID, slotId)
        }

    private fun requestCode(kind: AlarmKind, slotId: Long = 0): Int =
        if (kind == AlarmKind.MARK) 100_000 + (slotId % 1_000_000).toInt() else kind.code

    private fun pending(kind: AlarmKind, slotId: Long = 0, create: Boolean = true): PendingIntent? =
        PendingIntent.getBroadcast(
            context, requestCode(kind, slotId), intent(kind, slotId),
            PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
        )

    private fun setAt(kind: AlarmKind, at: LocalDateTime, slotId: Long = 0) {
        val millis = TimeUtil.toMillis(at, clock.zone)
        val pi = pending(kind, slotId) ?: return
        try {
            if (canScheduleExact()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun cancel(kind: AlarmKind, slotId: Long = 0) {
        pending(kind, slotId, create = false)?.let { alarmManager.cancel(it); it.cancel() }
    }

    /** Ближайший момент minute в подходящий день недели (null — каждый день) после now. */
    private fun next(now: LocalDateTime, minute: Int, isoDay: Int? = null): LocalDateTime {
        var d = now.toLocalDate()
        if (isoDay != null) d = d.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(isoDay)))
        var t = d.atTime(TimeUtil.timeOf(minute))
        if (!t.isAfter(now)) t = if (isoDay != null) t.plusWeeks(1) else t.plusDays(1)
        return t
    }

    private fun quiet(t: LocalDateTime, s: AppSettings, exempt: Boolean = false) = ReminderRules.adjustForQuiet(t, s, exempt)

    /** Полная переустановка: после изменений, загрузки, смены пояса и времени. */
    suspend fun rescheduleAll() = mutex.withLock {
        val s = settings.current()
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate().toEpochDay()

        setAt(AlarmKind.FORECAST, quiet(next(now, s.forecastTime), s, exempt = true))
        setAt(AlarmKind.SHOPPING_CHECK, quiet(next(now, s.shoppingCheckTime), s))
        setAt(AlarmKind.EAT_TODAY, quiet(next(now, s.eatTodayTime), s))
        setAt(AlarmKind.PREP_WEEK, quiet(next(now, s.prepTime, s.prepDay), s))
        setAt(AlarmKind.WEIGH, quiet(next(now, s.weighTime, s.weighDay), s))
        setAt(AlarmKind.WATER, quiet(next(now, s.waterCheckTime), s))
        setAt(AlarmKind.FISH, quiet(next(now, s.fishReminderTime, s.fishReminderDay), s))
        setAt(AlarmKind.LOW_PROTEIN, quiet(next(now, s.lowProteinTime), s))
        setAt(AlarmKind.MIDNIGHT, next(now, 5))
        val bpUntil = s.bpSeriesUntilDay
        if (bpUntil != null && bpUntil >= today) {
            setAt(AlarmKind.BP_MORNING, quiet(next(now, s.bpMorning), s))
            setAt(AlarmKind.BP_EVENING, quiet(next(now, s.bpEvening), s))
        } else {
            cancel(AlarmKind.BP_MORNING); cancel(AlarmKind.BP_EVENING)
        }

        // Напоминания «нет отметки» по слотам вчера–завтра.
        for (slot in db.plans().slotsRange(today - 1, today + 1)) {
            val at = ReminderRules.nextMarkReminder(slot, now, s, clock.zone)
            if (at == null) cancel(AlarmKind.MARK, slot.id) else setAt(AlarmKind.MARK, at, slot.id)
        }
    }

    suspend fun cancelAll() = mutex.withLock {
        AlarmKind.entries.filter { it != AlarmKind.MARK }.forEach { cancel(it) }
        val today = LocalDate.now(clock).toEpochDay()
        db.plans().slotsRange(today - 1, today + 1).forEach { cancel(AlarmKind.MARK, it.id) }
    }
}
