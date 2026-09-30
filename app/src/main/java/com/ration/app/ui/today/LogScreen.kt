package com.ration.app.ui.today

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.MeatChoice
import com.ration.app.domain.model.SlotType
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull

private val MULTIPLIERS = listOf(0.5, 0.75, 1.0, 1.25, 1.5)

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(nav: NavController, slotId: Long, vm: LogViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var slotType by remember(ui.slot) { mutableStateOf(ui.slot?.slot) }

    ui.done?.let { msg ->
        AlertDialog(onDismissRequest = { nav.popBackStack() }, confirmButton = { TextButton(onClick = { nav.popBackStack() }) { Text("OK") } },
            title = { Text("Записано") }, text = { Text(msg) })
    }

    Scaffold(topBar = { BackTopBar(ui.slot?.let { "Отметка: ${it.slot.label}" } ?: "Записать приём", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Блок") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Свой продукт") })
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (ui.slot == null) {
                    Text("Слот", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = slotType == null, onClick = { slotType = null }, label = { Text("Без слота") })
                        ui.daySlots.forEach { s ->
                            FilterChip(selected = slotType == s.slot, onClick = { slotType = s.slot }, label = { Text("${s.slot.label} ${TimeUtil.hm(s.minuteOfDay)}") })
                        }
                    }
                }
                if (tab == 0) BlockTab(vm, blocks, slotType, ui.slot?.blockId, ui.alternatives,
                    onBuild = { b -> nav.navigate("build/$slotId/${b?.id ?: 0}") }) { b, m, f, meat -> vm.logBlock(b, slotType, m, f, meat) }
                else CustomTab(vm, foods) { food, grams, portions -> vm.logCustom(food, slotType, grams, portions) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockTab(
    vm: LogViewModel, blocks: List<Block>, slot: SlotType?, plannedId: Long?, alternatives: List<Block>,
    onBuild: (Block?) -> Unit,
    onLog: (Block, Double, Boolean, MeatChoice?) -> Unit,
) {
    val planned = blocks.firstOrNull { it.id == plannedId }
    var selected by remember(planned) { mutableStateOf(planned) }
    var multiplier by rememberSaveable { mutableStateOf(1.0) }
    var fruit by remember(slot) { mutableStateOf(slot == SlotType.LUNCH) }
    var askMeat by remember { mutableStateOf(false) }

    if (planned != null) {
        SectionTitle("По плану")
        BlockRow(planned, selected?.id == planned.id) { selected = planned }
    }
    if (alternatives.isNotEmpty()) {
        SectionTitle("Альтернативы")
        alternatives.forEach { b -> BlockRow(b, selected?.id == b.id) { selected = b } }
    }
    SectionTitle("Каталог")
    val kinds = slot?.kinds
    blocks.filter { it.active && (kinds == null || it.kind in kinds) }.forEach { b -> BlockRow(b, selected?.id == b.id) { selected = b } }

    SectionTitle("Порция")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MULTIPLIERS.forEach { m -> FilterChip(selected = multiplier == m, onClick = { multiplier = m }, label = { Text("×" + TimeUtil.num(m, 2)) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = fruit, onCheckedChange = { fruit = it })
        Text("+ фрукт (яблоко или груша, +80 ккал)")
    }
    selected?.let { b ->
        Text("Итого: ${Math.round(b.kcal * multiplier + if (fruit) 80 else 0)} ккал · ${Math.round(b.protein * multiplier)} г белка",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
    Button(
        onClick = { selected?.let { if (vm.needsMeatQuestion(it)) askMeat = true else onLog(it, multiplier, fruit, null) } },
        enabled = selected != null, modifier = Modifier.fillMaxWidth(),
    ) { Text("Записать") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onBuild(selected) }, enabled = selected != null) { Text("Изменить состав") }
        TextButton(onClick = { onBuild(null) }) { Text("Собрать из продуктов") }
    }

    if (askMeat) {
        AlertDialog(
            onDismissRequest = { askMeat = false },
            title = { Text("Какое мясо?") },
            text = { Text("Для недельного счётчика красного мяса.") },
            confirmButton = { TextButton(onClick = { askMeat = false; selected?.let { onLog(it, multiplier, fruit, MeatChoice.CHICKEN) } }) { Text("Курица / индейка") } },
            dismissButton = { TextButton(onClick = { askMeat = false; selected?.let { onLog(it, multiplier, fruit, MeatChoice.RED) } }) { Text("Красное мясо") } },
        )
    }
}

@Composable
private fun BlockRow(b: Block, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text("${b.code} ${b.name}", fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text("${Math.round(b.kcal)} ккал · ${Math.round(b.protein)} г", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CustomTab(vm: LogViewModel, foods: List<CustomFood>, onLog: (CustomFood, Double?, Double?) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var chosen by remember { mutableStateOf<CustomFood?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var portion by rememberSaveable { mutableStateOf("") }
    var grams by rememberSaveable { mutableStateOf("") }
    var isBar by rememberSaveable { mutableStateOf(false) }
    var isProteinBar by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (foods.isNotEmpty()) {
        SectionTitle("Из справочника")
        foods.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable {
                chosen = f; name = f.name; kcal = TimeUtil.num(f.kcalPer100); protein = TimeUtil.num(f.proteinPer100)
                portion = f.portionGrams?.let { TimeUtil.num(it) } ?: ""; grams = portion; isBar = f.isBar; isProteinBar = f.isProteinBar
            }.padding(vertical = 6.dp)) {
                androidx.compose.material3.RadioButton(selected = chosen?.id == f.id, onClick = null)
                Text("${f.name} · ${TimeUtil.num(f.kcalPer100)} ккал / ${TimeUtil.num(f.proteinPer100)} г на 100 г")
            }
        }
    }
    SectionTitle("Новый или с этикетки")
    TextButton(onClick = {
        val t = clipboard.getText()?.text.orEmpty()
        val l = vm.parseLabel(t)
        if (l == null) error = "В буфере нет строки этикетки (формат 5.2)" else {
            chosen = null; name = l.name; kcal = TimeUtil.num(l.kcalPer100); protein = TimeUtil.num(l.proteinPer100)
            portion = l.portionGrams?.let { TimeUtil.num(it) } ?: ""; grams = portion.ifBlank { "100" }; error = null
        }
    }) { Text("Вставить строку этикетки из буфера") }
    OutlinedTextField(name, { name = it.take(120); chosen = null }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Ккал на 100 г", kcal, { kcal = it; chosen = null }, Modifier.weight(1f))
        NumberField("Белок на 100 г", protein, { protein = it; chosen = null }, Modifier.weight(1f))
    }
    NumberField("Размер порции, г", portion, { portion = it; chosen = null }, Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(isBar, { isBar = it; if (!it) isProteinBar = false }); Text("Батончик")
        Spacer(Modifier.padding(8.dp))
        Checkbox(isProteinBar, { isProteinBar = it; if (it) isBar = true }); Text("протеиновый")
    }
    NumberField("Съедено, г", grams, { grams = it }, Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        val k = kcal.toNumberOrNull(); val p = protein.toNumberOrNull(); val g = grams.toNumberOrNull()
        when {
            name.isBlank() -> error = "Введите название"
            k == null || k !in 0.0..900.0 -> error = "Ккал на 100 г: 0–900"
            p == null || p !in 0.0..100.0 -> error = "Белок на 100 г: 0–100"
            g == null || g <= 0 || g > 5000 -> error = "Укажите, сколько граммов съедено"
            else -> {
                val pg = portion.toNumberOrNull()
                val food = chosen ?: CustomFood(name = name.trim(), kcalPer100 = k, proteinPer100 = p, portionGrams = pg,
                    kcalPerPortion = pg?.let { k * it / 100 }, proteinPerPortion = pg?.let { p * it / 100 },
                    isBar = isBar, isProteinBar = isProteinBar)
                onLog(food, g, null)
            }
        }
    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Сохранить в справочник и записать") }
}
