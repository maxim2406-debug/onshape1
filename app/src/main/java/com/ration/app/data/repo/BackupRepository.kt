package com.ration.app.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.settings.SecureKeyStore
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.backup.BackupCodec
import com.ration.app.domain.backup.BackupData
import com.ration.app.domain.backup.BackupException
import com.ration.app.notifications.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val keyStore: SecureKeyStore,
    private val scheduler: ReminderScheduler,
    private val catalog: CatalogRepository,
    private val clock: Clock,
) {
    suspend fun snapshot(): BackupData = BackupData(
        exportedAtMillis = clock.millis(),
        settings = settings.current(),
        products = db.products().getAll(),
        stock = db.stock().getAll(),
        purchases = db.purchases().getAll(),
        purchaseLines = db.purchases().getAllLines(),
        blocks = db.blocks().getAll(),
        blockIngredients = db.blocks().allIngredients(),
        prepTemplates = db.preps().templates(),
        preps = db.preps().getAll(),
        mealLogs = db.meals().getAll(),
        customFoods = db.meals().foods(),
        substitutions = db.meals().substitutions(),
        dayPlans = db.plans().allPlans(),
        plannedSlots = db.plans().allSlots(),
        quickLogs = db.quick().getAll(),
        weights = db.health().weights(),
        bp = db.health().bp(),
        recipes = db.recipes().getAll().filter { it.source == "user" },
    )

    /** Экспорт в файл, выбранный пользователем (SAF). Ключ API не экспортируется. */
    suspend fun export(uri: Uri, password: CharArray?) = withContext(Dispatchers.IO) {
        val text = BackupCodec.encode(snapshot(), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw BackupException("Не удалось открыть файл для записи")
    }

    suspend fun readText(uri: Uri, limit: Int = BackupCodec.MAX_BYTES): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(limit + 1)
            if (bytes.size > limit) throw BackupException("Файл слишком большой")
            String(bytes, Charsets.UTF_8)
        } ?: throw BackupException("Не удалось открыть файл")
    }

    /** Импорт: сначала полная проверка, затем замена базы одной транзакцией. */
    suspend fun import(text: String, password: CharArray?) {
        val d = BackupCodec.decode(text, password)
        db.withTransaction {
            db.maintenance().deleteEverything()
            db.products().insertAll(d.products)
            db.stock().insertAll(d.stock)
            db.purchases().insertAll(d.purchases)
            db.purchases().insertLines(d.purchaseLines)
            db.blocks().insertAll(d.blocks)
            db.blocks().insertIngredients(d.blockIngredients)
            db.preps().insertTemplates(d.prepTemplates)
            db.preps().insertAll(d.preps)
            db.meals().insertFoods(d.customFoods)
            db.meals().insertAll(d.mealLogs)
            db.meals().insertSubstitutions(d.substitutions)
            db.plans().insertPlans(d.dayPlans)
            db.plans().insertSlots(d.plannedSlots)
            db.quick().insertAll(d.quickLogs)
            db.health().insertWeights(d.weights)
            db.health().insertBps(d.bp)
            db.recipes().upsertAll(d.recipes.map { it.copy(source = "user") })
        }
        catalog.ensureSeeded()
        catalog.forceCookbookSync()
        // Режим API не переносится: ключ не входит в копию.
        settings.replace(d.settings.copy(claudeApiEnabled = d.settings.claudeApiEnabled && keyStore.hasApiKey()))
        scheduler.rescheduleAll()
    }

    /** «Удалить все данные»: база, настройки, ключ, кэш отчётов; затем чистый засев. */
    suspend fun deleteAll() {
        scheduler.cancelAll()
        db.withTransaction { db.maintenance().deleteEverything() }
        withContext(Dispatchers.IO) { File(context.cacheDir, "reports").deleteRecursively() }
        settings.clearAll()
        keyStore.clear()
        catalog.ensureSeeded()
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
