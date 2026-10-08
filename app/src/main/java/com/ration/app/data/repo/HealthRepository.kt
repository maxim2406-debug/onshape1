package com.ration.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.FormDaily
import com.ration.app.data.db.entity.HealthDocument
import com.ration.app.data.db.entity.LabResult
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.data.db.entity.Workout
import com.ration.app.domain.health.FormDay
import com.ration.app.domain.health.HealthRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val docs: DocumentStore,
    private val clock: Clock,
) {
    val weights: Flow<List<WeightLog>> = db.health().observeWeights()
    val bp: Flow<List<BpLog>> = db.health().observeBp()
    val workouts: Flow<List<Workout>> = db.health2().observeWorkouts()
    val labs: Flow<List<LabResult>> = db.health2().observeLabs()
    val documents: Flow<List<HealthDocument>> = db.health2().observeDocuments()

    /** Правила и пороги (assets/health_rules.json). */
    val rules: HealthRules by lazy { HealthRules.parse(context.assets.open("health_rules.json").bufferedReader().use { it.readText() }) }

    fun today(): Long = LocalDate.now(clock).toEpochDay()

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

    // ---- Тренировки (20.2) ----
    suspend fun saveWorkout(w: Workout): Long {
        require(w.durationMin in 1.0..600.0 && w.kcalNet in 0.0..5000.0) { "Значения тренировки вне диапазона" }
        return db.health2().upsertWorkout(w)
    }
    suspend fun deleteWorkout(w: Workout) = db.health2().deleteWorkout(w)
    suspend fun workoutsList() = db.health2().workouts()
    suspend fun workoutsRange(from: Long, to: Long) = db.health2().workoutsRange(from, to)

    // ---- Анализы (20.4) ----
    suspend fun saveLab(r: LabResult) = db.health2().upsertLab(r)
    suspend fun insertLabs(list: List<LabResult>) = db.health2().insertLabs(list)
    suspend fun deleteLab(r: LabResult) = db.health2().deleteLab(r)
    suspend fun labsList() = db.health2().labs()

    // ---- Документы (20.4, 20.7) ----
    suspend fun addDocument(bytes: ByteArray, mime: String, day: Long, type: com.ration.app.domain.model.DocType, title: String, issuer: String, note: String): Long {
        require(bytes.size <= DocumentStore.MAX_BYTES) { "Файл больше 20 МБ" }
        require(mime in DocumentStore.MIMES) { "Только PDF, JPG или PNG" }
        val name = docs.write(bytes)
        return db.health2().insertDocument(
            HealthDocument(day = day, type = type, title = title.take(200), issuer = issuer.take(200), note = note.take(1000), fileName = name,
                mime = mime, sizeBytes = bytes.size.toLong(), createdAt = clock.millis()),
        )
    }

    suspend fun updateDocument(d: HealthDocument) = db.health2().updateDocument(d)
    suspend fun documentBytes(d: HealthDocument): ByteArray = docs.read(d.fileName)
    suspend fun documentsList() = db.health2().documents()

    /** Удаление: файл затирается, связанные показатели остаются без ссылки на документ. */
    suspend fun deleteDocument(d: HealthDocument) {
        db.withTransaction {
            db.health2().detachLabs(d.id)
            db.health2().deleteDocument(d)
        }
        docs.wipe(d.fileName)
    }

    /** Кэш расчётов формы (можно пересоздать). */
    suspend fun cacheForm(days: List<FormDay>) {
        db.health2().upsertForm(days.map { FormDaily(it.day, it.bmr, it.baseKcal, it.workoutKcal, it.intakeKcal, it.hasFood, clock.millis()) })
    }
}
