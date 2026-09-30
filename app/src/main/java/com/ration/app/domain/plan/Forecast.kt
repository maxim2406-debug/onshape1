package com.ration.app.domain.plan

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.Product
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Storage
import com.ration.app.domain.model.Tags

/** Подсказки вечернего прогноза: достать из морозилки, взять с собой, подготовить вечером. */
data class ForecastNotes(
    val fromFreezer: List<String>,
    val takeWith: List<String>,
    val prepEvening: List<String>,
    val needToBuy: List<String>,
)

object Forecast {
    fun notes(
        choices: List<PlannedChoice>,
        ingredients: Map<Long, List<BlockIngredient>>,
        products: Map<Long, Product>,
    ): ForecastNotes {
        val freezer = linkedSetOf<String>()
        val take = linkedSetOf<String>()
        val prep = linkedSetOf<String>()
        val buy = linkedSetOf<String>()
        for (c in choices) {
            val b: Block = c.block ?: continue
            for (ing in ingredients[b.id].orEmpty()) {
                val p = ing.productId?.let { products[it] } ?: continue
                if (!ing.toTaste && p.storage == Storage.FREEZER && c.spec.slot == SlotType.DINNER) freezer += "${p.name} — ${ing.label}"
            }
            val daytime = c.spec.slot == SlotType.LUNCH || c.spec.slot.isSnack ||
                (c.spec.slot == SlotType.BREAKFAST && Tags.CARRY in b.tags)
            if (daytime && Tags.CARRY in b.tags) take += "${b.code} ${b.name}"
            if (Tags.PREP_NIGHT in b.tags) prep += "Банка овсянки на ночь (${b.code}): 40 г овсянки, 200 г протеинового йогурта, ягоды"
            if (c.needsPurchase) buy += "${b.code} ${b.name}"
        }
        return ForecastNotes(freezer.toList(), take.toList(), prep.toList(), buy.toList())
    }
}
