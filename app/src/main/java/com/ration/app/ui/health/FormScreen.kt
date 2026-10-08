package com.ration.app.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.repo.HealthInsights
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.health.Condition
import com.ration.app.domain.health.FormReport
import com.ration.app.domain.health.HealthConstants
import com.ration.app.domain.health.ProfileCheck
import com.ration.app.domain.model.AppSettings
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs

data class FormUi(val days: Int = 14, val report: FormReport? = null, val missing: List<String> = emptyList(), val loading: Boolean = true)

@HiltViewModel
class FormViewModel @Inject constructor(
    private val insights: HealthInsights,
    private val settingsRepo: SettingsRepository,
    private val health: com.ration.app.data.repo.HealthRepository,
) : ViewModel() {
    val ui = MutableStateFlow(FormUi())
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun load(days: Int = ui.value.days) = viewModelScope.launch {
        ui.value = ui.value.copy(days = days, loading = true)
        val s = settingsRepo.current()
        val (_, missing) = ProfileCheck.profile(s, health.weightsList(), health.today())
        val r = if (missing.isEmpty()) insights.form(days) else null
        ui.value = FormUi(days, r, missing, loading = false)
    }

    /** Калибровка только предлагает PAL; применяется по кнопке «Принять». */
    fun acceptPal(pal: Double) = viewModelScope.launch {
        settingsRepo.update { it.copy(pal = Math.round(pal * 100) / 100.0) }
        load()
    }
}

private fun signed(v: Double, digits: Int = 2) = (if (v > 0) "+" else "") + TimeUtil.num(v, digits)

/** «Форма» (20.3): получено и потрачено, баланс, прогноз, фактический тренд, калибровка, рекомендации. */
@Composable
fun FormScreen(nav: NavController, vm: FormViewModel = hiltViewModel()) {
    val u by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    Scaffold(topBar = { BackTopBar("Форма", { nav.popBackStack() }) {
        TextButton(onClick = { nav.navigate("workouts") }) { Text("Тренировки") }
    } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 14, 30).forEach { d -> FilterChip(u.days == d, { vm.load(d) }, { Text("$d дней") }) }
                }
                if (u.missing.isNotEmpty()) {
                    InfoCard("Для расчёта недостаточно данных") {
                        Text(u.missing.joinToString(", "))
                        TextButton(onClick = { nav.navigate("settings") }) { Text("Открыть профиль в настройках") }
                    }
                }
                if (u.loading) Text("Считаю…", modifier = Modifier.padding(vertical = 8.dp))
            }
            val r = u.report ?: return@LazyColumn
            item {
                val food = r.days.filter { it.hasFood }
                InfoCard("Получено") {
                    Text("Среднее ${r.avgIntake?.let { Math.round(it).toString() + " ккал/день" } ?: "—"} по ${r.foodDays} дням с записями еды из ${r.days.size}")
                    if (r.foodDays < r.days.size) Text("Дни без записей еды в средние не входят.", style = MaterialTheme.typography.bodySmall)
                }
                InfoCard("Потрачено (в среднем по дням с едой)") {
                    if (food.isEmpty()) Text("—") else {
                        Text("Покой (BMR): ${Math.round(food.map { it.bmr }.average())} ккал")
                        Text("Бытовая активность (PAL ${TimeUtil.num(s.pal)}): ${Math.round(food.map { it.household }.average())} ккал")
                        Text("Тренировки: ${Math.round(food.map { it.workoutKcal }.average())} ккал")
                        Text("Всего: ${Math.round(r.avgTdee ?: 0.0)} ккал", fontWeight = FontWeight.SemiBold)
                    }
                }
                InfoCard("Баланс") {
                    Text(r.avgBalance?.let { "В среднем ${signed(it, 0)} ккал/день" + if (it < 0) " (дефицит)" else " (профицит)" } ?: "—",
                        fontWeight = FontWeight.SemiBold)
                    BalanceChart(r)
                }
                InfoCard("Прогноз и факт") {
                    Text("Прогноз по балансу: ${r.forecastKgWeek?.let { signed(it) + " кг/нед" } ?: "—"} (${HealthConstants.KCAL_PER_KG.toInt()} ккал = 1 кг)")
                    Text("Фактический тренд (среднее за 7 дней, регрессия 14 дней): ${r.trendKgWeek?.let { signed(it) + " кг/нед" } ?: "мало взвешиваний"}")
                    Text("Цель ${TimeUtil.num(s.targetWeight)} кг: " + (r.weeksToTarget?.let { "≈ ${Math.round(it)} нед." } ?: "срок не оценить при текущем темпе"))
                }
                val c = r.calibration
                InfoCard("Калибровка") {
                    if (c == null) {
                        Text("Нужно 14+ дней, записи еды не реже 70% дней и не меньше 6 взвешиваний. Выберите период 14 или 30 дней.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Расход по весу и еде: ${Math.round(c.observedTdee)} ккал · по формуле: ${Math.round(c.calcTdee)} ккал " +
                            "(${signed((c.observedTdee - c.calcTdee) / c.calcTdee * 100, 0)}%)")
                        if (c.suggestedPal != null && abs(c.suggestedPal - s.pal) >= 0.01) {
                            Text("Предлагается коэффициент активности ${TimeUtil.num(c.suggestedPal)} вместо ${TimeUtil.num(s.pal)}.")
                            Button(onClick = { vm.acceptPal(c.suggestedPal) }) { Text("Принять") }
                        } else Text("Расхождение в пределах 10% — коэффициент оставить.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                SectionTitle("Рекомендации")
                if (r.advice.isEmpty()) Text("Замечаний нет.")
            }
            items(r.advice) { a ->
                InfoCard {
                    Text(a.text, fontWeight = FontWeight.Medium)
                    Text("Почему: ${a.why}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Text("Оценка расхода ±20–30%: формулы Миффлина — Сан Жеора, ACSM, Compendium. ${Condition.DISCLAIMER}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

/** Столбики баланса по дням: вниз — дефицит (зелёный), вверх — профицит (красный), серым — дни без еды. */
@Composable
private fun BalanceChart(r: FormReport) {
    val days = r.days
    if (days.isEmpty()) return
    val maxAbs = days.filter { it.hasFood }.maxOfOrNull { abs(it.balance) }?.coerceAtLeast(100.0) ?: 100.0
    val grey = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 6.dp)) {
        val mid = size.height / 2
        val w = size.width / days.size
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid))
        days.forEachIndexed { i, d ->
            val x = i * w + w * 0.15f
            if (!d.hasFood) { drawRect(grey, Offset(x, mid - 2f), Size(w * 0.7f, 4f)); return@forEachIndexed }
            val h = (abs(d.balance) / maxAbs * (mid - 4)).toFloat()
            val c = if (d.balance < 0) Color(0xFF2E7D32) else Color(0xFFC62828)
            if (d.balance < 0) drawRect(c, Offset(x, mid), Size(w * 0.7f, h)) else drawRect(c, Offset(x, mid - h), Size(w * 0.7f, h))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(TimeUtil.dateShort(LocalDate.ofEpochDay(days.first().day)), style = MaterialTheme.typography.labelSmall)
        Text("±${Math.round(maxAbs)} ккал", style = MaterialTheme.typography.labelSmall)
        Text(TimeUtil.dateShort(LocalDate.ofEpochDay(days.last().day)), style = MaterialTheme.typography.labelSmall)
    }
}
