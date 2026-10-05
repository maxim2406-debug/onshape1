package com.ration.app.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.report.DayNutrition
import com.ration.app.domain.report.NutritionStats
import com.ration.app.domain.report.NutritionSummary
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class NutritionUi(val days: Int = 14, val summary: NutritionSummary? = null, val settings: AppSettings = AppSettings())

/** Статистика калорий и белка (только по журналу питания; календарь и другие разрешения не нужны). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NutritionViewModel @Inject constructor(plans: PlanRepository, meals: MealRepository, settings: SettingsRepository) : ViewModel() {
    private val today = plans.today()
    val period = MutableStateFlow(14)

    val ui = period.flatMapLatest { n ->
        combine(meals.observeRange(today - n + 1, today), settings.settings) { logs, s ->
            NutritionUi(n, NutritionStats.summary(logs, today - n + 1, today, s), s)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NutritionUi())
}

@Composable
fun NutritionScreen(nav: NavController, vm: NutritionViewModel = hiltViewModel()) {
    val u by vm.ui.collectAsStateWithLifecycle()
    val s = u.settings
    Scaffold(topBar = { BackTopBar("Калории и белок", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 14, 30, 90).forEach { n -> FilterChip(u.days == n, { vm.period.value = n }, { Text("$n дн.") }) }
                }
            }
            val r = u.summary
            if (r == null || r.loggedDays == 0) {
                item { InfoCard { Text("За ${u.days} дн. записей нет. Статистика появится после первых записей приёмов пищи.") } }
                return@LazyColumn
            }
            item {
                InfoCard("Средние за день") {
                    Text("Ккал: ${Math.round(r.avgKcal ?: 0.0)} (цель ${s.kcalTarget}, норма ${s.kcalMin}–${s.kcalMax})", fontWeight = FontWeight.SemiBold)
                    Text("Белок: ${Math.round(r.avgProtein ?: 0.0)} г (цель ${s.proteinTarget}, норма ${s.proteinMin}–${s.proteinMax})", fontWeight = FontWeight.SemiBold)
                    r.proteinKcalPct?.let { Text("Белок даёт ${Math.round(it)}% калорий", style = MaterialTheme.typography.bodySmall) }
                    Text("Дней с записями: ${r.loggedDays} из ${u.days}", style = MaterialTheme.typography.bodySmall)
                }
                InfoCard("Дни") {
                    Text("Ккал в норме: ${r.kcalInRange} из ${r.loggedDays}")
                    Text("Белок не ниже ${s.proteinMin} г: ${r.proteinOk} из ${r.loggedDays}")
                    Text("Перебор больше 10%: ${r.redDays}", color = if (r.redDays > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("Серия дней с нормой белка: ${r.proteinStreak}")
                    r.minKcal?.let { Text("Меньше всего: ${day(it)} — ${Math.round(it.kcal)} ккал", style = MaterialTheme.typography.bodySmall) }
                    r.maxKcal?.let { Text("Больше всего: ${day(it)} — ${Math.round(it.kcal)} ккал", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                SectionTitle("Ккал по дням")
                DayBars(r.days, { it.kcal }, MaterialTheme.colorScheme.primary,
                    lines = listOf(s.kcalMin.toDouble() to Color(0xFF2E7D32), s.kcalMax.toDouble() to MaterialTheme.colorScheme.error),
                    over = s.kcalMax.toDouble())
                Legend("— норма ${s.kcalMin}–${s.kcalMax}; столбец красный при переборе")
                SectionTitle("Белок по дням, г")
                DayBars(r.days, { it.protein }, Color(0xFF6A1B9A),
                    lines = listOf(s.proteinMin.toDouble() to Color(0xFF2E7D32)), under = s.proteinMin.toDouble())
                Legend("— минимум ${s.proteinMin} г; столбец бледный, если ниже")
            }
            if (r.weeks.size > 1) {
                item { SectionTitle("По неделям") }
                items(r.weeks) { w ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("с ${TimeUtil.dateShort(w.weekStart)} (${w.days} дн.)", Modifier.weight(1f))
                        Text("${Math.round(w.avgKcal)} ккал · ${Math.round(w.avgProtein)} г")
                    }
                    HorizontalDivider()
                }
            }
            item { SectionTitle("По приёмам (в среднем за день)") }
            items(r.bySlot) { sh ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sh.slot?.title ?: "Без слота", Modifier.weight(1f))
                    Text("${Math.round(sh.avgKcal)} ккал · ${Math.round(sh.avgProtein)} г · ${Math.round(sh.sharePct)}%")
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun day(d: DayNutrition) = TimeUtil.dateRu(LocalDate.ofEpochDay(d.day))

@Composable
private fun Legend(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Столбцы по дням с горизонтальными линиями целей. Дни без записей — пустое место.
 * [over] — значение выше которого столбец красный; [under] — ниже которого столбец бледный.
 */
@Composable
private fun DayBars(
    days: List<DayNutrition>, value: (DayNutrition) -> Double, color: Color,
    lines: List<Pair<Double, Color>>, over: Double? = null, under: Double? = null,
) {
    val max = (days.maxOfOrNull(value) ?: 0.0).coerceAtLeast(lines.maxOfOrNull { it.first } ?: 0.0) * 1.1
    val error = MaterialTheme.colorScheme.error
    val axis = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 4.dp)) {
        if (max <= 0 || days.isEmpty()) return@Canvas
        val slot = size.width / days.size
        val bar = (slot * 0.7f).coerceAtLeast(1f)
        days.forEachIndexed { i, d ->
            if (!d.hasLogs) return@forEachIndexed
            val v = value(d)
            val h = (v / max * size.height).toFloat()
            val c = when {
                over != null && v > over -> error
                under != null && v < under -> color.copy(alpha = 0.4f)
                else -> color
            }
            drawRect(c, topLeft = Offset(i * slot + (slot - bar) / 2, size.height - h), size = Size(bar, h))
        }
        lines.forEach { (y, c) ->
            val yy = (size.height - y / max * size.height).toFloat()
            drawLine(c, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
    }
    Row(Modifier.fillMaxWidth()) {
        Text(TimeUtil.dateShort(LocalDate.ofEpochDay(days.first().day)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(TimeUtil.dateShort(LocalDate.ofEpochDay(days.last().day)), style = MaterialTheme.typography.labelSmall)
    }
}
