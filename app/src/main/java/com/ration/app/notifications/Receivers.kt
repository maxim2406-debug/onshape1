package com.ration.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.w("Ration", "receiver failed: ${e.javaClass.simpleName}")
        } finally {
            pending.finish()
        }
    }
}

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var handler: AlarmHandler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val kind = runCatching { AlarmKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: return) }.getOrNull() ?: return
        val slotId = intent.getLongExtra(EXTRA_SLOT_ID, 0)
        runAsync { handler.onAlarm(kind, slotId) }
    }

    companion object {
        const val ACTION_ALARM = "com.ration.app.action.ALARM"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SLOT_ID = "slotId"
    }
}

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var handler: AlarmHandler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val slotId = intent.getLongExtra(EXTRA_SLOT_ID, 0)
        val day = intent.getLongExtra(EXTRA_DAY, 0)
        runAsync { handler.onAction(action, slotId, day) }
    }

    companion object {
        const val ACTION_EAT = "com.ration.app.action.EAT"
        const val ACTION_SKIP = "com.ration.app.action.SKIP"
        const val ACTION_SNOOZE = "com.ration.app.action.SNOOZE"
        const val ACTION_CONFIRM = "com.ration.app.action.CONFIRM"
        const val ACTION_WATER = "com.ration.app.action.WATER"
        val ACTIONS = setOf(ACTION_EAT, ACTION_SKIP, ACTION_SNOOZE, ACTION_CONFIRM, ACTION_WATER)
        const val EXTRA_SLOT_ID = "slotId"
        const val EXTRA_DAY = "day"
    }
}

/** Только системные события: загрузка, обновление, смена пояса/времени, разрешение точных будильников. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SYSTEM_ACTIONS) return
        MaintenanceWorker.enqueue(context)
        runAsync { scheduler.rescheduleAll() }
    }

    companion object {
        val SYSTEM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
