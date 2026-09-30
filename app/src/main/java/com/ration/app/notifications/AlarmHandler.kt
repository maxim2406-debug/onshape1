package com.ration.app.notifications

import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.HealthRepository
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.data.seed.PrepKeys
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Tags
import com.ration.app.domain.reminders.ReminderRules
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Логика срабатывания будильников и действий из уведомлений (вызывается из ресиверов и воркера). */
@Singleton
class AlarmHandler @Inject constructor(
    private val catalog: CatalogRepository,
    private val plans: PlanRepository,
    private val meals: MealRepository,
    private val inventory: InventoryRepository,
    private val health: HealthRepository,
    private val settings: SettingsRepository,
    private val notifier: AppNotifier,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    private fun today() = LocalDate.now(clock).toEpochDay()

    suspend fun onAlarm(kind: AlarmKind, slotId: Long) {
        catalog.ensureSeeded()
        val s = settings.current()
        val today = today()
        when (kind) {
            AlarmKind.MARK -> {
                val slot = plans.slot(slotId)
                if (slot != null && slot.status == SlotStatus.PLANNED) {
                    val snoozeAvailable = !slot.snoozeUsed
                    if (slot.reminderCount == 0 || (slot.snoozeUsed && slot.reminderCount == 1)) {
                        plans.updateSlot(ReminderRules.afterFired(slot, clock.millis()))
                        notifier.markReminder(slot.id, question(slot.slot), snoozeAvailable)
                    }
                }
            }
            AlarmKind.FORECAST -> {
                val tomorrow = today + 1
                val plan = plans.ensurePlan(tomorrow)
                if (!plan.confirmed) {
                    val slots = plans.slots(tomorrow)
                    val blocks = catalog.allBlocks().associateBy { it.id }
                    val lines = slots.sortedBy { it.minuteOfDay }.mapNotNull { sl ->
                        sl.blockId?.let(blocks::get)?.let { "${TimeUtil.hm(sl.minuteOfDay)} ${it.code}" }
                    }
                    val oat = slots.any { sl -> sl.blockId?.let(blocks::get)?.tags?.contains(Tags.PREP_NIGHT) == true }
                    val text = buildString {
                        append("Тип ${plan.dayType.label}${if (plan.road) " + дорога" else ""}: ")
                        append(lines.joinToString(", "))
                        if (oat) append(". Вечером: банка овсянки на ночь (З5).")
                    }
                    notifier.forecast(tomorrow, text)
                }
            }
            AlarmKind.SHOPPING_CHECK -> inventory.checkThresholds()
            AlarmKind.EAT_TODAY -> {
                val preps = inventory.activePrepsList()
                val soon = preps.filter { it.expiresDay <= today + 1 && it.expiresDay >= today }
                if (soon.isNotEmpty()) notifier.eatToday(soon.map { it.name }.distinct())
                val tomorrowEnd = preps.filter { it.expiresDay == today + 1 }
                if (tomorrowEnd.isNotEmpty()) notifier.prepSoon(tomorrowEnd.map { it.name }.distinct())
            }
            AlarmKind.PREP_WEEK -> {
                val keys = inventory.activePrepsList().map { it.outputKey }.toSet()
                if (PrepKeys.CHICKEN !in keys || PrepKeys.POTATO !in keys || PrepKeys.EGGS !in keys) notifier.prepWeek()
            }
            AlarmKind.WEIGH -> if (!health.weighedThisWeek()) notifier.weigh()
            AlarmKind.BP_MORNING, AlarmKind.BP_EVENING -> {
                val until = s.bpSeriesUntilDay
                if (until != null && until >= today) notifier.bp()
            }
            AlarmKind.WATER -> {
                val water = meals.quickFor(today).filter { it.type == QuickType.WATER }.sumOf { it.amount }
                if (water < s.waterCheckMinMl) notifier.water()
            }
            AlarmKind.FISH -> {
                val c = plans.weekCounters(today, includeDay = true)
                if (c.fattyFish < s.fattyFishMin || c.fish < 2) {
                    notifier.fish("На этой неделе рыбы: ${c.fish}, жирной: ${c.fattyFish}. Добавьте У1 или У10.")
                }
            }
            AlarmKind.LOW_PROTEIN -> meals.notifyNewWarnings(today)
            AlarmKind.MIDNIGHT -> plans.ensurePlan(today)
        }
        scheduler.rescheduleAll()
    }

    private fun question(slot: SlotType) = when (slot) {
        SlotType.BREAKFAST -> "Вы позавтракали?"
        SlotType.LUNCH -> "Вы пообедали?"
        SlotType.DINNER -> "Вы поужинали?"
        SlotType.EVENING -> "Был вечерний перекус?"
        SlotType.SNACK_1, SlotType.SNACK_2 -> "Был перекус?"
        SlotType.ROAD_BAR -> "Батончик в дороге?"
    }

    suspend fun onAction(action: String, slotId: Long, day: Long) {
        when (action) {
            NotificationActionReceiver.ACTION_EAT -> {
                notifier.cancelSlot(slotId)
                meals.eatPlanned(slotId)
            }
            NotificationActionReceiver.ACTION_SKIP -> {
                notifier.cancelSlot(slotId)
                meals.skip(slotId)
            }
            NotificationActionReceiver.ACTION_SNOOZE -> {
                notifier.cancelSlot(slotId)
                val slot = plans.slot(slotId) ?: return
                ReminderRules.snooze(slot, clock.millis(), settings.current())?.let { plans.updateSlot(it) }
                scheduler.rescheduleAll()
            }
            NotificationActionReceiver.ACTION_CONFIRM -> {
                notifier.cancel(AppNotifier.Ids.FORECAST)
                plans.confirm(day)
            }
            NotificationActionReceiver.ACTION_WATER -> {
                notifier.cancel(AppNotifier.Ids.WATER)
                meals.quick(QuickType.WATER, 250.0)
            }
        }
    }
}
