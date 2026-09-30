package com.ration.app.ui.blocks

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.Substitution
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.importing.ImportParser
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.substitution.SubstitutionProposal
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.RecipeSteps
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BlocksViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val meals: MealRepository,
    private val plans: PlanRepository,
) : ViewModel() {
    private val id: Long = savedState["id"] ?: 0L
    val blocks = catalog.blocks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val foods = catalog.customFoods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subs = meals.observeSubstitutions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val ingredients = MutableStateFlow<List<BlockIngredient>>(emptyList())
    val proposal = MutableStateFlow<Triple<BlockIngredient, CustomFood, SubstitutionProposal>?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init { if (id > 0) viewModelScope.launch { ingredients.value = catalog.ingredientsOf(id) } }

    fun saveNutrition(b: Block, kcal: Double, protein: Double) = viewModelScope.launch {
        catalog.saveBlock(b.copy(kcal = kcal, protein = protein)); _messages.tryEmit("Блок обновлён")
    }

    fun propose(ing: BlockIngredient, food: CustomFood) = viewModelScope.launch {
        val p = meals.proposeSubstitution(ing, food)
        if (p == null) _messages.tryEmit("Для этого ингредиента нет данных о весе или пищевой ценности") else proposal.value = Triple(ing, food, p)
    }

    fun proposeFromLabel(ing: BlockIngredient, text: String) = viewModelScope.launch {
        val l = ImportParser.parseLabel(text) ?: run { _messages.tryEmit("Не удалось разобрать строку этикетки"); return@launch }
        val food = CustomFood(name = l.name, kcalPer100 = l.kcalPer100, proteinPer100 = l.proteinPer100, portionGrams = l.portionGrams,
            kcalPerPortion = l.kcalPerPortion, proteinPerPortion = l.proteinPerPortion, replacesProductId = ing.productId)
        val saved = food.copy(id = catalog.saveCustomFood(food))
        propose(ing, saved)
    }

    fun apply(block: Block, permanent: Boolean) = viewModelScope.launch {
        val (ing, food, p) = proposal.value ?: return@launch
        meals.saveSubstitution(block, ing, food, p, permanent, plans.today())
        proposal.value = null
        _messages.tryEmit(if (permanent) "Замена сохранена как постоянная" else "Замена применена на сегодня")
    }

    fun dismissProposal() { proposal.value = null }
    fun deleteSub(s: Substitution) = viewModelScope.launch { meals.deleteSubstitution(s) }
}

