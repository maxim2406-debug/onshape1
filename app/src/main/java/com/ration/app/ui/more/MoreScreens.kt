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
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.plan.CounterRow
import com.ration.app.domain.plan.CounterState
import com.ration.app.domain.plan.ForecastNotes
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
                "tomorrow" to ("План на завтра" to "Тип дня, блоки, что достать и взять с собой"),
                "week" to ("Неделя" to "Рыба, красное мясо, свободный обед, яйца"),
                "health" to ("Вес и давление" to "Журналы, графики, отчёт для врача"),
                "cook" to ("Что приготовить" to "Варианты из того, что есть дома"),
                "library" to ("Продукты (библиотека)" to "Поиск, добавление, пакетный ввод, скрытые"),
                "inventory" to ("Инвентаризация" to "Сверка остатков по списку или фото холодильника"),
                "blocks" to ("Блоки и рецепты" to "Каталог блоков, рецепты, замены"),
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
    val day: Long = 0,
    val plan: DayPlan? = null,
    val slots: List<PlannedSlot> = emptyList(),
    val blocks: Map<Long, Block> = emptyMap(),
    val alternatives: Map<SlotType, List<Block>> = emptyMap(),
    val notes: ForecastNotes? = null,
    val settings: AppSettings = AppSettings(),
)

@HiltViewModel
class TomorrowViewModel @Inject constructor(
    private val plans: PlanRepository,
    catalog: CatalogRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val day = plans.today() + 1
    private val extra = MutableStateFlow<Pair<Map<SlotType, List<Block>>, ForecastNotes?>>(emptyMap<SlotType, List<Block>>() to null)
    val state = combine(plans.observePlan(day), plans.observeSlots(day), catalog.blocks, settings.settings, extra) { p, s, b, st, e ->
        TomorrowState(day, p, s, b.associateBy { it.id }, e.first, e.second, st)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TomorrowState(day))

    init {
        viewModelScope.launch {
            plans.ensurePlan(day)
            plans.observeSlots(day).collect { refresh() }
        }
    }

    private suspend fun refresh() { extra.value = plans.alternatives(day) to plans.forecast(day) }

    fun setType(t: DayType) = viewModelScope.launch { plans.setDayType(day, t) }
    fun setRoad(r: Boolean) = viewModelScope.launch { plans.setRoad(day, r) }
    fun setFreeLunch(v: Boolean) = viewModelScope.launch { plans.setFreeLunch(day, v) }
    fun choose(slot: PlannedSlot, b: Block) = viewModelScope.launch { plans.setSlotBlock(slot.id, b.id) }
    fun confirm() = viewModelScope.launch { plans.confirm(day) }
    fun refreshFromCalendar() = viewModelScope.launch {
        val (t, reason) = plans.suggestType(day)
        plans.setDayType(day, t, reason)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TomorrowScreen(nav: NavController, vm: TomorrowViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val s = st.settings
    Scaffold(topBar = { BackTopBar("Завтра, ${TimeUtil.dateRu(LocalDate.ofEpochDay(st.day))}", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    DayType.entries.forEach { t -> FilterChip(st.plan?.dayType == t, { vm.setType(t) }, { Text("Тип ${t.label}") }) }
                    FilterChip(st.plan?.road == true, { vm.setRoad(st.plan?.road != true) }, { Text("В · дорога") })
                    FilterChip(st.plan?.freeLunchRequested == true, { vm.setFreeLunch(st.plan?.freeLunchRequested != true) }, { Text("Свободный обед") })
                }
                st.plan?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (s.calendarHint) AssistChip(onClick = vm::refreshFromCalendar, label = { Text("Подсказка по календарю") })
            }
            items(st.slots, key = { it.id }) { slot ->
                val b = slot.blockId?.let { st.blocks[it] }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${TimeUtil.hm(slot.minuteOfDay)} · ${slot.slot.label}", fontWeight = FontWeight.Bold)
                        Text(b?.let { "${it.code} ${it.name} · ${Math.round(it.kcal)} ккал · ${Math.round(it.protein)} г" + if (slot.needsPurchase) " · нужно купить" else "" } ?: "—")
                        val alts = st.alternatives[slot.slot].orEmpty()
                        if (alts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            alts.forEach { a -> AssistChip(onClick = { vm.choose(slot, a) }, label = { Text("${a.code} · ${Math.round(a.kcal)}") }) }
                        }
                    }
                }
            }
            item {
                val main = st.slots.filter { it.slot != SlotType.EVENING }.sumOf { (it.blockId?.let { id -> st.blocks[id]?.kcal } ?: 0.0) * it.multiplier }
                val evening = st.slots.filter { it.slot == SlotType.EVENING }.sumOf { (it.blockId?.let { id -> st.blocks[id]?.kcal } ?: 0.0) }
                val protein = st.slots.sumOf { (it.blockId?.let { id -> st.blocks[id]?.protein } ?: 0.0) * it.multiplier }
                val fruit = if (st.slots.any { it.slot == SlotType.LUNCH }) s.fruitKcal else 0
                val range = if (st.plan?.dayType == DayType.A) "${s.checksumAMin}–${s.checksumAMax}" else "${s.checksumBMin}–${s.checksumBMax}"
                InfoCard("Итоги") {
                    Text("Контрольная сумма: ${Math.round(main)} ккал (норма $range)")
                    Text("С фруктом и вечерним блоком: ${Math.round(main + fruit + evening)} ккал (цель ${s.kcalMin}–${s.kcalMax})")
                    Text("Белок: ${Math.round(protein)} г (цель ${s.proteinMin}–${s.proteinMax})")
                }
                st.notes?.let { n ->
                    if (n.fromFreezer.isNotEmpty()) InfoCard("Достать из морозилки") { n.fromFreezer.forEach { Text("• $it") } }
                    if (n.takeWith.isNotEmpty()) InfoCard("Взять с собой") { n.takeWith.forEach { Text("• $it") } }
                    if (n.prepEvening.isNotEmpty()) InfoCard("Подготовить вечером") { n.prepEvening.forEach { Text("• $it") } }
                    if (n.needToBuy.isNotEmpty()) InfoCard("Нужно купить") { n.needToBuy.forEach { Text("• $it") } }
                }
                Button(onClick = vm::confirm, enabled = st.plan?.confirmed != true, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(if (st.plan?.confirmed == true) "План подтверждён" else "Подтвердить")
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
