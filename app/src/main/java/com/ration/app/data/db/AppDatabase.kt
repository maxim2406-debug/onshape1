package com.ration.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ration.app.data.db.dao.BlockDao
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
        PlannedSlot::class, QuickLog::class, WeightLog::class, BpLog::class, Recipe::class,
    ],
    version = 2,
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

    companion object {
        const val NAME = "ration.db"

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
    }
}
