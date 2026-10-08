package com.ration.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ration.app.data.db.dao.BlockDao
import com.ration.app.data.db.dao.DishDao
import com.ration.app.data.db.dao.Health2Dao
import com.ration.app.data.db.dao.SlotStateDao
import com.ration.app.data.db.dao.HealthDao
import com.ration.app.data.db.dao.MaintenanceDao
import com.ration.app.data.db.dao.MealDao
import com.ration.app.data.db.dao.PlanDao
import com.ration.app.data.db.dao.PrepDao
import com.ration.app.data.db.dao.ProductDao
import com.ration.app.data.db.dao.PurchaseDao
import com.ration.app.data.db.dao.QuickDao
import com.ration.app.data.db.dao.RecipeDao
import com.ration.app.data.db.dao.StockDao
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.DayPlan
import com.ration.app.data.db.entity.Dish
import com.ration.app.data.db.entity.FormDaily
import com.ration.app.data.db.entity.HealthDocument
import com.ration.app.data.db.entity.LabResult
import com.ration.app.data.db.entity.Workout
import com.ration.app.data.db.entity.SlotState
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.Purchase
import com.ration.app.data.db.entity.PurchaseLine
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.Recipe
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.db.entity.Substitution
import com.ration.app.data.db.entity.WeightLog

