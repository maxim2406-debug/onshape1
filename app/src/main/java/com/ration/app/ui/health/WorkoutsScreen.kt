package com.ration.app.ui.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Workout
import com.ration.app.data.repo.HealthRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.health.Energy
import com.ration.app.domain.health.KcalEstimate
import com.ration.app.domain.model.KcalSource
import com.ration.app.domain.model.WorkoutType
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.toNumberOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class WorkoutsViewModel @Inject constructor(
    private val health: HealthRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    val workouts = health.workouts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val weights = health.weights.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val today: Long get() = LocalDate.now(clock).toEpochDay()

    suspend fun bmrOn(day: Long): Double? {
        val s = settings.current()
        val kg = Energy.weightOn(health.weightsList(), day) ?: return null
        return Energy.bmr(s.sex, kg, s.heightCm.toDouble(), s.ageYears)
    }

    fun save(w: Workout, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { health.saveWorkout(w) }.onFailure { onError(it.message ?: "Ошибка") }
    }

    fun delete(w: Workout) = viewModelScope.launch { health.deleteWorkout(w) }
    fun restore(w: Workout) = viewModelScope.launch { health.saveWorkout(w) }
}

/** Тренировки (20.2): бассейн и дорожка, общий список по датам, фильтр, правка, удаление с отменой. */
@Composable
fun WorkoutsScreen(nav: NavController, vm: WorkoutsViewModel = hiltViewModel()) {
    val list by vm.workouts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf<WorkoutType?>(null) }
    var days by remember { mutableStateOf(30) }
    var editing by remember { mutableStateOf<Workout?>(null) }
    var creating by remember { mutableStateOf<WorkoutType?>(null) }
    val shown = list.filter { (type == null || it.type == type) && it.day > vm.today - days }
    Scaffold(
        topBar = { BackTopBar("Тренировки", { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtendedFloatingActionButton(onClick = { creating = WorkoutType.SWIM }) { Text("+ Бассейн") }
                ExtendedFloatingActionButton(onClick = { creating = WorkoutType.WALK }) { Text("+ Дорожка") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(type == null, { type = null }, { Text("Все") })
                    WorkoutType.entries.forEach { t -> FilterChip(type == t, { type = t }, { Text(t.label) }) }
                    listOf(7, 30, 90, 365).forEach { d -> FilterChip(days == d, { days = d }, { Text("$d дн.") }) }
                }
                Text("За период: ${Math.round(shown.sumOf { it.durationMin })} мин, ${Math.round(shown.sumOf { it.kcalNet })} ккал чистых",
                    style = MaterialTheme.typography.bodySmall)
                if (shown.isEmpty()) Text("Тренировок нет. Добавьте бассейн или дорожку кнопкой внизу.", modifier = Modifier.padding(vertical = 16.dp))
            }
            items(shown, key = { it.id }) { w ->
                Row(Modifier.fillMaxWidth().clickable { editing = w }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val t = TimeUtil.toLocal(w.startMillis, ZoneId.systemDefault())
                        Text("${w.type.label} · ${TimeUtil.dateShort(t.toLocalDate())} ${TimeUtil.hm(TimeUtil.minuteOf(t.toLocalTime()))}", fontWeight = FontWeight.Medium)
                        Text(details(w) + " · ${Math.round(w.kcalNet)} ккал (${w.kcalSource.label})", style = MaterialTheme.typography.bodySmall)
                        if (w.note.isNotBlank()) Text(w.note, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        vm.delete(w)
                        scope.launch { if (snackbar.showSnackbar("Тренировка удалена", "Отменить") == SnackbarResult.ActionPerformed) vm.restore(w) }
                    }) { Text("Удалить") }
                }
                HorizontalDivider()
            }
            item { Text(" ", modifier = Modifier.padding(40.dp)) }
        }
    }
    creating?.let { t -> WorkoutDialog(vm, null, t, onDismiss = { creating = null }, onError = { scope.launch { snackbar.showSnackbar(it) } }) }
    editing?.let { w -> WorkoutDialog(vm, w, w.type, onDismiss = { editing = null }, onError = { scope.launch { snackbar.showSnackbar(it) } }) }
}

private fun details(w: Workout): String = when (w.type) {
    WorkoutType.SWIM -> "${Math.round(w.durationMin)} мин" + (w.distanceM?.let { d ->
        " · ${Math.round(d)} м · темп ${Energy.swimPaceSec(d, w.durationMin)?.let { Energy.paceLabel(it) } ?: "—"}/100 м" } ?: "")
    WorkoutType.WALK -> "${Math.round(w.durationMin)} мин" + (w.speedKmh?.let { " · ${TimeUtil.num(it)} км/ч" } ?: "") +
        (w.inclinePct?.let { " · уклон ${TimeUtil.num(it)}%" } ?: "") + (w.distanceM?.let { " · ${TimeUtil.num(it / 1000, 2)} км" } ?: "")
}

