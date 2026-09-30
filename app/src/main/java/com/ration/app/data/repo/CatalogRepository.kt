package com.ration.app.data.repo

import androidx.room.withTransaction
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.seed.SeedData
import com.ration.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Справочники: продукты, блоки, шаблоны заготовок, свои продукты. */
@Singleton
class CatalogRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    val products: Flow<List<Product>> = db.products().observeAll()
    val blocks: Flow<List<Block>> = db.blocks().observeAll()
    val ingredients: Flow<List<BlockIngredient>> = db.blocks().observeIngredients()
    val templates: Flow<List<PrepTemplate>> = db.preps().observeTemplates()
    val customFoods: Flow<List<CustomFood>> = db.meals().observeFoods()

    /** Засев на чистой установке (разделы 7–9). Идемпотентно. */
    suspend fun ensureSeeded() {
        if (db.products().count() > 0) {
            if (!settings.current().seeded) settings.update { it.copy(seeded = true) }
            return
        }
        val seed = SeedData.build()
        db.withTransaction {
            db.products().insertAll(seed.products)
            db.blocks().insertAll(seed.blocks)
            db.blocks().insertIngredients(seed.ingredients)
            db.preps().insertTemplates(seed.templates)
        }
        settings.update { it.copy(seeded = true) }
    }

    suspend fun allProducts() = db.products().getAll()
    suspend fun allBlocks() = db.blocks().getAll()
    suspend fun allIngredients() = db.blocks().allIngredients()
    suspend fun block(id: Long) = db.blocks().get(id)
    suspend fun blockByCode(code: String) = db.blocks().byCode(code)
    suspend fun ingredientsOf(blockId: Long) = db.blocks().ingredients(blockId)
    suspend fun templates() = db.preps().templates()
    suspend fun product(id: Long) = db.products().get(id)
    suspend fun productByKey(key: String) = db.products().byKey(key)

    suspend fun saveProduct(p: Product): Long = if (p.id == 0L) db.products().insert(p) else { db.products().update(p); p.id }
    suspend fun saveBlock(b: Block) = db.blocks().update(b)
    suspend fun saveIngredient(i: BlockIngredient) = db.blocks().updateIngredient(i)

    suspend fun addAlias(productId: Long, alias: String) {
        val p = db.products().get(productId) ?: return
        val a = alias.trim().take(120)
        if (a.isEmpty() || p.aliases.any { it.equals(a, ignoreCase = true) } || p.name.equals(a, ignoreCase = true)) return
        db.products().update(p.copy(aliases = p.aliases + a))
    }

    suspend fun customFoods() = db.meals().foods()
    suspend fun customFood(id: Long) = db.meals().food(id)
    suspend fun saveCustomFood(f: CustomFood): Long = db.meals().insertFood(f)
}
