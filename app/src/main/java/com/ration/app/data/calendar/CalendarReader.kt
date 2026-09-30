package com.ration.app.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.plan.DayTypeHint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Читает только начало и длительность событий дня (без названий и участников) и ничего не сохраняет.
 */
@Singleton
class CalendarReader @Inject constructor(@ApplicationContext private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun eventsOn(date: LocalDate, zone: ZoneId): List<DayTypeHint.Event>? {
        if (!hasPermission()) return null
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        val out = mutableListOf<DayTypeHint.Event>()
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) == 1) continue
                    val b = c.getLong(0)
                    val e = c.getLong(1)
                    if (b < start || b >= end) continue
                    val minute = TimeUtil.minuteOf(TimeUtil.toLocal(b, zone).toLocalTime())
                    out += DayTypeHint.Event(minute, ((e - b) / 60_000L).toInt())
                }
            }
            out.toList()
        }.getOrNull()
    }
}
