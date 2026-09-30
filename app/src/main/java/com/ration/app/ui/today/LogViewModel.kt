package com.ration.app.ui.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.domain.importing.ImportParser
import com.ration.app.domain.importing.ParsedLabel
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.MeatChoice
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Tags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogUi(
    val slot: PlannedSlot? = null,
    val daySlots: List<PlannedSlot> = emptyList(),
    val alternatives: List<Block> = emptyList(),
    val done: String? = null,
    val error: String? = null,
)

@HiltViewModel
class LogViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val plans: PlanRepository,
    private val meals: MealRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val slotId: Long = savedState["slotId"] ?: 0L
    val day = plans.today()
    val ui = MutableStateFlow(LogUi())
    val blocks: StateFlow<List<Block>> = catalog.blocks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val foods: StateFlow<List<CustomFood>> = catalog.customFoods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val slot = if (slotId > 0) plans.slot(slotId) else null
            val d = slot?.day ?: day
            val alts = slot?.let { plans.alternatives(d)[it.slot] }.orEmpty()
            ui.value = LogUi(slot, plans.slots(d), alts)
        }
    }

    fun logBlock(block: Block, slot: SlotType?, multiplier: Double, fruit: Boolean, meat: MeatChoice?) = viewModelScope.launch {
        val d = ui.value.slot?.day ?: day
        val source = if (ui.value.slot?.blockId == block.id) MealSource.PLAN else MealSource.BLOCK
        val out = meals.logBlock(d, slot, block, multiplier, fruit, meat, source)
        ui.value = ui.value.copy(done = out.message() ?: "Записано")
    }

    fun parseLabel(text: String): ParsedLabel? = runCatching { ImportParser.parseLabel(text) }.getOrNull()

    fun logCustom(food: CustomFood, slot: SlotType?, grams: Double?, portions: Double?) = viewModelScope.launch {
        val d = ui.value.slot?.day ?: day
        val saved = if (food.id == 0L) food.copy(id = catalog.saveCustomFood(food)) else food
        val out = meals.logCustom(d, slot, saved, grams, portions)
        ui.value = ui.value.copy(done = out.message() ?: "Записано: ${saved.name}")
    }

    fun needsMeatQuestion(b: Block) = Tags.ASK_MEAT in b.tags
}
