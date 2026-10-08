package com.ration.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.DayPlan
import com.ration.app.data.db.entity.Dish
import com.ration.app.data.db.entity.FormDaily
import com.ration.app.data.db.entity.HealthDocument
import com.ration.app.data.db.entity.LabResult
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.Purchase
import com.ration.app.data.db.entity.PurchaseLine
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.Recipe
import com.ration.app.data.db.entity.SlotState
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.db.entity.Substitution
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.data.db.entity.Workout
import com.ration.app.domain.model.SlotType
import kotlinx.coroutines.flow.Flow

data class ProductTotal(val productId: Long, val total: Double)

@Dao
interface ProductDao {
    @Query("SELECT * FROM product ORDER BY name") fun observeAll(): Flow<List<Product>>
    @Query("SELECT * FROM product ORDER BY name") suspend fun getAll(): List<Product>
    @Query("SELECT * FROM product WHERE id = :id") suspend fun get(id: Long): Product?
    @Query("SELECT * FROM product WHERE `key` = :key") suspend fun byKey(key: String): Product?
    @Query("SELECT COUNT(*) FROM product") suspend fun count(): Int
    @Insert suspend fun insert(p: Product): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(list: List<Product>)
    @Update suspend fun update(p: Product)
    @Update suspend fun updateAll(list: List<Product>)
    @Query("DELETE FROM product WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE product SET useCount = useCount + 1, lastUsedMillis = :now WHERE id IN (:ids)") suspend fun markUsed(ids: List<Long>, now: Long)
    @Query("SELECT DISTINCT category FROM product WHERE category != '' ORDER BY category") suspend fun categories(): List<String>
}

@Dao
interface StockDao {
    @Query("SELECT * FROM stock_item ORDER BY purchasedDay, id") fun observeAll(): Flow<List<StockItem>>
    @Query("SELECT * FROM stock_item ORDER BY purchasedDay, id") suspend fun getAll(): List<StockItem>
    @Query("SELECT * FROM stock_item WHERE productId = :productId ORDER BY purchasedDay, id") suspend fun forProduct(productId: Long): List<StockItem>
    @Query("SELECT productId, SUM(qty) AS total FROM stock_item GROUP BY productId") suspend fun totals(): List<ProductTotal>
    @Insert suspend fun insert(s: StockItem): Long
    @Insert suspend fun insertAll(list: List<StockItem>)
    @Query("DELETE FROM stock_item WHERE id IN (:ids)") suspend fun deleteIds(ids: List<Long>)
    @Query("SELECT COUNT(*) FROM stock_item WHERE productId = :productId") suspend fun countFor(productId: Long): Int
    @Update suspend fun updateAll(list: List<StockItem>)
    @Query("DELETE FROM stock_item WHERE productId = :productId") suspend fun deleteForProduct(productId: Long)
    @Query("DELETE FROM stock_item WHERE qty <= 0.000001 AND purchasedDay < :beforeDay") suspend fun purgeEmpty(beforeDay: Long)
}

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchase ORDER BY day DESC, id DESC") fun observeAll(): Flow<List<Purchase>>
    @Query("SELECT * FROM purchase") suspend fun getAll(): List<Purchase>
    @Query("SELECT * FROM purchase_line") suspend fun getAllLines(): List<PurchaseLine>
    @Query("SELECT * FROM purchase_line WHERE productId = :productId ORDER BY id DESC LIMIT 1") suspend fun lastLineFor(productId: Long): PurchaseLine?
    @Insert suspend fun insert(p: Purchase): Long
    @Insert suspend fun insertAll(list: List<Purchase>)
    @Insert suspend fun insertLines(lines: List<PurchaseLine>)
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM block ORDER BY kind, id") fun observeAll(): Flow<List<Block>>
    @Query("SELECT * FROM block ORDER BY kind, id") suspend fun getAll(): List<Block>
    @Query("SELECT * FROM block WHERE id = :id") suspend fun get(id: Long): Block?
    @Query("SELECT * FROM block WHERE code = :code") suspend fun byCode(code: String): Block?
    @Insert suspend fun insertAll(list: List<Block>)
    @Insert suspend fun insert(b: Block): Long
    @Update suspend fun update(b: Block)
    @Query("DELETE FROM block WHERE id = :id AND custom = 1") suspend fun deleteCustom(id: Long)
    @Query("DELETE FROM block_ingredient WHERE blockId = :blockId") suspend fun deleteIngredients(blockId: Long)
    @Query("SELECT COUNT(*) FROM block_ingredient WHERE productId = :productId") suspend fun countUsing(productId: Long): Int
    @Query("SELECT * FROM block_ingredient ORDER BY blockId, id") suspend fun allIngredients(): List<BlockIngredient>
    @Query("SELECT * FROM block_ingredient ORDER BY blockId, id") fun observeIngredients(): Flow<List<BlockIngredient>>
    @Query("SELECT * FROM block_ingredient WHERE blockId = :blockId ORDER BY id") suspend fun ingredients(blockId: Long): List<BlockIngredient>
    @Insert suspend fun insertIngredients(list: List<BlockIngredient>)
    @Update suspend fun updateIngredient(i: BlockIngredient)
}

