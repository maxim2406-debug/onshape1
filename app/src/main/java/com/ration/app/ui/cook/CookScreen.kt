package com.ration.app.ui.cook

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.CookRepository
import com.ration.app.data.repo.Drafts
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.cook.SuggestRequest
import com.ration.app.domain.cook.Suggestion
import com.ration.app.domain.model.CookMethod
import com.ration.app.domain.model.CookSlot
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.ui.today.message
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MethodFilter(val label: String, val methods: Set<CookMethod>?) {
    ALL("Любой способ", null),
    SOUSVIDE("Только сувид", setOf(CookMethod.SOUSVIDE)),
    NINJA("Только Ninja", setOf(CookMethod.NINJA_GRILL, CookMethod.NINJA_AIRFRY)),
    NO_STOVE("Без плиты", CookMethod.entries.filter { !it.usesStove }.toSet()),
}

data class CookUi(
    val loading: Boolean = true,
    val slot: CookSlot = CookSlot.DINNER,
    val kcal: Double = 600.0,
    val protein: Double = 40.0,
    val onlyOwn: Boolean = false,
    val allowProcessed: Boolean = false,
    val respectWeekly: Boolean = true,
    val expiringFirst: Boolean = false,
    val method: MethodFilter = MethodFilter.ALL,
    val results: List<Suggestion> = emptyList(),
    val reason: String? = null,
    val leftovers: List<Pair<String, Suggestion>> = emptyList(),
    val today: Long = 0,
    val ms: Long = 0,
)

