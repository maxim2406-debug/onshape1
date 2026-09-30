package com.ration.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.Recipe
import com.ration.app.data.seed.SeedData
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.cook.Cookbook
import com.ration.app.domain.cook.CookbookFile
import com.ration.app.domain.library.BulkRow
import com.ration.app.domain.library.CatalogSync
import com.ration.app.domain.library.FoodEntry
import com.ration.app.domain.library.FoodSearch
import com.ration.app.domain.library.LibraryParser
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.ProductSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Справочники: продукты, блоки, шаблоны заготовок, свои продукты, рецепты. */
@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    val products: Flow<List<Product>> = db.products().observeAll()
    val blocks: Flow<List<Block>> = db.blocks().observeAll()
    val ingredients: Flow<List<BlockIngredient>> = db.blocks().observeIngredients()
    val templates: Flow<List<PrepTemplate>> = db.preps().observeTemplates()
    val customFoods: Flow<List<CustomFood>> = db.meals().observeFoods()
    val recipes: Flow<List<Recipe>> = db.recipes().observeAll()

    /** Все записи библиотеки одним потоком (13.1): Product + CustomFood, с признаком «есть на складе». */
    val entries: Flow<List<FoodEntry>> = combine(db.products().observeAll(), db.meals().observeFoods(), db.stock().observeAll()) { ps, fs, st ->
        val inStock = st.filter { it.qty > 1e-6 }.map { it.productId }.toSet()
        ps.map { FoodEntry.of(it, it.id in inStock) } + fs.map { FoodEntry.of(it) }
    }

    private val lock = Mutex()
    @Volatile private var cookbookCache: CookbookFile? = null

    /** Засев на чистой установке и идемпотентная досинхронизация при обновлении (13.2.4). */
    suspend fun ensureSeeded() = lock.withLock {
        val seed = SeedData.build()
        if (db.products().count() == 0) {
            db.withTransaction {
                db.products().insertAll(seed.products)
                db.blocks().insertAll(seed.blocks)
                db.blocks().insertIngredients(seed.ingredients)
                db.preps().insertTemplates(seed.templates)
            }
        } else {
            val existing = db.products().getAll()
            val plan = CatalogSync.plan(existing, seed.products, SeedData.OLD_CATEGORIES)
            val noKey = existing.filter { it.searchKey.isEmpty() && plan.updates.none { u -> u.id == it.id } }
                .map { it.copy(searchKey = FoodSearch.buildKey(it.name, it.aliases, it.category, it.tags)) }
            db.withTransaction {
                if (plan.inserts.isNotEmpty()) db.products().insertAll(plan.inserts)
                if (plan.updates.isNotEmpty()) db.products().updateAll(plan.updates)
                if (noKey.isNotEmpty()) db.products().updateAll(noKey)
            }
        }
        syncCookbook()
        if (!settings.current().seeded) settings.update { it.copy(seeded = true) }
    }

    fun cookbook(): CookbookFile = cookbookCache ?: context.assets.open("cookbook.json").bufferedReader(Charsets.UTF_8)
        .use { Cookbook.parse(it.readText()) }.also { cookbookCache = it }

    /** База рецептов: при новой версии встроенные заменяются, правки пользователя остаются (18.8). */
    private suspend fun syncCookbook() = withContext(Dispatchers.IO) {
        val file = cookbook()
        val stored = settings.flag("cookbook_version")?.toIntOrNull() ?: 0
        val existing = db.recipes().getAll()
        if (stored == file.version && existing.any { it.source != "user" }) return@withContext
        val (del, ins) = Cookbook.merge(existing, file)
        db.withTransaction {
            if (del.isNotEmpty()) db.recipes().deleteIds(del)
            db.recipes().upsertAll(ins)
        }
        settings.setFlag("cookbook_version", file.version.toString())
    }

    suspend fun forceCookbookSync() {
        settings.setFlag("cookbook_version", "0")
        syncCookbook()
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
    suspend fun categories(): List<String> = (com.ration.app.domain.cook.Categories.START + db.products().categories()).distinct()
    suspend fun recipesList() = db.recipes().getAll()

    private fun withKey(p: Product) = p.copy(searchKey = FoodSearch.buildKey(p.name, p.aliases, p.category, p.tags))

    suspend fun saveProduct(p: Product): Long = if (p.id == 0L) db.products().insert(withKey(p)) else { db.products().update(withKey(p)); p.id }

    /**
     * Правка из библиотеки (13.2.3): изменение названия или пищевой ценности справочного продукта
     * делает его пользовательским (source = user) — обновление приложения его не перезапишет.
     */
    suspend fun editProduct(p: Product): Long {
        val old = db.products().get(p.id)
        val changedNutrition = old != null && (old.kcalPer100 != p.kcalPer100 || old.proteinPer100 != p.proteinPer100 || old.name != p.name)
        val src = if (old != null && old.source == ProductSource.REFERENCE && changedNutrition) ProductSource.USER else p.source
        return saveProduct(p.copy(source = src))
    }

    /** «Добавить в библиотеку» — только справочник, журнал и остатки не меняются (13.1.2). */
    suspend fun addToLibrary(p: Product): Long = saveProduct(p.copy(id = 0, source = if (p.source == ProductSource.REFERENCE) ProductSource.USER else p.source,
        cooked = p.cooked ?: LibraryParser.cookedFromName(p.name)))

    suspend fun bulkAdd(rows: List<BulkRow>): Int {
        val list = rows.map {
            withKey(Product(name = it.name, category = it.category ?: com.ration.app.domain.cook.Categories.SAUCES_OTHER, unit = MeasureUnit.G,
                kcalPer100 = it.kcal100, proteinPer100 = it.protein100, gramsPerPiece = it.gramsPerPiece, source = ProductSource.USER,
                cooked = LibraryParser.cookedFromName(it.name)))
        }
        db.products().insertAll(list)
        return list.size
    }

    suspend fun setFavorite(p: Product, fav: Boolean) = db.products().update(p.copy(favorite = fav))
    suspend fun setHidden(p: Product, hidden: Boolean) = db.products().update(p.copy(hidden = hidden))

    /** Продукт в блоках, запасах, покупках или журнале не удаляется, а скрывается (13.2.2). */
    suspend fun removeProduct(p: Product): Boolean {
        val used = p.key != null || db.stock().countFor(p.id) > 0 || db.blocks().countUsing(p.id) > 0 ||
            db.meals().countMentioning("%\"productId\":${p.id}%") > 0 || db.purchases().lastLineFor(p.id) != null
        if (used) db.products().update(p.copy(hidden = true)) else db.products().delete(p.id)
        return used
    }

    suspend fun addAlias(productId: Long, alias: String) {
        val p = db.products().get(productId) ?: return
        val a = alias.trim().take(120)
        if (a.isEmpty() || p.aliases.any { it.equals(a, ignoreCase = true) } || p.name.equals(a, ignoreCase = true)) return
        saveProduct(p.copy(aliases = p.aliases + a))
    }

    suspend fun saveBlock(b: Block) = db.blocks().update(b)
    suspend fun saveIngredient(i: BlockIngredient) = db.blocks().updateIngredient(i)

    /** Свой блок (12.4). */
    suspend fun saveCustomBlock(block: Block, ings: List<BlockIngredient>): Long = db.withTransaction {
        val id = if (block.id == 0L) db.blocks().insert(block) else { db.blocks().update(block); db.blocks().deleteIngredients(block.id); block.id }
        db.blocks().insertIngredients(ings.map { it.copy(id = 0, blockId = id) })
        id
    }

    suspend fun deleteCustomBlock(b: Block) = db.withTransaction {
        db.blocks().deleteIngredients(b.id)
        db.blocks().deleteCustom(b.id)
    }

    suspend fun saveUserRecipe(r: Recipe) = db.recipes().upsertAll(listOf(r.copy(source = "user")))
    suspend fun deleteRecipe(r: Recipe) = db.recipes().deleteIds(listOf(r.id))

    suspend fun customFoods() = db.meals().foods()
    suspend fun customFood(id: Long) = db.meals().food(id)
    suspend fun saveCustomFood(f: CustomFood): Long = db.meals().insertFood(f)
}