@Dao
interface PrepDao {
    @Query("SELECT * FROM prep_template ORDER BY id") fun observeTemplates(): Flow<List<PrepTemplate>>
    @Query("SELECT * FROM prep_template ORDER BY id") suspend fun templates(): List<PrepTemplate>
    @Insert suspend fun insertTemplates(list: List<PrepTemplate>)
    @Update suspend fun updateTemplate(t: PrepTemplate)
    @Query("SELECT * FROM prep WHERE discarded = 0 AND portionsLeft > 0.000001 ORDER BY expiresDay, id") fun observeActive(): Flow<List<Prep>>
    @Query("SELECT * FROM prep ORDER BY expiresDay, id") suspend fun getAll(): List<Prep>
    @Query("SELECT * FROM prep WHERE discarded = 0 AND portionsLeft > 0.000001 ORDER BY expiresDay, id") suspend fun active(): List<Prep>
    @Insert suspend fun insert(p: Prep): Long
    @Insert suspend fun insertAll(list: List<Prep>)
    @Update suspend fun update(p: Prep)
    @Update suspend fun updateAll(list: List<Prep>)
}

@Dao
interface MealDao {
    @Query("SELECT * FROM meal_log WHERE day = :day ORDER BY atMillis") fun observeDay(day: Long): Flow<List<MealLog>>
    @Query("SELECT * FROM meal_log WHERE day = :day ORDER BY atMillis") suspend fun forDay(day: Long): List<MealLog>
    @Query("SELECT * FROM meal_log WHERE day BETWEEN :from AND :to ORDER BY atMillis") suspend fun range(from: Long, to: Long): List<MealLog>
    @Query("SELECT * FROM meal_log WHERE day BETWEEN :from AND :to ORDER BY atMillis") fun observeRange(from: Long, to: Long): Flow<List<MealLog>>
    @Query("SELECT * FROM meal_log") suspend fun getAll(): List<MealLog>
    @Query("SELECT * FROM meal_log ORDER BY atMillis DESC LIMIT :n") suspend fun recent(n: Int): List<MealLog>
    @Query("SELECT COUNT(*) FROM meal_log WHERE items LIKE :pattern") suspend fun countMentioning(pattern: String): Int
    @Query("SELECT * FROM meal_log WHERE id = :id") suspend fun get(id: Long): MealLog?
    @Query("SELECT * FROM meal_log WHERE day = :day AND slot = :slot ORDER BY atMillis") suspend fun forSlot(day: Long, slot: SlotType): List<MealLog>
    @Insert suspend fun insert(l: MealLog): Long
    @Update suspend fun update(l: MealLog)
    @Insert suspend fun insertAll(list: List<MealLog>)
    @Delete suspend fun delete(l: MealLog)

    @Query("SELECT * FROM custom_food ORDER BY name") fun observeFoods(): Flow<List<CustomFood>>
    @Query("SELECT * FROM custom_food ORDER BY name") suspend fun foods(): List<CustomFood>
    @Query("SELECT * FROM custom_food WHERE id = :id") suspend fun food(id: Long): CustomFood?
    @Insert suspend fun insertFood(f: CustomFood): Long
    @Insert suspend fun insertFoods(list: List<CustomFood>)

    @Query("SELECT * FROM substitution") suspend fun substitutions(): List<Substitution>
    @Query("SELECT * FROM substitution") fun observeSubstitutions(): Flow<List<Substitution>>
    @Query("SELECT * FROM substitution WHERE blockId = :blockId AND (permanent = 1 OR day = :day)") suspend fun substitutionsFor(blockId: Long, day: Long): List<Substitution>
    @Insert suspend fun insertSubstitution(s: Substitution): Long
    @Insert suspend fun insertSubstitutions(list: List<Substitution>)
    @Delete suspend fun deleteSubstitution(s: Substitution)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM day_plan WHERE day = :day") suspend fun dayPlan(day: Long): DayPlan?
    @Query("SELECT * FROM day_plan WHERE day = :day") fun observeDayPlan(day: Long): Flow<DayPlan?>
    @Query("SELECT * FROM day_plan") suspend fun allPlans(): List<DayPlan>
    @Upsert suspend fun upsertPlan(p: DayPlan)
    @Insert suspend fun insertPlans(list: List<DayPlan>)

