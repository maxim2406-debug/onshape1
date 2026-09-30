package com.ration.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.DayPlan
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.LogOutcome
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.rules.DayWarning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodayState(
    val day: Long = 0,
    val plan: DayPlan? = null,
    val slots: List<PlannedSlot> = emptyList(),
    val logs: List<MealLog> = emptyList(),
    val quick: List<QuickLog> = emptyList(),
    val blocks: Map<Long, Block> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val warnings: List<DayWarning> = emptyList(),
) {
    val kcal get() = logs.sumOf { it.kcal }
    val protein get() = logs.sumOf { it.protein }
    val waterMl get() = quick.filter { it.type == QuickType.WATER }.sumOf { it.amount }
    val espresso get() = quick.count { it.type == QuickType.ESPRESSO }
}

/** Сообщение после действия: нехватка, лимиты, советы пересчёта. */
fun LogOutcome.message(): String? {
    val parts = mutableListOf<String>()
    if (shortages.isNotEmpty()) parts += "Не хватило на складе: " + shortages.joinToString(", ") { "${it.label} (−${TimeUtil.num(it.missing)} ${it.unit.label})" } + ". Приём записан."
    parts += notes
    parts += limitWarnings
    parts += advice
    return parts.joinToString("\n").ifBlank { null }
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val plans: PlanRepository,
    private val meals: MealRepository,
    catalog: CatalogRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val day = plans.today()
    private val warnings = MutableStateFlow<List<DayWarning>>(emptyList())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val base = combine(plans.observePlan(day), plans.observeSlots(day), meals.observeLogs(day), meals.observeQuick(day)) { p, s, l, q ->
        TodayState(day = day, plan = p, slots = s, logs = l, quick = q)
    }

    val state: StateFlow<TodayState> = combine(base, catalog.blocks, settingsRepo.settings, warnings) { st, b, s, w ->
        st.copy(blocks = b.associateBy { it.id }, settings = s, warnings = w)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayState(day = day))

    init {
        viewModelScope.launch {
            plans.ensurePlan(day)
            refreshWarnings()
        }
        viewModelScope.launch {
            combine(meals.observeLogs(day), meals.observeQuick(day)) { _, _ -> Unit }.collect { refreshWarnings() }
        }
    }

    private suspend fun refreshWarnings() {
        warnings.value = meals.warnings(day)
    }

    private fun act(block: suspend () -> String?) = viewModelScope.launch {
        runCatching { block() }.onSuccess { msg -> msg?.let { _messages.tryEmit(it) } }
            .onFailure { _messages.tryEmit("Ошибка: ${it.message ?: "неизвестно"}") }
    }

    fun setType(t: DayType) = act { plans.setDayType(day, t); "Тип дня ${t.label}: блоки пересобраны." }
    fun setRoad(road: Boolean) = act { plans.setRoad(day, road); null }
    fun eatPlanned(slot: PlannedSlot) = act { meals.eatPlanned(slot.id)?.message() }
    fun skip(slot: PlannedSlot) = act { meals.skip(slot.id).joinToString("\n").ifBlank { null } }
    fun water() = act { meals.quick(QuickType.WATER, 250.0); null }
    fun espresso() = act { meals.quick(QuickType.ESPRESSO); null }
    fun alcohol() = act { meals.quick(QuickType.ALCOHOL); "Алкоголь исключён, пока показатели печени не в норме." }
    fun bar(protein: Boolean) = act { meals.logBar(day, protein).message() ?: if (!protein) "Злаковый батончик не закрывает белок: добавьте белковый источник в следующий приём." else null }
    fun undo(log: MealLog) = act { meals.undo(log.id); "Запись отменена, запасы возвращены." }
    fun undoQuick(q: QuickLog) = act { meals.undoQuick(q); null }
}
