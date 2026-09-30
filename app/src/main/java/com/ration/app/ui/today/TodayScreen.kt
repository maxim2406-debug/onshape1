package com.ration.app.ui.today

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
import androidx.compose.material3.FilterChip
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
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.rules.DayRules
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.PlainTopBar
import com.ration.app.ui.components.RangeProgress
import com.ration.app.ui.components.SectionTitle
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(nav: NavController, vm: TodayViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) } }
    val s = st.settings
    Scaffold(
        topBar = { PlainTopBar("Сегодня, ${TimeUtil.dateRu(LocalDate.ofEpochDay(st.day))}") },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Тип дня:", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.CenterVertically))
                    DayType.entries.forEach { t ->
                        FilterChip(selected = st.plan?.dayType == t, onClick = { vm.setType(t) }, label = { Text(t.label) })
                    }
                    FilterChip(selected = st.plan?.road == true, onClick = { vm.setRoad(st.plan?.road != true) }, label = { Text("В · дорога") })
                }
                st.plan?.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.padding(4.dp))
                RangeProgress("Ккал", st.kcal, s.kcalMin, s.kcalMax, "ккал")
                RangeProgress("Белок", st.protein, s.proteinMin, s.proteinMax, "г")
                Text("Вода ${Math.round(st.waterMl)} мл из ${s.waterGoalMl} · эспрессо ${st.espresso}",
                    style = MaterialTheme.typography.bodySmall)
                if (DayRules.isRedDay(st.kcal, s)) Text("● День выше нормы больше чем на 10%", color = MaterialTheme.colorScheme.error)
            }
            item {
                SectionTitle("Быстро")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = vm::water, label = { Text("Вода +250 мл") })
                    AssistChip(onClick = vm::espresso, label = { Text("Эспрессо") })
                    AssistChip(onClick = { vm.bar(false) }, label = { Text("Злаковый батончик") })
                    AssistChip(onClick = { vm.bar(true) }, label = { Text("Протеиновый батончик") })
                    AssistChip(onClick = vm::alcohol, label = { Text("Алкоголь") })
                    AssistChip(onClick = { nav.navigate("log/0") }, label = { Text("Свой продукт") })
                    AssistChip(onClick = { nav.navigate("build/0/0") }, label = { Text("Собрать из продуктов") })
                    AssistChip(onClick = { nav.navigate("cook") }, label = { Text("Что приготовить") })
                }
            }
            if (st.warnings.isNotEmpty()) item {
                SectionTitle("Предупреждения")
                InfoCard(container = MaterialTheme.colorScheme.errorContainer) {
                    st.warnings.forEach { Text("• ${it.text}", color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
            item { SectionTitle("Приёмы пищи") }
            items(st.slots, key = { it.id }) { slot ->
                SlotCard(slot, st, onEat = { vm.eatPlanned(slot) }, onChange = { nav.navigate("log/${slot.id}") },
                    onSkip = { vm.skip(slot) }, onOpenBlock = { id -> nav.navigate("block/$id") })
            }
            item {
                val extra = if (st.slots.isNotEmpty()) {
                    val planned = st.slots.sumOf { sl -> (sl.blockId?.let { st.blocks[it]?.kcal } ?: 0.0) * sl.multiplier }
                    "План: ${Math.round(planned)} ккал без фрукта, ${Math.round(planned + s.fruitKcal)} с фруктом к обеду"
                } else ""
                Text(extra, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (st.logs.isNotEmpty() || st.quick.isNotEmpty()) item { SectionTitle("Журнал дня") }
            items(st.logs, key = { "l${it.id}" }) { l ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name + if (l.multiplier != 1.0) " ×${TimeUtil.num(l.multiplier, 2)}" else "")
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
private fun SlotCard(slot: PlannedSlot, st: TodayState, onEat: () -> Unit, onChange: () -> Unit, onSkip: () -> Unit, onOpenBlock: (Long) -> Unit) {
    val block = slot.blockId?.let { st.blocks[it] }
    val closed = slot.status != SlotStatus.PLANNED
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (closed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TimeUtil.hm(slot.minuteOfDay), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(slot.slot.label + if (slot.optional) " (необязательно)" else "", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(slot.status.label, style = MaterialTheme.typography.labelMedium,
                    color = if (slot.status == SlotStatus.SKIPPED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            if (block != null) {
                TextButton(onClick = { onOpenBlock(block.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("${block.code} ${block.name}", style = MaterialTheme.typography.bodyLarge)
                }
                Text("${Math.round(block.kcal * slot.multiplier)} ккал · ${Math.round(block.protein * slot.multiplier)} г белка" +
                    (if (slot.multiplier != 1.0) " · порция ×${TimeUtil.num(slot.multiplier, 2)}" else "") +
                    (if (slot.needsPurchase) " · нужно купить" else ""),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Блок не назначен", style = MaterialTheme.typography.bodyMedium)
            }
            if (!closed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = onEat, enabled = block != null) { Text("Съел по плану") }
                    OutlinedButton(onClick = onChange) { Text("Изменить") }
                    TextButton(onClick = onSkip) { Text("Пропустил") }
                }
            }
        }
    }
}