    @Query("SELECT * FROM planned_slot WHERE day = :day ORDER BY minuteOfDay") fun observeSlots(day: Long): Flow<List<PlannedSlot>>
    @Query("SELECT * FROM planned_slot WHERE day = :day ORDER BY minuteOfDay") suspend fun slots(day: Long): List<PlannedSlot>
    @Query("SELECT * FROM planned_slot WHERE day BETWEEN :from AND :to") suspend fun slotsRange(from: Long, to: Long): List<PlannedSlot>
    @Query("SELECT * FROM planned_slot WHERE id = :id") suspend fun slot(id: Long): PlannedSlot?
    @Query("SELECT * FROM planned_slot") suspend fun allSlots(): List<PlannedSlot>
    @Insert suspend fun insertSlots(list: List<PlannedSlot>)
    @Update suspend fun updateSlot(s: PlannedSlot)
    @Update suspend fun updateSlots(list: List<PlannedSlot>)
    @Query("DELETE FROM planned_slot WHERE day = :day") suspend fun deleteSlots(day: Long)
}

/** Состояния приёмов (19.5). */
@Dao
interface SlotStateDao {
    @Query("SELECT * FROM slot_state WHERE day = :day") fun observeDay(day: Long): Flow<List<SlotState>>
    @Query("SELECT * FROM slot_state WHERE day = :day") suspend fun forDay(day: Long): List<SlotState>
    @Query("SELECT * FROM slot_state") suspend fun getAll(): List<SlotState>
    @Upsert suspend fun upsertAll(list: List<SlotState>)
    @Query("DELETE FROM slot_state WHERE day = :day AND slot IN (:slots)") suspend fun delete(day: Long, slots: List<String>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<SlotState>)
}

/** Отдельные блюда (19.4). IGNORE по ключу (legacyBlockId, componentIndex) — разбиение идемпотентно. */
@Dao
interface DishDao {
    @Query("SELECT * FROM dish ORDER BY legacyBlockId, componentIndex") fun observeAll(): Flow<List<Dish>>
    @Query("SELECT * FROM dish ORDER BY legacyBlockId, componentIndex") suspend fun getAll(): List<Dish>
    @Query("SELECT * FROM dish WHERE id = :id") suspend fun get(id: Long): Dish?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(list: List<Dish>): List<Long>
}

/** Раздел 20: тренировки, анализы, документы, кэш формы. */
@Dao
interface Health2Dao {
    @Query("SELECT * FROM workout ORDER BY startMillis DESC") fun observeWorkouts(): Flow<List<Workout>>
    @Query("SELECT * FROM workout ORDER BY startMillis") suspend fun workouts(): List<Workout>
    @Query("SELECT * FROM workout WHERE day BETWEEN :from AND :to ORDER BY startMillis") suspend fun workoutsRange(from: Long, to: Long): List<Workout>
    @Upsert suspend fun upsertWorkout(w: Workout): Long
    @Insert suspend fun insertWorkouts(list: List<Workout>)
    @Delete suspend fun deleteWorkout(w: Workout)

    @Query("SELECT * FROM lab_result ORDER BY day DESC, indicator") fun observeLabs(): Flow<List<LabResult>>
    @Query("SELECT * FROM lab_result ORDER BY day, indicator") suspend fun labs(): List<LabResult>
    @Upsert suspend fun upsertLab(r: LabResult): Long
    @Insert suspend fun insertLabs(list: List<LabResult>)
    @Delete suspend fun deleteLab(r: LabResult)

    @Query("SELECT * FROM health_document ORDER BY day DESC, id DESC") fun observeDocuments(): Flow<List<HealthDocument>>
    @Query("SELECT * FROM health_document ORDER BY day, id") suspend fun documents(): List<HealthDocument>
    @Query("SELECT * FROM health_document WHERE id = :id") suspend fun document(id: Long): HealthDocument?
    @Insert suspend fun insertDocument(d: HealthDocument): Long
    @Update suspend fun updateDocument(d: HealthDocument)
    @Delete suspend fun deleteDocument(d: HealthDocument)
    @Query("UPDATE lab_result SET documentId = NULL WHERE documentId = :docId") suspend fun detachLabs(docId: Long)

