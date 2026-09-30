package com.ration.app.data.repo

import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.WeightLog
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepository @Inject constructor(private val db: AppDatabase, private val clock: Clock) {
    val weights: Flow<List<WeightLog>> = db.health().observeWeights()
    val bp: Flow<List<BpLog>> = db.health().observeBp()

    suspend fun addWeight(kg: Double, day: Long = LocalDate.now(clock).toEpochDay()) {
        require(kg in 20.0..400.0) { "Вес вне диапазона" }
        db.health().insertWeight(WeightLog(day = day, kg = kg))
    }

    suspend fun addBp(sys: Int, dia: Int, pulse: Int?) {
        require(sys in 40..300 && dia in 20..200 && (pulse == null || pulse in 20..250)) { "Значения вне диапазона" }
        db.health().insertBp(BpLog(atMillis = clock.millis(), systolic = sys, diastolic = dia, pulse = pulse))
    }

    suspend fun deleteWeight(w: WeightLog) = db.health().deleteWeight(w)
    suspend fun deleteBp(b: BpLog) = db.health().deleteBp(b)
    suspend fun weightsList() = db.health().weights()
    suspend fun bpList() = db.health().bp()

    /** Есть ли запись веса за последние 7 дней. */
    suspend fun weighedThisWeek(): Boolean {
        val today = LocalDate.now(clock).toEpochDay()
        return db.health().weights().any { it.day > today - 7 }
    }
}
