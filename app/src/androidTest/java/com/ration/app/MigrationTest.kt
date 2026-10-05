package com.ration.app

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.PreMigrationBackup
import com.ration.app.domain.meal.DishSplitter
import com.ration.app.domain.meal.FoodCatalog
import com.ration.app.domain.model.MealSlotStatus
import com.ration.app.domain.model.SlotType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 19.7: база версии 2 с записями, остатками, своими блоками и продуктами открывается после миграции v2 → v3;
 * количества, записи и остатки совпадают; повторный запуск безопасен; блюда без дубликатов; копия файла до миграции.
 * Схема 2 — из репозитория (app/schemas), схема 3 — из текущей сборки.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @After fun cleanup() {
        context.deleteDatabase(name)
        File(context.filesDir, "backup").deleteRecursively()
    }

    private val day = 20_400L

    private fun seedV2(db: SupportSQLiteDatabase) {
        fun product(id: Int, key: String?, name: String, unit: String, kcal: Double, protein: Double) = db.execSQL(
            "INSERT INTO product (id, `key`, name, category, unit, kcalPer100, proteinPer100, gramsPerPiece, storage, parLevel, parManual, aliases, untracked, note, " +
                "tags, source, hidden, favorite, edibleFraction, cooked, role, slots, minPortion, maxPortion, searchKey, useCount, lastUsedMillis) VALUES " +
                "($id, ${key?.let { "'$it'" } ?: "NULL"}, '$name', 'рыба', '$unit', $kcal, $protein, NULL, 'FRIDGE', 500.0, 0, '[]', 0, '', '[\"fish\",\"fatty_fish\"]', " +
                "'${if (key == null) "USER" else "REFERENCE"}', 0, 0, 1.0, 'RAW', 'PROTEIN', '[]', NULL, NULL, '${name.lowercase()}', 3, 0)",
        )
        product(1, "salmon", "Лосось, сырой", "G", 208.0, 20.0)
        product(2, null, "Мой творог", "G", 120.0, 16.0)
        db.execSQL("INSERT INTO stock_item (id, productId, qty, purchasedDay, expiresDay, note, approx) VALUES (1, 1, 350.0, $day, ${day + 3}, '', 0)")
        db.execSQL("INSERT INTO stock_item (id, productId, qty, purchasedDay, expiresDay, note, approx) VALUES (2, 2, 400.0, $day, NULL, '', 1)")
        db.execSQL(
            "INSERT INTO block (id, code, kind, name, kcal, protein, prepMinutes, tags, recipeSteps, deductStock, composition, active, custom) VALUES " +
                "(1, 'У1', 'DINNER', 'Лосось + батат + брокколи', 550.0, 36.0, 20, '[\"fish\",\"fatty_fish\"]', '[]', 1, '', 1, 0)",
        )
        db.execSQL(
            "INSERT INTO block (id, code, kind, name, kcal, protein, prepMinutes, tags, recipeSteps, deductStock, composition, active, custom) VALUES " +
                "(2, 'M1', 'SNACK', 'Мой творог', 240.0, 32.0, 0, '[]', '[]', 1, '', 1, 1)",
        )
        db.execSQL("INSERT INTO block_ingredient (id, blockId, label, productId, altProductIds, prepKey, qty, unit, toTaste) VALUES (1, 1, 'лосось 180 г', 1, '[]', NULL, 180.0, 'G', 0)")
        db.execSQL("INSERT INTO block_ingredient (id, blockId, label, productId, altProductIds, prepKey, qty, unit, toTaste) VALUES (2, 2, 'творог 200 г', 2, '[]', NULL, 200.0, 'G', 0)")
        fun log(id: Int, slot: String?, name: String, kcal: Double) = db.execSQL(
            "INSERT INTO meal_log (id, day, atMillis, slot, blockId, blockCode, customFoodId, name, multiplier, kcal, protein, source, withFruit, meatChoice, " +
                "tags, eggs, deductions, basedOnBlockCode, items) VALUES ($id, $day, ${1_780_000_000_000 + id}, ${slot?.let { "'$it'" } ?: "NULL"}, NULL, NULL, NULL, " +
                "'$name', 1.0, $kcal, 20.0, 'CUSTOM', 0, NULL, '[]', 0.0, '[]', NULL, '[]')",
        )
        log(1, "LUNCH", "обед", 600.0)
        log(2, "SNACK_1", "перекус 1", 200.0)
        log(3, "SNACK_2", "перекус 2", 150.0)
        log(4, null, "без слота", 100.0)
        db.execSQL("INSERT INTO day_plan (day, dayType, road, confirmed, reason, freeLunchRequested) VALUES ($day, 'A', 0, 1, 'вручную', 0)")
        fun slot(id: Int, slot: String, minute: Int, status: String) = db.execSQL(
            "INSERT INTO planned_slot (id, day, slot, minuteOfDay, blockId, multiplier, status, optional, needsPurchase, lastReminderAtMillis, reminderCount, " +
                "snoozeUntilMillis, snoozeUsed) VALUES ($id, $day, '$slot', $minute, NULL, 1.0, '$status', 0, 0, NULL, 0, NULL, 0)",
        )
        slot(1, "LUNCH", 720, "EATEN")
        slot(2, "SNACK_1", 900, "REPLACED")
        slot(3, "SNACK_2", 1050, "REPLACED")
        slot(4, "DINNER", 1230, "PLANNED")
        db.execSQL("INSERT INTO custom_food (id, name, kcalPer100, proteinPer100, portionGrams, kcalPerPortion, proteinPerPortion, replacesProductId, isBar, isProteinBar) VALUES (1, 'Батончик', 400.0, 30.0, 50.0, 200.0, 15.0, NULL, 1, 1)")
        db.execSQL("INSERT INTO quick_log (id, day, atMillis, type, amount) VALUES (1, $day, 1780000000000, 'WATER', 250.0)")
    }

    private fun count(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    @Test fun migrate2To3KeepsEverything() {
        helper.createDatabase(name, 2).use { seedV2(it) }

        // 19.6: копия файла перед миграцией
        PreMigrationBackup.run(context, name, AppDatabase.VERSION)
        assertTrue(File(context.filesDir, "backup/pre-migration-2.db").exists())

        val db = helper.runMigrationsAndValidate(name, 3, true, AppDatabase.MIGRATION_2_3)
        assertEquals(2L, count(db, "SELECT COUNT(*) FROM product"))
        assertEquals(750.0, db.query("SELECT SUM(qty) FROM stock_item").use { it.moveToFirst(); it.getDouble(0) }, 1e-9)
        assertEquals(1L, count(db, "SELECT COUNT(*) FROM stock_item WHERE approx = 1"))
        assertEquals(4L, count(db, "SELECT COUNT(*) FROM meal_log"))
        assertEquals(1050.0, db.query("SELECT SUM(kcal) FROM meal_log").use { it.moveToFirst(); it.getDouble(0) }, 1e-9)
        assertEquals(2L, count(db, "SELECT COUNT(*) FROM meal_log WHERE slot = 'SNACK_PM'"))
        assertEquals(0L, count(db, "SELECT COUNT(*) FROM meal_log WHERE slot IN ('SNACK_1', 'SNACK_2', 'ROAD_BAR')"))
        // расписание: две строки П одного дня слились в одну
        assertEquals(1L, count(db, "SELECT COUNT(*) FROM planned_slot WHERE slot = 'SNACK_PM' AND day = $day"))
        assertEquals(3L, count(db, "SELECT COUNT(*) FROM planned_slot"))
        // статусы: LOGGED там, где есть записи; пустые слоты строк не получают
        assertEquals(2L, count(db, "SELECT COUNT(*) FROM slot_state WHERE status = 'LOGGED'"))
        assertEquals(2L, count(db, "SELECT COUNT(*) FROM slot_state"))
        // исходный блок скрыт, свой блок остался видимым; тип дня сохранён
        assertEquals(1L, count(db, "SELECT hidden FROM block WHERE code = 'У1'"))
        assertEquals(0L, count(db, "SELECT hidden FROM block WHERE code = 'M1'"))
        assertEquals("A", db.query("SELECT dayType FROM day_plan WHERE day = $day").use { it.moveToFirst(); it.getString(0) })
        assertEquals(1L, count(db, "SELECT COUNT(*) FROM custom_food"))
        assertEquals(1L, count(db, "SELECT COUNT(*) FROM quick_log"))
        db.close()

        // Room открывает базу (валидация схемы), статусы и записи читаются, разбиение блюд идемпотентно
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
        runBlocking {
            val states = room.slotStates().forDay(day).associateBy { it.slot }
            assertEquals(MealSlotStatus.LOGGED, states[SlotType.LUNCH]?.status)
            assertEquals(MealSlotStatus.LOGGED, states[SlotType.SNACK_PM]?.status)
            assertEquals(4, room.meals().forDay(day).size)
            val cat = FoodCatalog(room.products().getAll().associateBy { it.id })
            repeat(2) {
                room.dishes().insertIgnore(DishSplitter.split(room.blocks().getAll(), room.blocks().allIngredients().groupBy { it.blockId }, cat))
            }
            val dishes = room.dishes().getAll()
            assertEquals(1, dishes.size) // только У1: свой блок M1 не разбивается
            assertEquals(dishes.size, dishes.map { it.legacyBlockId to it.componentIndex }.toSet().size)
        }
        room.close()

        // повторное открытие: миграция не запускается, данные те же
        val again = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
        runBlocking { assertEquals(4, again.meals().getAll().size); assertEquals(1, again.dishes().getAll().size) }
        again.close()
        // копия перед миграцией делается, только если версия файла меньше текущей
        File(context.filesDir, "backup").deleteRecursively()
        PreMigrationBackup.run(context, name, AppDatabase.VERSION)
        assertTrue(!File(context.filesDir, "backup/pre-migration-3.db").exists())
    }

    @Test fun keepsLastTwoCopies() {
        val dir = File(context.filesDir, "backup").apply { mkdirs() }
        listOf(1, 2, 3).forEachIndexed { i, v ->
            File(dir, "pre-migration-$v.db").apply { writeText("x"); setLastModified(1_000_000L + i * 1000) }
        }
        PreMigrationBackup.prune(dir)
        assertEquals(setOf("pre-migration-2.db", "pre-migration-3.db"), dir.list()!!.toSet())
    }
}
