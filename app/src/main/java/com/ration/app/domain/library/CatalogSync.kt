package com.ration.app.domain.library

import com.ration.app.data.db.entity.Product
import com.ration.app.domain.model.ProductSource

data class SyncPlan(val inserts: List<Product>, val updates: List<Product>)

/**
 * Засев каталога при обновлении (13.2.4): новые строки добавляются, существующие не перезаписываются.
 * У справочных (source = reference) дозаполняются только новые служебные поля — теги, роль,
 * категория из старого набора, алиасы, ключ поиска. Продукты label и user не трогаются.
 */
object CatalogSync {
    fun plan(existing: List<Product>, seed: List<Product>, oldCategories: Set<String>): SyncPlan {
        val byKey = existing.filter { it.key != null }.associateBy { it.key!! }
        val byName = existing.associateBy { FoodSearch.normalize(it.name) }
        val inserts = mutableListOf<Product>()
        val updates = mutableListOf<Product>()
        for (s in seed) {
            val ex = byKey[s.key] ?: byName[FoodSearch.normalize(s.name)]
            if (ex == null) {
                inserts += s.copy(id = 0)
                continue
            }
            if (ex.source != ProductSource.REFERENCE) continue
            val merged = ex.copy(
                tags = (ex.tags + s.tags).distinct(),
                aliases = (ex.aliases + s.aliases).distinct(),
                role = ex.role ?: s.role,
                cooked = ex.cooked ?: s.cooked,
                category = if (ex.category in oldCategories || ex.category.isBlank()) s.category else ex.category,
                edibleFraction = if (ex.edibleFraction == 1.0) s.edibleFraction else ex.edibleFraction,
            ).let { it.copy(searchKey = FoodSearch.buildKey(it.name, it.aliases, it.category, it.tags)) }
            if (merged != ex) updates += merged
        }
        return SyncPlan(inserts, updates)
    }
}
