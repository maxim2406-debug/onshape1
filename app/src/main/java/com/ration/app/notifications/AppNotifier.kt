package com.ration.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ration.app.MainActivity
import com.ration.app.R
import com.ration.app.domain.rules.DayWarning
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Все уведомления приложения. Тексты не содержат веса, давления и конкретных значений здоровья.
 */
@Singleton
class AppNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    object Channels {
        const val MEALS = "meals"
        const val PLAN = "plan"
        const val SHOPPING = "shopping"
        const val PREPS = "preps"
        const val HEALTH = "health"
        const val WATER = "water"
        const val WARNINGS = "warnings"
    }

    object Ids {
        const val FORECAST = 10
        const val SHOPPING = 11
        const val EAT_TODAY = 12
        const val PREP_WEEK = 13
        const val WEIGH = 14
        const val BP = 15
        const val WATER = 16
        const val FISH = 17
        const val WARNING_BASE = 100
        const val SLOT_BASE = 1000
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(Channels.MEALS, "Отметки приёмов пищи", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(Channels.PLAN, "План на завтра", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(Channels.SHOPPING, "Закупки", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(Channels.PREPS, "Заготовки", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(Channels.HEALTH, "Вес и давление", NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
            NotificationChannel(Channels.WATER, "Вода", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(Channels.WARNINGS, "Предупреждения дня", NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach(nm::createNotificationChannel)
    }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openIntent(route: String, requestCode: Int, slotId: Long? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
            slotId?.let { putExtra(MainActivity.EXTRA_SLOT_ID, it) }
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, requestCode: Int, slotId: Long? = null, day: Long? = null): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            slotId?.let { putExtra(NotificationActionReceiver.EXTRA_SLOT_ID, it) }
            day?.let { putExtra(NotificationActionReceiver.EXTRA_DAY, it) }
        }
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(channel: String, title: String, text: String, route: String, id: Int, sensitive: Boolean = false) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_ration)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(route, id))
            .setAutoCancel(true)
            .setVisibility(if (sensitive) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

    @Suppress("MissingPermission")
    private fun post(id: Int, n: NotificationCompat.Builder) {
        if (!canPost()) return
        runCatching { NotificationManagerCompat.from(context).notify(id, n.build()) }
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)
    fun cancelSlot(slotId: Long) = cancel(slotNotificationId(slotId))
    fun slotNotificationId(slotId: Long) = Ids.SLOT_BASE + (slotId % 100_000).toInt()

    /** «Вы пообедали? Отметьте, что и сколько съели.» */
    fun markReminder(slotId: Long, question: String, snoozeAvailable: Boolean) {
        val id = slotNotificationId(slotId)
        val rc = id * 10
        val b = base(Channels.MEALS, question, "Отметьте, что и сколько съели.", "log/$slotId", rc)
            .setContentIntent(openIntent("log/$slotId", rc, slotId))
            .addAction(0, "Записать", openIntent("log/$slotId", rc + 1, slotId))
            .addAction(0, "Съел по плану", actionIntent(NotificationActionReceiver.ACTION_EAT, rc + 2, slotId))
            .addAction(0, "Пропустил", actionIntent(NotificationActionReceiver.ACTION_SKIP, rc + 3, slotId))
        if (snoozeAvailable) b.addAction(0, "Через 30 минут", actionIntent(NotificationActionReceiver.ACTION_SNOOZE, rc + 4, slotId))
        post(id, b)
    }

    fun forecast(day: Long, text: String, proteinShort: Boolean = false) {
        val b = base(Channels.PLAN, "План на завтра", text + if (proteinShort) " Сегодня не хватает белка." else "", "tomorrow", Ids.FORECAST)
            .addAction(0, "Открыть", openIntent("tomorrow", Ids.FORECAST * 10 + 1))
            .addAction(0, "Подтвердить", actionIntent(NotificationActionReceiver.ACTION_CONFIRM, Ids.FORECAST * 10 + 2, day = day))
        if (proteinShort) b.addAction(0, "Варианты из холодильника", openIntent("cook", Ids.FORECAST * 10 + 3))
        post(Ids.FORECAST, b)
    }

    fun urgentShopping(count: Int) {
        val b = base(Channels.SHOPPING, "Пора в магазин", "В списке закупки позиций: $count.", "shopping", Ids.SHOPPING)
            .addAction(0, "Открыть список", openIntent("shopping", Ids.SHOPPING * 10 + 1))
        post(Ids.SHOPPING, b)
    }

    fun eatToday(names: List<String>) =
        post(Ids.EAT_TODAY, base(Channels.PREPS, "Съесть сегодня", "Заготовки истекают в течение суток: ${names.joinToString(", ")}.", "preps", Ids.EAT_TODAY))

    fun prepSoon(names: List<String>) =
        post(Ids.EAT_TODAY + 50, base(Channels.PREPS, "Заготовка скоро испортится", names.joinToString(", "), "preps", Ids.EAT_TODAY + 50))

    fun prepWeek() {
        val b = base(Channels.PREPS, "Заготовка на неделю", "Нет заготовок курицы, картофеля или яиц.", "prep_checklist", Ids.PREP_WEEK)
            .addAction(0, "Открыть чек-лист", openIntent("prep_checklist", Ids.PREP_WEEK * 10 + 1))
        post(Ids.PREP_WEEK, b)
    }

    fun weigh() = post(Ids.WEIGH, base(Channels.HEALTH, "Взвешивание", "Запишите вес за эту неделю.", "health", Ids.WEIGH, sensitive = true)
        .addAction(0, "Ввести", openIntent("health", Ids.WEIGH * 10 + 1)))

    fun bp() = post(Ids.BP, base(Channels.HEALTH, "Давление", "Время измерить и записать давление.", "health", Ids.BP, sensitive = true)
        .addAction(0, "Ввести", openIntent("health", Ids.BP * 10 + 1)))

    fun water() {
        val b = base(Channels.WATER, "Вода", "Выпито меньше литра. Добавьте стакан.", "today", Ids.WATER)
            .addAction(0, "+250 мл", actionIntent(NotificationActionReceiver.ACTION_WATER, Ids.WATER * 10 + 1))
        post(Ids.WATER, b)
    }

    fun fish(text: String) = post(Ids.FISH, base(Channels.WARNINGS, "Рыба на этой неделе", text, "week", Ids.FISH))

    fun dayWarning(w: DayWarning) {
        val id = Ids.WARNING_BASE + (w.id.hashCode() and 0x3F)
        post(id, base(Channels.WARNINGS, if (w.severe) "Важно" else "Предупреждение", w.text, "today", id))
    }
}
