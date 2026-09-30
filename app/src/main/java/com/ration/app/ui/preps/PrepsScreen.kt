package com.ration.app.ui.preps

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.inventory.Shortage
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.PlainTopBar
import com.ration.app.ui.components.RecipeSteps
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.ui.pantry.PrepRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrepsViewModel @Inject constructor(
    private val inventory: InventoryRepository,
    catalog: CatalogRepository,
) : ViewModel() {
    val templates = catalog.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preps = inventory.activePreps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val products = catalog.products.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val shortages = MutableStateFlow<List<Shortage>>(emptyList())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    val today get() = inventory.today()

    fun refreshShortages(list: List<PrepTemplate>) = viewModelScope.launch { shortages.value = inventory.shortagesFor(list) }

    fun cook(t: PrepTemplate, batches: Double, yields: Map<String, Double?>, overrides: Map<Long, Double> = emptyMap()) = viewModelScope.launch {
        val r = inventory.cook(t, batches, yields, overrides)
        _messages.tryEmit("«${t.name}» готово: " + r.preps.joinToString(", ") { "${it.name} ${TimeUtil.num(it.portionsTotal)} порц." } +
            if (r.shortages.isNotEmpty()) ". Не хватило на складе: " + r.shortages.joinToString(", ") { "${it.label} ${TimeUtil.num(it.missing)} ${it.unit.label}" } + " — записано всё равно." else "")
    }

    fun saturdayBatch(list: List<PrepTemplate>) = viewModelScope.launch {
        list.filter { it.inSaturdayBatch }.forEach { t -> inventory.cook(t, 1.0) }
        _messages.tryEmit("Субботняя партия записана")
    }

    fun discard(p: Prep) = viewModelScope.launch { inventory.discardPrep(p) }
}

@Composable
fun PrepsScreen(nav: NavController, vm: PrepsViewModel = hiltViewModel()) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val preps by vm.preps.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var cooking by remember { mutableStateOf<PrepTemplate?>(null) }
    var batchConfirm by remember { mutableStateOf(false) }
    val byId = products.associateBy { it.id }

    Scaffold(topBar = { PlainTopBar("Заготовки") }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { batchConfirm = true }) { Text("Субботняя партия") }
                    OutlinedButton(onClick = { nav.navigate("prep_checklist") }) { Text("Чек-лист на субботу") }
                }
                SectionTitle("В наличии")
                if (preps.isEmpty()) Text("Нет заготовок.", style = MaterialTheme.typography.bodySmall)
            }
            items(preps, key = { it.id }) { p -> PrepRow(p, vm.today) { vm.discard(p) } }
            item { SectionTitle("Шаблоны") }
            items(templates, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold)
                        Text("Вход: " + t.inputs.joinToString(", ") { i -> "${byId[i.productId]?.name ?: "?"} ${TimeUtil.num(i.qty)} ${byId[i.productId]?.unit?.label ?: ""}" + if (i.variable) " (≈)" else "" },
                            style = MaterialTheme.typography.bodySmall)
                        Text("Выход: " + t.outputs.joinToString(", ") { o -> "${o.name} ${TimeUtil.num(o.defaultPortions)} порц." + (o.portionGrams?.let { " по ${TimeUtil.num(it)} г" } ?: "") } +
                            " · хранение ${t.shelfDays} сут.", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { cooking = t }, modifier = Modifier.padding(top = 8.dp)) { Text("Приготовил") }
                    }
                }
            }
        }
    }

    cooking?.let { t -> CookDialog(t, byId, onDismiss = { cooking = null }) { b, y, o -> vm.cook(t, b, y, o); cooking = null } }
    if (batchConfirm) {
        val batch = templates.filter { it.inSaturdayBatch }
        val totals = mutableMapOf<Long, Double>()
        batch.forEach { t -> t.inputs.forEach { totals[it.productId] = (totals[it.productId] ?: 0.0) + it.qty } }
        AlertDialog(
            onDismissRequest = { batchConfirm = false },
            title = { Text("Субботняя партия") },
            text = {
                Column {
                    Text(batch.joinToString(", ") { it.name })
                    Text("Со склада спишется:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    totals.forEach { (pid, q) -> Text("• ${byId[pid]?.name ?: "?"} — ${TimeUtil.num(q)} ${byId[pid]?.unit?.label ?: ""}") }
                    Text("Если на складе меньше, запись всё равно будет сделана.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.saturdayBatch(templates); batchConfirm = false }) { Text("Записать") } },
            dismissButton = { TextButton(onClick = { batchConfirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun CookDialog(t: PrepTemplate, products: Map<Long, Product>, onDismiss: () -> Unit, onCook: (Double, Map<String, Double?>, Map<Long, Double>) -> Unit) {
    var batches by remember { mutableStateOf("1") }
    val yields = remember { mutableStateMapOf<String, String>() }
    val overrides = remember { mutableStateMapOf<Long, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Приготовил: ${t.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("Число замесов", batches, { batches = it })
                t.inputs.filter { it.variable }.forEach { i ->
                    NumberField("${products[i.productId]?.name ?: "?"}, на замес", overrides[i.productId] ?: TimeUtil.num(i.qty), { overrides[i.productId] = it },
                        suffix = products[i.productId]?.unit?.label)
                }
                t.outputs.filter { it.userEntersYield }.forEach { o ->
                    NumberField("${o.name}: вес готового, г (необязательно)", yields[o.key] ?: "", { yields[o.key] = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val b = batches.toNumberOrNull()?.takeIf { it > 0 && it <= 20 } ?: 1.0
                onCook(b, yields.mapValues { it.value.toNumberOrNull() }, overrides.mapNotNull { (k, v) -> v.toNumberOrNull()?.let { k to it } }.toMap())
            }) { Text("Записать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun PrepChecklistScreen(nav: NavController, vm: PrepsViewModel = hiltViewModel()) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val shortages by vm.shortages.collectAsStateWithLifecycle()
    val batch = templates.filter { it.inSaturdayBatch }
    LaunchedEffect(batch.size) { if (batch.isNotEmpty()) vm.refreshShortages(batch) }
    val done = remember { mutableStateMapOf<String, Boolean>() }
    Scaffold(topBar = { BackTopBar("Заготовка на субботу", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                SectionTitle("Нехватка продуктов")
                if (shortages.isEmpty()) Text("Всего хватает.", style = MaterialTheme.typography.bodyMedium)
                shortages.forEach { Text("• ${it.label}: не хватает ${TimeUtil.num(it.missing)} ${it.unit.label}", color = MaterialTheme.colorScheme.error) }
            }
            batch.forEach { t ->
                item {
                    SectionTitle(t.name)
                    t.steps.forEachIndexed { i, step ->
                        val key = "${t.id}:$i"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(done[key] == true, { done[key] = it })
                            Column(Modifier.weight(1f)) { RecipeSteps(listOf(step)) }
                        }
                    }
                }
            }
        }
    }
}
