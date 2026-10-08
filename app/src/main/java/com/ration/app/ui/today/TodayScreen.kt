package com.ration.app.ui.today

import com.ration.app.domain.health.HealthWarning
import com.ration.app.data.repo.FormHint
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.MealSlotStatus
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.rules.DayRules
import com.ration.app.ui.components.GoalProgress
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.PlainTopBar
import com.ration.app.ui.components.SectionTitle
import java.time.LocalDate

/**
 * «Сегодня» (19.2, 19.8): три цели дня, быстрое меню над шестью карточками приёмов, у каждой карточки ровно две кнопки.
 * Без типов дня и без рекомендуемых блюд.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(nav: NavController, vm: TodayViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) } }
    val s = st.settings
    val hint by vm.formHint.collectAsStateWithLifecycle()
    val healthWarnings by vm.healthWarnings.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHealth() }
    LaunchedEffect(s) { vm.refreshHealth() }
    Scaffold(
        topBar = { PlainTopBar("Сегодня, ${TimeUtil.dateRu(LocalDate.ofEpochDay(st.day))}") },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            items(healthWarnings, key = { "hw" + it.key }) { w -> HealthWarningCard(w) { vm.dismissWarning(w.key) } }
            item {
                val t = hint?.target
                GoalProgress("Ккал", st.kcal, s.kcalTarget, "ккал", s.kcalMin..s.kcalMax, marker = t?.kcal)
                GoalProgress("Белок", st.protein, s.proteinTarget, "г", s.proteinMin..s.proteinMax,
                    marker = t?.proteinLow?.toDouble(), markerHigh = t?.proteinHigh?.toDouble())
                FormHintRow(hint, s.kcalTarget, s.proteinTarget, onApply = vm::applyForm, onProfile = { nav.navigate("settings") })
                GoalProgress("Вода", st.waterMl, s.waterGoalMl, "мл")
                Text("Эспрессо: ${st.espresso}", style = MaterialTheme.typography.bodySmall)
                if (DayRules.isRedDay(st.kcal, s)) Text("● День выше нормы больше чем на 10%", color = MaterialTheme.colorScheme.error)
            }
            if (st.warnings.isNotEmpty()) item {
                SectionTitle("Предупреждения")
                InfoCard(container = MaterialTheme.colorScheme.errorContainer) {
                    st.warnings.forEach { Text("• ${it.text}", color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
            item {
                SectionTitle("Приёмы пищи")
                // Быстрое меню — без изменений (19.8), строкой над карточками; «Что приготовить» перенесено в меню конструктора (19.4)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = vm::water, label = { Text("Вода +250 мл") })
                    AssistChip(onClick = vm::espresso, label = { Text("Эспрессо") })
                    AssistChip(onClick = { vm.bar(false) }, label = { Text("Злаковый батончик") })
                    AssistChip(onClick = { vm.bar(true) }, label = { Text("Протеиновый батончик") })
                    AssistChip(onClick = vm::alcohol, label = { Text("Алкоголь") })
                    AssistChip(onClick = { nav.navigate("log/0") }, label = { Text("Свой продукт") })
                    AssistChip(onClick = { nav.navigate("build/0/0") }, label = { Text("Собрать из продуктов") })
                }
            }
            items(st.cards, key = { it.slot.name }) { c ->
                SlotCard(
                    c,
                    onSkip = { vm.skip(c.slot) },
                    onUnskip = { vm.unskip(c.slot) },
                    onBuild = { c.planned?.let { nav.navigate("build/${it.id}/0") } },
                    onEdit = { c.planned?.let { nav.navigate("build/${it.id}/0?edit=true") } },
                )
            }
            if (st.logs.isNotEmpty() || st.quick.isNotEmpty()) item { SectionTitle("Журнал дня") }
            items(st.logs, key = { "l${it.id}" }) { l ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((l.slot?.let { "${it.short} · " } ?: "") + l.name + if (l.multiplier != 1.0) " ×${TimeUtil.num(l.multiplier, 2)}" else "")
                        Text("${TimeUtil.hm(TimeUtil.minuteOf(TimeUtil.toLocal(l.atMillis, java.time.ZoneId.systemDefault()).toLocalTime()))} · " +
                            "${Math.round(l.kcal)} ккал · ${Math.round(l.protein)} г" + if (l.withFruit) " · + фрукт" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.undo(l) }) { Icon(Icons.Filled.Close, contentDescription = "Отменить запись") }
                }
            }
            items(st.quick, key = { "q${it.id}" }) { q ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${q.type.label}${if (q.type == QuickType.WATER) " ${Math.round(q.amount)} мл" else ""}", Modifier.weight(1f))
                    TextButton(onClick = { vm.undoQuick(q) }) { Text("Убрать") }
                }
            }
            item { Spacer(Modifier.padding(24.dp)) }
        }
    }
}

@Composable
private fun SlotCard(c: SlotCardState, onSkip: () -> Unit, onUnskip: () -> Unit, onBuild: () -> Unit, onEdit: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (c.status) {
                MealSlotStatus.EMPTY -> MaterialTheme.colorScheme.secondaryContainer
                MealSlotStatus.SKIPPED -> MaterialTheme.colorScheme.surfaceVariant
                MealSlotStatus.LOGGED -> MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.slot.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(c.planned?.let { TimeUtil.hm(it.minuteOfDay) } ?: "", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    c.status.label + if (c.status == MealSlotStatus.SKIPPED && c.autoSkipped) " (авто)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = when (c.status) {
                        MealSlotStatus.SKIPPED -> MaterialTheme.colorScheme.error
                        MealSlotStatus.LOGGED -> MaterialTheme.colorScheme.primary
                        MealSlotStatus.EMPTY -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (c.status == MealSlotStatus.LOGGED) {
                c.logs.forEach { l ->
                    if (l.items.isNotEmpty()) {
                        l.items.filter { !it.untracked || it.qty > 0 }.forEach { i ->
                            Text("• ${i.name} — ${TimeUtil.num(i.qty)} ${i.unit.label} · ${Math.round(i.kcal)} ккал · ${TimeUtil.num(i.protein)} г",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (l.withFruit) Text("• фрукт", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("• ${l.name} · ${Math.round(l.kcal)} ккал · ${Math.round(l.protein)} г", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Итог: ${Math.round(c.kcal)} ккал · ${Math.round(c.protein)} г белка", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                when (c.status) {
                    MealSlotStatus.EMPTY -> {
                        OutlinedButton(onClick = onSkip) { Text("Пропустить") }
                        FilledTonalButton(onClick = onBuild, enabled = c.planned != null) { Text("Собрать из продуктов") }
                    }
                    MealSlotStatus.SKIPPED -> {
                        OutlinedButton(onClick = onUnskip) { Text("Отменить пропуск") }
                        FilledTonalButton(onClick = onBuild, enabled = c.planned != null) { Text("Собрать из продуктов") }
                    }
                    MealSlotStatus.LOGGED -> {
                        OutlinedButton(onClick = onBuild, enabled = c.planned != null) { Text("Добавить ещё") }
                        FilledTonalButton(onClick = onEdit, enabled = c.planned != null) { Text("Изменить") }
                    }
                }
            }
        }
    }
}

/** 20.5: «По форме» — ориентир на сегодня (метка на шкалах); цель меняется только кнопкой. */
@Composable
private fun FormHintRow(hint: FormHint?, kcalGoal: Int, proteinGoal: Int, onApply: () -> Unit, onProfile: () -> Unit) {
    hint ?: return
    val t = hint.target
    if (t == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Для рекомендации не хватает: ${hint.missing.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onProfile) { Text("Профиль") }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("По форме: ${Math.round(t.kcal)} ккал (расход ${Math.round(t.tdee)} − дефицит ${Math.round(t.deficit)}), белок ${t.proteinLow}–${t.proteinHigh} г",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
        val same = Math.round(t.kcal / 10.0).toInt() * 10 == kcalGoal && proteinGoal in t.proteinLow..t.proteinHigh
        if (!same) TextButton(onClick = onApply) { Text("Сделать целью") }
    }
}

/** 20.6: срочное — красная карточка с текстом про 101; устойчивое ухудшение — жёлтая, со значениями по 3 окнам. */
@Composable
private fun HealthWarningCard(w: HealthWarning, onDismiss: () -> Unit) {
    val urgent = w.urgent
    InfoCard(w.title, container = if (urgent) Color(0xFFFFCDD2) else Color(0xFFFFF59D)) {
        if (w.windows.size == 3) Text("3 недели назад: ${w.windows[0]} → 2 недели: ${w.windows[1]} → последняя неделя: ${w.windows[2]}",
            style = MaterialTheme.typography.bodySmall, color = Color.Black)
        Text(w.advice, style = MaterialTheme.typography.bodySmall, color = Color.Black)
        if (!urgent) TextButton(onClick = onDismiss) { Text("Скрыть на 3 дня") }
    }
}
