package com.ration.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.notifications.AppNotifier
import com.ration.app.notifications.MaintenanceWorker
import com.ration.app.notifications.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class RationApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: AppNotifier
    @Inject lateinit var catalog: CatalogRepository
    @Inject lateinit var plans: PlanRepository
    @Inject lateinit var scheduler: ReminderScheduler

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        appScope.launch {
            catalog.ensureSeeded()
            plans.ensurePlan(plans.today())
            scheduler.rescheduleAll()
        }
        MaintenanceWorker.enqueue(this)
    }
}