@Composable
fun BlocksScreen(nav: NavController, vm: BlocksViewModel = hiltViewModel()) {
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Блоки и рецепты", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            MealKind.entries.forEach { kind ->
                item { SectionTitle(kind.label, Modifier.padding(horizontal = 16.dp)) }
                items(blocks.filter { it.kind == kind }, key = { it.id }) { b ->
                    ListItem(
                        headlineContent = { Text("${b.code} ${b.name}") },
                        supportingContent = { Text("${Math.round(b.kcal)} ккал · ${Math.round(b.protein)} г" + if (b.prepMinutes > 0) " · ${b.prepMinutes} мин" else "") },
                        modifier = Modifier.clickable { nav.navigate("block/${b.id}") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun BlockDetailScreen(nav: NavController, id: Long, vm: BlocksViewModel = hiltViewModel()) {
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val ingredients by vm.ingredients.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    val subs by vm.subs.collectAsStateWithLifecycle()
    val proposal by vm.proposal.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val block = blocks.firstOrNull { it.id == id } ?: return
    var kcal by remember(block.kcal) { mutableStateOf(TimeUtil.num(block.kcal)) }
    var protein by remember(block.protein) { mutableStateOf(TimeUtil.num(block.protein)) }
    var substituting by remember { mutableStateOf<BlockIngredient?>(null) }

    Scaffold(topBar = { BackTopBar(block.code, { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(block.name, style = MaterialTheme.typography.titleLarge)
            Text(block.composition, style = MaterialTheme.typography.bodySmall)
            Text("Теги: ${block.tags.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField("Ккал", kcal, { kcal = it }, Modifier.weight(1f))
                NumberField("Белок, г", protein, { protein = it }, Modifier.weight(1f))
                TextButton(onClick = {
                    val k = kcal.toNumberOrNull(); val p = protein.toNumberOrNull()
                    if (k != null && p != null && k in 0.0..5000.0 && p in 0.0..500.0) vm.saveNutrition(block, k, p)
                }) { Text("Сохранить") }
            }
            if (!block.deductStock) InfoCard { Text("Блок не списывается со склада: учитываются только ккал и белок.") }
            if (ingredients.isNotEmpty()) {
                SectionTitle("Состав (для списания)")
                ingredients.forEach { ing ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• ${ing.label}" + if (ing.toTaste) " — по вкусу" else "", Modifier.weight(1f))
                        if (!ing.toTaste) TextButton(onClick = { substituting = ing }) { Text("Заменить") }
                    }
                }
            }
            val blockSubs = subs.filter { it.blockId == block.id }
            if (blockSubs.isNotEmpty()) {
                SectionTitle("Замены")
                blockSubs.forEach { s ->
                    val ing = ingredients.firstOrNull { it.id == s.ingredientId }
                    val food = foods.firstOrNull { it.id == s.customFoodId }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${ing?.label ?: "?"} → ${food?.name ?: "?"} ${TimeUtil.num(s.qtyGrams)} г (${if (s.permanent) "постоянно" else "разово"}; " +
                            "${signed(s.kcalDelta)} ккал, ${signed(s.proteinDelta)} г)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.deleteSub(s) }) { Text("Убрать") }
                    }
                }
            }
            if (block.recipeSteps.isNotEmpty()) {
                SectionTitle("Рецепт" + if (block.prepMinutes > 0) " · ${block.prepMinutes} мин" else "")
                RecipeSteps(block.recipeSteps)
                Text("Вместо соли: паприка, чесночный порошок, чёрный перец, лимон, зелень. Внутри: рыба и свинина 63 °C, курица 74 °C, говядина 57–60 °C.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    substituting?.let { ing -> ChooseFoodDialog(ing, foods, onDismiss = { substituting = null }, onFood = { vm.propose(ing, it); substituting = null },
        onLabel = { vm.proposeFromLabel(ing, it); substituting = null }) }
    proposal?.let { (ing, food, p) ->
        AlertDialog(
            onDismissRequest = vm::dismissProposal,
            title = { Text("Замена: ${ing.label}") },
            text = {
                Column {
                    Text("${food.name}: ${TimeUtil.num(p.grams)} г")
                    Text("${TimeUtil.num(p.kcal)} ккал, ${TimeUtil.num(p.protein)} г белка")
                    Text("Разница: ${signed(p.kcalDelta)} ккал, ${signed(p.proteinDelta)} г белка")
                    if (p.lowProtein) Text("Белка меньше 60% от заменяемого — лучше выбрать другой блок.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.apply(block, permanent = false) }) { Text("Один раз") }
                    TextButton(onClick = { vm.apply(block, permanent = true) }) { Text("Постоянно") }
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissProposal) { Text("Отмена") } },
        )
    }
}

private fun signed(v: Double) = (if (v > 0) "+" else "") + TimeUtil.num(v)

@Composable
private fun ChooseFoodDialog(ing: BlockIngredient, foods: List<CustomFood>, onDismiss: () -> Unit, onFood: (CustomFood) -> Unit, onLabel: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var selected by remember { mutableStateOf<CustomFood?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Чем заменить «${ing.label}»") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (foods.isEmpty()) Text("В справочнике своих продуктов пока нет. Вставьте строку этикетки (формат 5.2).", style = MaterialTheme.typography.bodySmall)
                foods.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { selected = f }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected?.id == f.id, { selected = f })
                        Text("${f.name} · ${TimeUtil.num(f.kcalPer100)}/${TimeUtil.num(f.proteinPer100)} на 100 г")
                    }
                }
                TextButton(onClick = { clipboard.getText()?.text?.let(onLabel) }) { Text("Вставить строку этикетки из буфера") }
            }
        },
        confirmButton = { TextButton(onClick = { selected?.let(onFood) }, enabled = selected != null) { Text("Подобрать количество") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