@HiltViewModel
class CookViewModel @Inject constructor(
    savedState: androidx.lifecycle.SavedStateHandle,
    private val cook: CookRepository,
    private val catalog: CatalogRepository,
    private val drafts: Drafts,
) : ViewModel() {
    val ui = MutableStateFlow(CookUi())
    /** Слот из конструктора (19.4); без него — ближайший неотмеченный. Запись идёт в этот слот. */
    private var targetSlot: com.ration.app.domain.model.SlotType? =
        savedState.get<String>("slot")?.let { com.ration.app.domain.model.SlotType.fromStored(it) }
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            val slotType = targetSlot ?: cook.nearestOpenSlot()
            targetSlot = slotType
            val (k, p) = cook.defaultTargets()
            ui.value = ui.value.copy(slot = CookSlot.of(slotType), kcal = k, protein = p)
            run()
        }
    }

    private suspend fun request(u: CookUi): SuggestRequest = cook.baseRequest(u.slot, u.kcal, u.protein).copy(
        onlyOwn = u.onlyOwn, allowProcessed = u.allowProcessed, respectWeekly = u.respectWeekly,
        expiringFirst = u.expiringFirst, methods = u.method.methods,
    )

    fun run() = viewModelScope.launch {
        val u = ui.value.copy(loading = true)
        ui.value = u
        val t0 = System.currentTimeMillis()
        val r = cook.suggest(request(u))
        ui.value = ui.value.copy(loading = false, results = r.suggestions, reason = r.reason, ms = System.currentTimeMillis() - t0,
            today = com.ration.app.domain.TimeUtil.let { java.time.LocalDate.now().toEpochDay() })
    }

    fun loadLeftovers() = viewModelScope.launch {
        ui.value = ui.value.copy(loading = true)
        val lo = cook.leftovers(request(ui.value))
        ui.value = ui.value.copy(loading = false, leftovers = lo)
    }

    fun set(f: (CookUi) -> CookUi) { ui.value = f(ui.value); run() }

    fun setSlot(slot: CookSlot) = viewModelScope.launch {
        ui.value = ui.value.copy(slot = slot)
        targetSlot = slot.slotType
        run()
    }

    fun record(s: Suggestion) = viewModelScope.launch {
        val out = cook.record(s, targetSlot ?: ui.value.slot.slotType)
        _messages.tryEmit(out.message() ?: "Записано: ${s.title}")
        run()
    }

    fun openInBuilder(s: Suggestion) {
        drafts.builderItems = s.items.map { it.toMealItem() }
        drafts.builderTitle = s.title
    }

    fun saveAsBlock(s: Suggestion, name: String) = viewModelScope.launch {
        val b = cook.saveAsBlock(name.ifBlank { s.title }, ui.value.slot, s.items.map { it.toMealItem() })
        _messages.tryEmit("Сохранён свой блок ${b.code}")
    }

    fun saveAsRecipe(s: Suggestion) = viewModelScope.launch {
        val r = s.recipe ?: return@launch
        catalog.saveUserRecipe(com.ration.app.domain.cook.Cookbook.userCopy(r, "user-" + System.currentTimeMillis()))
        _messages.tryEmit("Рецепт сохранён как свой — его можно править")
    }

    fun hide(s: Suggestion) = viewModelScope.launch {
        cook.hide(s.key)
        ui.value = ui.value.copy(results = ui.value.results - s)
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CookScreen(nav: NavController, vm: CookViewModel = hiltViewModel()) {
    val u by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var howTo by remember { mutableStateOf<Suggestion?>(null) }
    var blockName by remember { mutableStateOf<Suggestion?>(null) }
    var kcalText by remember(u.kcal) { mutableStateOf(TimeUtil.num(u.kcal)) }
    var proteinText by remember(u.protein) { mutableStateOf(TimeUtil.num(u.protein)) }

    Scaffold(topBar = { BackTopBar("Что приготовить", { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Подбор") })
                Tab(tab == 1, { tab = 1; vm.loadLeftovers() }, text = { Text("Использовать остатки") })
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (tab == 0) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CookSlot.entries.forEach { s -> FilterChip(u.slot == s, { vm.setSlot(s) }, { Text("${s.letter} ${s.label}") }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NumberField("Цель, ккал", kcalText, { kcalText = it }, Modifier.weight(1f))
                            NumberField("Белок, г", proteinText, { proteinText = it }, Modifier.weight(1f))
                            TextButton(onClick = {
                                val k = kcalText.toNumberOrNull(); val p = proteinText.toNumberOrNull()
                                if (k != null && p != null && k in 50.0..2500.0 && p in 0.0..200.0) vm.set { it.copy(kcal = k, protein = p) }
                            }) { Text("OK") }
                        }
                        Text("Допуск: ккал ±10%, белок не ниже цели −10%.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(u.onlyOwn, { vm.set { it.copy(onlyOwn = !it.onlyOwn) } }, { Text("Только свои блоки и рецепты") })
                            FilterChip(u.allowProcessed, { vm.set { it.copy(allowProcessed = !it.allowProcessed) } }, { Text("Разрешить готовое") })
                            FilterChip(u.respectWeekly, { vm.set { it.copy(respectWeekly = !it.respectWeekly) } }, { Text("Недельные лимиты") })
                            FilterChip(u.expiringFirst, { vm.set { it.copy(expiringFirst = !it.expiringFirst) } }, { Text("Сначала скоро испортится") })
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MethodFilter.entries.forEach { m -> FilterChip(u.method == m, { vm.set { it.copy(method = m) } }, { Text(m.label) }) }
                        }
                        if (u.loading) Row(Modifier.padding(16.dp)) { CircularProgressIndicator() }
                        if (!u.loading && u.results.isEmpty()) {
                            InfoCard(container = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("Вариантов нет: ${u.reason ?: "остатки не подходят"}.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { nav.navigate("import") }) { Text("Добавить покупку") }
                                    TextButton(onClick = { nav.navigate("inventory") }) { Text("Инвентаризация") }
                                }
                            }
                        }
                    }
                    items(u.results, key = { it.key }) { s ->
                        SuggestionCard(s, u.today, onRecord = { vm.record(s) }, onHow = { howTo = s },
                            onBuilder = { vm.openInBuilder(s); nav.navigate("build/0/0") }, onBlock = { blockName = s },
                            onRecipe = if (s.recipe != null) ({ vm.saveAsRecipe(s) }) else null, onHide = { vm.hide(s) })
                    }
                } else {
                    item {
                        Text("Блюда на 2 дня (обед и ужин) из продуктов с ближайшим сроком или залежавшихся.", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                        if (u.loading) CircularProgressIndicator()
                        else if (u.leftovers.isEmpty()) Text("Нет продуктов со сроком ≤3 дней или залежавшихся. Срок можно указать в партии или инвентаризации (до ДД.ММ).")
                    }
                    items(u.leftovers, key = { it.first + it.second.key }) { (label, s) ->
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        SuggestionCard(s, u.today, onRecord = { vm.record(s) }, onHow = { howTo = s },
                            onBuilder = { vm.openInBuilder(s); nav.navigate("build/0/0") }, onBlock = { blockName = s }, onRecipe = null, onHide = null)
                    }
                }
            }
        }
    }

    howTo?.let { s -> HowToDialog(s) { howTo = null } }
    blockName?.let { s ->
        var name by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { blockName = null },
            title = { Text("Сохранить как свой блок") },
            text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Название") }) },
            confirmButton = { TextButton(onClick = { vm.saveAsBlock(s, name); blockName = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { blockName = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionCard(
    s: Suggestion, today: Long, onRecord: () -> Unit, onHow: () -> Unit, onBuilder: () -> Unit, onBlock: () -> Unit,
    onRecipe: (() -> Unit)?, onHide: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(s.title, fontWeight = FontWeight.SemiBold)
            Text("${Math.round(s.kcal)} ккал · ${Math.round(s.protein)} г белка" + (s.method?.let { " · ${it.device} ${it.label}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium)
            s.items.forEach { i ->
                Text("• ${i.entry.product.name} — ${TimeUtil.num(i.qty)} ${i.entry.unit.label}" + if (i.entry.approx) " (примерно)" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("всё есть", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (s.expiringSoon(today)) Text("скоро испортится", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (s.approx) Text("примерно", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (s.generated) Text("новый, проверьте", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                if (s.block?.custom == true) Text("мой", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilledTonalButton(onClick = onRecord) { Text("Записать как съеденное") }
                OutlinedButton(onClick = onHow) { Text("Как приготовить") }
                TextButton(onClick = onBuilder) { Text("В конструкторе") }
                TextButton(onClick = onBlock) { Text("Свой блок") }
                onRecipe?.let { TextButton(onClick = it) { Text("Свой рецепт") } }
                onHide?.let { TextButton(onClick = it) { Text("Скрыть") } }
            }
        }
    }
}

@Composable
private fun HowToDialog(s: Suggestion, onDismiss: () -> Unit) {
    val r = s.recipe
    val steps = r?.steps ?: s.block?.recipeSteps?.map { it.text } ?: s.defaultSteps
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val method = r?.method ?: s.method
                method?.let { Text("${it.device} ${it.label}", fontWeight = FontWeight.SemiBold) }
                r?.let { Text("Активно ${it.activeMin} мин, всего ${it.totalMin} мин" + if (it.rawToCooked < 1.0) " · готовый вес ≈ ${Math.round(it.rawToCooked * 100)}% сырого" else "") }
                s.block?.let { if (it.prepMinutes > 0) Text("${it.prepMinutes} мин") }
                if (steps.isEmpty()) Text("Шагов нет: продукт готов к еде.")
                steps.forEachIndexed { i, st -> Text("${i + 1}. $st") }
                if (s.generated) Text("Рецепт новый, проверьте калорийность и способ.", color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall)
                Text("Без соли или минимум; температуры и время — ориентир.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
