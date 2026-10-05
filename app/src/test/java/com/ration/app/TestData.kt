package com.ration.app

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.seed.PrepKeys
import com.ration.app.data.seed.SeedBundle
import com.ration.app.data.seed.SeedData
import com.ration.app.domain.inventory.Consumption
import com.ration.app.domain.inventory.StockSnapshot

object TestData {
    val seed: SeedBundle by lazy { SeedData.build() }
    val products by lazy { seed.products.associateBy { it.id } }
    fun product(key: String) = seed.products.first { it.key == key }
    fun block(code: String) = seed.blocks.first { it.code == code }
    fun ingredients(b: Block) = seed.ingredients.filter { it.blockId == b.id }

    /** Полный склад: по 10 кг/шт каждого продукта и заготовки. */
    fun fullStock(today: Long): Pair<List<StockItem>, List<Prep>> {
        val stock = seed.products.mapIndexed { i, p -> StockItem(id = i + 1L, productId = p.id, qty = 10_000.0, purchasedDay = today - 1) }
        val preps = listOf(PrepKeys.CHICKEN to 150.0, PrepKeys.POTATO to 250.0, PrepKeys.BATAT to 200.0, PrepKeys.EGGS to null)
            .mapIndexed { i, (k, g) -> Prep(i + 1L, 1, k, k, today, today + 4, 100.0, 100.0, g) }
        return stock to preps
    }

}