@Composable
private fun WorkoutDialog(vm: WorkoutsViewModel, existing: Workout?, type: WorkoutType, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val start0 = existing?.let { TimeUtil.toLocal(it.startMillis, ZoneId.systemDefault()) } ?: LocalDateTime.now()
    var date by remember { mutableStateOf(TimeUtil.dateShort(start0.toLocalDate()) + "." + start0.year) }
    var time by remember { mutableStateOf(TimeUtil.hm(TimeUtil.minuteOf(start0.toLocalTime()))) }
    var minutes by remember { mutableStateOf(existing?.durationMin?.let { TimeUtil.num(it) } ?: "") }
    var distance by remember { mutableStateOf(existing?.distanceM?.let { TimeUtil.num(it) } ?: "") }
    var incline by remember { mutableStateOf(existing?.inclinePct?.let { TimeUtil.num(it) } ?: "") }
    var speed by remember { mutableStateOf(existing?.speedKmh?.let { TimeUtil.num(it) } ?: "") }
    var calc by remember { mutableStateOf(existing?.kcalSource?.let { it == KcalSource.CALC } ?: true) }
    var total by remember { mutableStateOf(existing?.kcalSource == KcalSource.INPUT_TOTAL) }
    var kcal by remember { mutableStateOf(existing?.takeIf { it.kcalSource != KcalSource.CALC }?.kcalEntered?.let { TimeUtil.num(it) } ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    val day = com.ration.app.domain.health.Labs.parseDate(date)
    val kg = day?.let { Energy.weightOn(weights, it) }
    val min = minutes.toNumberOrNull()
    // ходьба: два из трёх (время, скорость, расстояние) → третье
    val walk = if (type == WorkoutType.WALK) Energy.solveWalk(min, speed.toNumberOrNull(), distance.toNumberOrNull()) else null
    val estimate: KcalEstimate? = when {
        kg == null -> null
        type == WorkoutType.SWIM -> distance.toNumberOrNull()?.let { d -> min?.let { Energy.swim(d, it, kg) } }
        walk != null -> Energy.walk(walk.speedKmh, incline.toNumberOrNull() ?: 0.0, kg, walk.minutes)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (existing == null) "Новая тренировка: " else "") + type.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата ДД.ММ.ГГГГ") }, singleLine = true, modifier = Modifier.weight(1.4f))
                    OutlinedTextField(time, { time = it.take(5) }, label = { Text("Время") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                NumberField("Длительность", minutes, { minutes = it }, Modifier.fillMaxWidth(), "мин")
                NumberField("Расстояние", distance, { distance = it }, Modifier.fillMaxWidth(), "м")
                if (type == WorkoutType.SWIM) {
                    val pace = distance.toNumberOrNull()?.let { d -> min?.let { Energy.swimPaceSec(d, it) } }
                    Text("Темп: ${pace?.let { Energy.paceLabel(it) + " на 100 м" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                } else {
                    NumberField("Средняя скорость", speed, { speed = it }, Modifier.fillMaxWidth(), "км/ч")
                    NumberField("Уклон", incline, { incline = it }, Modifier.fillMaxWidth(), "%")
                    walk?.let { Text("Время ${TimeUtil.num(it.minutes)} мин · ${TimeUtil.num(it.speedKmh)} км/ч · ${TimeUtil.num(it.distanceM / 1000, 2)} км",
                        style = MaterialTheme.typography.bodySmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!calc, { calc = false }, { Text("Ввести") })
                    FilterChip(calc, { calc = true }, { Text("Рассчитать") })
                }
                if (calc) {
                    when {
                        kg == null -> Text("Для расчёта нужен вес до даты тренировки (запишите в «Вес и давление»).", color = MaterialTheme.colorScheme.error)
                        estimate == null -> Text(if (type == WorkoutType.SWIM) "Укажите длительность и расстояние." else "Укажите два из трёх: время, скорость, расстояние.")
                        else -> {
                            Text("≈ ${Math.round(estimate.kcalNet)} ккал чистых (оценка ±20–30%)", fontWeight = FontWeight.SemiBold)
                            Text(estimate.formula + " Вес ${TimeUtil.num(kg)} кг.", style = MaterialTheme.typography.bodySmall)
                            estimate.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else {
                    NumberField("Ккал с часов или тренажёра", kcal, { kcal = it }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(!total, { total = false }, { Text("Активные") })
                        FilterChip(total, { total = true }, { Text("Общие") })
                    }
                    Text("В баланс идут активные: из «общих» вычитается энергия покоя за время тренировки.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = day ?: return@TextButton onError("Дата в формате ДД.ММ.ГГГГ")
                val mm = TimeUtil.parseHm(time) ?: return@TextButton onError("Время в формате ЧЧ:ММ")
                val dur = (if (type == WorkoutType.WALK) walk?.minutes else min) ?: return@TextButton onError("Укажите длительность")
                scope.launch {
                    val (src, entered, net) = if (calc) {
                        val e = estimate ?: return@launch onError("Не хватает данных для расчёта")
                        Triple(KcalSource.CALC, e.kcalNet, e.kcalNet)
                    } else {
                        val k = kcal.toNumberOrNull() ?: return@launch onError("Введите ккал")
                        if (total) {
                            val bmr = vm.bmrOn(d) ?: return@launch onError("Для перевода «общих» нужен вес до даты тренировки")
                            Triple(KcalSource.INPUT_TOTAL, k, Energy.netFromTotal(k, bmr, dur))
                        } else Triple(KcalSource.INPUT_ACTIVE, k, k)
                    }
                    val startMs = TimeUtil.toMillis(LocalDate.ofEpochDay(d).atTime(TimeUtil.timeOf(mm)), ZoneId.systemDefault())
                    vm.save(Workout(
                        id = existing?.id ?: 0, type = type, day = d, startMillis = startMs, durationMin = dur,
                        distanceM = if (type == WorkoutType.WALK) walk?.distanceM else distance.toNumberOrNull(),
                        inclinePct = if (type == WorkoutType.WALK) incline.toNumberOrNull() else null,
                        speedKmh = if (type == WorkoutType.WALK) walk?.speedKmh else null,
                        kcalSource = src, kcalEntered = entered, kcalNet = net, note = note.trim(),
                    ), onError)
                    onDismiss()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
