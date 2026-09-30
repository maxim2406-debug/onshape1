package com.ration.app.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.data.repo.PlanRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Фоновая страховка (WorkManager): засев, план на сегодня, проверка порогов,
 * переустановка точных будильников — на случай, если система их сбросила.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val catalog: CatalogRepository,
    private val plans: PlanRepository,
    private val inventory: InventoryRepository,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        catalog.ensureSeeded()
        plans.ensurePlan(plans.today())
        inventory.checkThresholds()
        scheduler.rescheduleAll()
        // Отчёты для врача живут в кэше не дольше часа.
        java.io.File(applicationContext.cacheDir, "reports").listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.delete() }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        private const val NAME = "maintenance"
        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