@Database(
    entities = [
        Product::class, StockItem::class, Purchase::class, PurchaseLine::class, Block::class, BlockIngredient::class,
        PrepTemplate::class, Prep::class, MealLog::class, CustomFood::class, Substitution::class, DayPlan::class,
        PlannedSlot::class, QuickLog::class, WeightLog::class, BpLog::class, Recipe::class, SlotState::class, Dish::class,
        Workout::class, LabResult::class, HealthDocument::class, FormDaily::class,
    ],
    version = 4, // = AppDatabase.VERSION
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun stock(): StockDao
    abstract fun purchases(): PurchaseDao
    abstract fun blocks(): BlockDao
    abstract fun preps(): PrepDao
    abstract fun meals(): MealDao
    abstract fun plans(): PlanDao
    abstract fun quick(): QuickDao
    abstract fun health(): HealthDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun recipes(): RecipeDao
    abstract fun slotStates(): SlotStateDao
    abstract fun dishes(): DishDao
    abstract fun health2(): Health2Dao

    companion object {
        const val NAME = "ration.db"
        /** Совпадает с @Database(version). */
        const val VERSION = 4

        /**
         * v1 → v2 (разделы 12–18): новые поля продукта, партий, приёмов и блоков, таблица рецептов.
         * Данные пользователя сохраняются; продукты без ключа засева считаются своими (source = USER).
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE `product` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `product` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'REFERENCE'",
                    "ALTER TABLE `product` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `product` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `product` ADD COLUMN `edibleFraction` REAL NOT NULL DEFAULT 1.0",
                    "ALTER TABLE `product` ADD COLUMN `cooked` TEXT",
                    "ALTER TABLE `product` ADD COLUMN `role` TEXT",
                    "ALTER TABLE `product` ADD COLUMN `slots` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `product` ADD COLUMN `minPortion` REAL",
                    "ALTER TABLE `product` ADD COLUMN `maxPortion` REAL",
                    "ALTER TABLE `product` ADD COLUMN `searchKey` TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE `product` ADD COLUMN `useCount` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `product` ADD COLUMN `lastUsedMillis` INTEGER NOT NULL DEFAULT 0",
                    "CREATE INDEX IF NOT EXISTS `index_product_searchKey` ON `product` (`searchKey`)",
                    "UPDATE `product` SET `source` = 'USER' WHERE `key` IS NULL",
                    "UPDATE `product` SET `source` = 'LABEL' WHERE `key` IN ('cottage', 'protein_yogurt')",
                    "ALTER TABLE `stock_item` ADD COLUMN `approx` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `meal_log` ADD COLUMN `basedOnBlockCode` TEXT",
                    "ALTER TABLE `meal_log` ADD COLUMN `items` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `block` ADD COLUMN `custom` INTEGER NOT NULL DEFAULT 0",
                    "CREATE TABLE IF NOT EXISTS `recipe` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `slots` TEXT NOT NULL, " +
                        "`tags` TEXT NOT NULL, `ingredients` TEXT NOT NULL, `method` TEXT NOT NULL, `activeMin` INTEGER NOT NULL, " +
                        "`totalMin` INTEGER NOT NULL, `steps` TEXT NOT NULL, `rawToCooked` REAL NOT NULL, `source` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                ).forEach(db::execSQL)
            }
        }

        /**
         * v2 → v3 (раздел 19): шесть слотов, состояния приёмов, отдельные блюда.
         * - SNACK_1, SNACK_2, ROAD_BAR (все между обедом и ужином) → SNACK_PM (П) в журнале и расписании;
         *   при совпадении дня и слота в planned_slot лишняя строка расписания удаляется (журнал не трогается).
         * - slot_state: LOGGED для слотов, в которых есть записи; пустые слоты строк не получают (= EMPTY),
         *   автопропуск задним числом не делается.
         * - Исходные блоки скрываются (hidden = 1); свои блоки M… остаются. Блюда создаёт приложение (DishSplitter, INSERT OR IGNORE).
         * - day_plan.dayType не удаляется и не переименовывается.
         * Ни одна строка журнала, склада, библиотеки и настроек не удаляется.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "UPDATE `meal_log` SET `slot` = 'SNACK_PM' WHERE `slot` IN ('SNACK_1', 'SNACK_2', 'ROAD_BAR')",
                    "UPDATE `planned_slot` SET `slot` = 'SNACK_PM' WHERE `slot` = 'SNACK_1'",
                    "DELETE FROM `planned_slot` WHERE `slot` = 'SNACK_2' AND `day` IN (SELECT `day` FROM `planned_slot` WHERE `slot` = 'SNACK_PM')",
                    "UPDATE `planned_slot` SET `slot` = 'SNACK_PM' WHERE `slot` = 'SNACK_2'",
                    "DELETE FROM `planned_slot` WHERE `slot` = 'ROAD_BAR' AND `day` IN (SELECT `day` FROM `planned_slot` WHERE `slot` = 'SNACK_PM')",
                    "UPDATE `planned_slot` SET `slot` = 'SNACK_PM' WHERE `slot` = 'ROAD_BAR'",
                    "ALTER TABLE `block` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0",
                    "UPDATE `block` SET `hidden` = 1 WHERE `custom` = 0",
                    "ALTER TABLE `meal_log` ADD COLUMN `autoSkipped` TEXT NOT NULL DEFAULT '[]'",
                    "CREATE TABLE IF NOT EXISTS `slot_state` (`day` INTEGER NOT NULL, `slot` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`autoSkipped` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`day`, `slot`))",
                    "INSERT OR IGNORE INTO `slot_state` (`day`, `slot`, `status`, `autoSkipped`, `updatedAt`) " +
                        "SELECT `day`, `slot`, 'LOGGED', 0, MAX(`atMillis`) FROM `meal_log` WHERE `slot` IS NOT NULL GROUP BY `day`, `slot`",
                    "CREATE TABLE IF NOT EXISTS `dish` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `legacyBlockId` INTEGER NOT NULL, " +
                        "`componentIndex` INTEGER NOT NULL, `name` TEXT NOT NULL, `productId` INTEGER, `prepKey` TEXT, `qty` REAL NOT NULL, " +
                        "`unit` TEXT NOT NULL, `kcal` REAL NOT NULL, `protein` REAL NOT NULL, `tags` TEXT NOT NULL, `blockCode` TEXT NOT NULL, " +
                        "`hidden` INTEGER NOT NULL)",
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_dish_legacyBlockId_componentIndex` ON `dish` (`legacyBlockId`, `componentIndex`)",
                ).forEach(db::execSQL)
            }
        }

        /**
         * v3 → v4 (раздел 20): новые таблицы тренировок, анализов, документов и кэша формы.
         * Существующие таблицы (вес, давление, журнал, склад, свои блоки) не меняются.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "CREATE TABLE IF NOT EXISTS `workout` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `day` INTEGER NOT NULL, " +
                        "`startMillis` INTEGER NOT NULL, `durationMin` REAL NOT NULL, `distanceM` REAL, `inclinePct` REAL, `speedKmh` REAL, " +
                        "`kcalSource` TEXT NOT NULL, `kcalEntered` REAL NOT NULL, `kcalNet` REAL NOT NULL, `note` TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS `index_workout_day` ON `workout` (`day`)",
                    "CREATE TABLE IF NOT EXISTS `lab_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, `indicator` TEXT NOT NULL, " +
                        "`value` REAL NOT NULL, `unit` TEXT NOT NULL, `refLow` REAL, `refHigh` REAL, `documentId` INTEGER, `source` TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS `index_lab_result_day` ON `lab_result` (`day`)",
                    "CREATE INDEX IF NOT EXISTS `index_lab_result_indicator` ON `lab_result` (`indicator`)",
                    "CREATE TABLE IF NOT EXISTS `health_document` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `issuer` TEXT NOT NULL, `note` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS `index_health_document_day` ON `health_document` (`day`)",
                    "CREATE TABLE IF NOT EXISTS `form_daily` (`day` INTEGER NOT NULL, `bmr` REAL NOT NULL, `baseKcal` REAL NOT NULL, `workoutKcal` REAL NOT NULL, " +
                        "`intakeKcal` REAL NOT NULL, `hasFood` INTEGER NOT NULL, `computedAt` INTEGER NOT NULL, PRIMARY KEY(`day`))",
                ).forEach(db::execSQL)
            }
        }
    }
}
