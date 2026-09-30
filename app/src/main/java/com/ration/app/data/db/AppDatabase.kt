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
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.db.entity.Substitution
import com.ration.app.data.db.entity.WeightLog

@Database(
    entities = [
        Product::class, StockItem::class, Purchase::class, PurchaseLine::class, Block::class, BlockIngredient::class,
        PrepTemplate::class, Prep::class, MealLog::class, CustomFood::class, Substitution::class, DayPlan::class,
        PlannedSlot::class, QuickLog::class, WeightLog::class, BpLog::class,
    ],
    version = 1,
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

    companion object {
        const val NAME = "ration.db"
    }
}
