package com.ration.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.DayPlan
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.plan.CounterRow
import com.ration.app.domain.plan.CounterState
import com.ration.app.domain.plan.WeekRules
import com.ration.app.domain.rules.DayRules
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.Dot
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.PlainTopBar
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.theme.LevelColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@Composable
fun MoreScreen(nav: NavController) {
    Scaffold(topBar = { PlainTopBar("Ещё") }) { pad ->
        Column(Modifier.padding(pad)) {
            listOf(
                "tomorrow" to ("Завтра" to "Время приёмов, цели, заготовки, подтверждение"),
                "week" to ("Неделя" to "Рыба, красное мясо, свободный обед, яйца"),
                "health" to ("Вес и давление" to "Журналы, графики, отчёт для врача"),
                "library" to ("Продукты (библиотека)" to "Поиск, добавление, пакетный ввод, скрытые"),
                "inventory" to ("Инвентаризация" to "Сверка остатков по списку или фото холодильника"),
                "blocks" to ("Блоки и рецепты" to "Исходные блоки (справочно), рецепты, мои сеты"),
                "settings" to ("Настройки" to "Цели, время, уведомления, резервная копия"),
            ).forEach { (route, t) ->
                ListItem(headlineContent = { Text(t.first) }, supportingContent = { Text(t.second) }, modifier = Modifier.clickable { nav.navigate(route) })
                HorizontalDivider()
            }
        }
    }
}

// ---------- План на завтра ----------

data class TomorrowState(
    val day: Long,
    val plan: DayPlan? = null,
    val slots: List<PlannedSlot> = emptyList(),
    val expiring: List<String> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

/** «Завтра» (19.8): шесть приёмов по одному расписанию, без типов дня и без готовых блюд. */
@HiltViewModel
class TomorrowViewModel @Inject constructor(
    private val plans: PlanRepository,
    inventory: com.ration.app.data.repo.InventoryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val day = plans.today() + 1
    val state = combine(plans.observePlan(day), plans.observeSlots(day), inventory.activePreps, settings.settings) { p, s, preps, st ->
        TomorrowState(day, p, s.sortedBy { it.slot.ordinal }, preps.filter { it.expiresDay <= day }.map { it.name }.distinct(), st)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TomorrowState(day))

    init {
        viewModelScope.launch { plans.ensurePlan(day) }
    }

    fun confirm() = viewModelScope.launch { plans.confirm(day) }
}

@Composable
fun TomorrowScreen(nav: NavController, vm: TomorrowViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s = st.settings
    Scaffold(topBar = { BackTopBar("Завтра, ${TimeUtil.dateRu(LocalDate.ofEpochDay(st.day))}", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Text("Приёмы собираются из продуктов в течение дня. Время меняется в Настройках.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            items(st.slots, key = { it.id }) { slot ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(TimeUtil.hm(slot.minuteOfDay), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(slot.slot.title, Modifier.weight(1f))
                }
                HorizontalDivider()
            }
            item {
                InfoCard("Цели дня") {
                    Text("Ккал: ${s.kcalTarget} (норма ${s.kcalMin}–${s.kcalMax})")
                    Text("Белок: ${s.proteinTarget} г (норма ${s.proteinMin}–${s.proteinMax})")
                    Text("Вода: ${s.waterGoalMl} мл")
                }
                if (st.expiring.isNotEmpty()) InfoCard("Заготовки: съесть до завтра") { st.expiring.forEach { Text("• $it") } }
                Button(onClick = vm::confirm, enabled = st.plan?.confirmed != true, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(if (st.plan?.confirmed == true) "Подтверждено" else "Подтвердить")
                }
            }
        }
    }
}

// ---------- Неделя ----------

data class WeekState(val rows: List<CounterRow> = emptyList(), val days: List<Triple<LocalDate, Double, Double>> = emptyList(), val settings: AppSettings = AppSettings())

@HiltViewModel
class WeekViewModel @Inject constructor(plans: PlanRepository, meals: MealRepository, settings: SettingsRepository) : ViewModel() {
    private val today = LocalDate.ofEpochDay(plans.today())
    val state = MutableStateFlow(WeekState())

    init {
        viewModelScope.launch {
            settings.settings.collectLatest { s ->
                val start = TimeUtil.weekStart(today, s.weekStart)
                meals.observeRange(start.toEpochDay(), start.plusDays(6).toEpochDay()).collect { logs: List<MealLog> ->
                    val quick = meals.quickRange(start.toEpochDay(), start.plusDays(6).toEpochDay())
                    val c = com.ration.app.domain.plan.WeekCounters.of(logs, quick)
                    val daysLeft = (start.plusDays(6).toEpochDay() - today.toEpochDay()).toInt()
                    val days = (0..6).map { start.plusDays(it.toLong()) }.filter { !it.isAfter(today) }.map { d ->
                        val l = logs.filter { it.day == d.toEpochDay() }
                        Triple(d, l.sumOf { it.kcal }, l.sumOf { it.protein })
                    }
                    state.value = WeekState(WeekRules.rows(c, s, daysLeft), days, s)
                }
            }
        }
    }
}

@Composable
fun WeekScreen(nav: NavController, vm: WeekViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Неделя", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item { SectionTitle("Счётчики (неделя с ${TimeUtil.dayShort(st.settings.weekStart)})") }
            items(st.rows) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(when (r.state) { CounterState.OK -> LevelColors.green; CounterState.WARN -> LevelColors.yellow; CounterState.BAD -> LevelColors.red })
                    Spacer(Modifier.width(12.dp))
                    Text(r.title, Modifier.weight(1f))
                    Text("${r.value} · ${r.target}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            item { SectionTitle("Дни") }
            items(st.days) { (d, kcal, protein) ->
                val red = DayRules.isRedDay(kcal, st.settings)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (red) Dot(LevelColors.red, 10) else Spacer(Modifier.width(10.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(TimeUtil.dateRu(d), Modifier.weight(1f))
                    Text("${Math.round(kcal)} ккал · ${Math.round(protein)} г")
                }
            }
        }
    }
}