    @Upsert suspend fun upsertForm(list: List<FormDaily>)
    @Query("DELETE FROM form_daily") suspend fun clearForm()
}

@Dao
interface QuickDao {
    @Query("SELECT * FROM quick_log WHERE day = :day ORDER BY atMillis") fun observeDay(day: Long): Flow<List<QuickLog>>
    @Query("SELECT * FROM quick_log WHERE day = :day ORDER BY atMillis") suspend fun forDay(day: Long): List<QuickLog>
    @Query("SELECT * FROM quick_log WHERE day BETWEEN :from AND :to") suspend fun range(from: Long, to: Long): List<QuickLog>
    @Query("SELECT * FROM quick_log") suspend fun getAll(): List<QuickLog>
    @Insert suspend fun insert(q: QuickLog): Long
    @Insert suspend fun insertAll(list: List<QuickLog>)
    @Delete suspend fun delete(q: QuickLog)
}

@Dao
interface HealthDao {
    @Query("SELECT * FROM weight_log ORDER BY day") fun observeWeights(): Flow<List<WeightLog>>
    @Query("SELECT * FROM weight_log ORDER BY day") suspend fun weights(): List<WeightLog>
    @Insert suspend fun insertWeight(w: WeightLog): Long
    @Insert suspend fun insertWeights(list: List<WeightLog>)
    @Delete suspend fun deleteWeight(w: WeightLog)
    @Query("SELECT * FROM bp_log ORDER BY atMillis") fun observeBp(): Flow<List<BpLog>>
    @Query("SELECT * FROM bp_log ORDER BY atMillis") suspend fun bp(): List<BpLog>
    @Insert suspend fun insertBp(b: BpLog): Long
    @Insert suspend fun insertBps(list: List<BpLog>)
    @Delete suspend fun deleteBp(b: BpLog)
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipe ORDER BY title") fun observeAll(): Flow<List<Recipe>>
    @Query("SELECT * FROM recipe ORDER BY title") suspend fun getAll(): List<Recipe>
    @Query("SELECT * FROM recipe WHERE id = :id") suspend fun get(id: String): Recipe?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<Recipe>)
    @Query("DELETE FROM recipe WHERE id IN (:ids)") suspend fun deleteIds(ids: List<String>)
}

@Dao
abstract class MaintenanceDao {
    @Query("DELETE FROM product") abstract suspend fun products()
    @Query("DELETE FROM stock_item") abstract suspend fun stock()
    @Query("DELETE FROM purchase") abstract suspend fun purchases()
    @Query("DELETE FROM purchase_line") abstract suspend fun purchaseLines()
    @Query("DELETE FROM block") abstract suspend fun blocks()
    @Query("DELETE FROM block_ingredient") abstract suspend fun blockIngredients()
    @Query("DELETE FROM prep_template") abstract suspend fun prepTemplates()
    @Query("DELETE FROM prep") abstract suspend fun preps()
    @Query("DELETE FROM meal_log") abstract suspend fun mealLogs()
    @Query("DELETE FROM custom_food") abstract suspend fun customFoods()
    @Query("DELETE FROM substitution") abstract suspend fun substitutions()
    @Query("DELETE FROM day_plan") abstract suspend fun dayPlans()
    @Query("DELETE FROM planned_slot") abstract suspend fun plannedSlots()
    @Query("DELETE FROM quick_log") abstract suspend fun quickLogs()
    @Query("DELETE FROM weight_log") abstract suspend fun weights()
    @Query("DELETE FROM bp_log") abstract suspend fun bp()
    @Query("DELETE FROM recipe") abstract suspend fun recipes()
    @Query("DELETE FROM slot_state") abstract suspend fun slotStates()
    @Query("DELETE FROM dish") abstract suspend fun dishes()
    @Query("DELETE FROM workout") abstract suspend fun workouts()
    @Query("DELETE FROM lab_result") abstract suspend fun labResults()
    @Query("DELETE FROM health_document") abstract suspend fun documents()
    @Query("DELETE FROM form_daily") abstract suspend fun formDaily()

    open suspend fun deleteEverything() {
        products(); stock(); purchases(); purchaseLines(); blocks(); blockIngredients(); prepTemplates(); preps()
        mealLogs(); customFoods(); substitutions(); dayPlans(); plannedSlots(); quickLogs(); weights(); bp(); recipes()
        slotStates(); dishes()
        workouts(); labResults(); documents(); formDaily()
    }
}
