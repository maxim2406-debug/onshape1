package com.ration.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ration.app.data.db.entity.Product
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.cook.Categories
import com.ration.app.domain.library.FoodEntry
import com.ration.app.domain.library.LibraryParser
import com.ration.app.domain.library.NutritionBasis
import com.ration.app.domain.library.NutritionInput
import com.ration.app.domain.model.CookState
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.ProductSource
import com.ration.app.domain.model.Tags
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.toNumberOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProductFormViewModel @Inject constructor(private val catalog: CatalogRepository) : ViewModel() {
    var categories: List<String> = Categories.START
        private set
    var existing: List<Product> = emptyList()
        private set

    fun load(done: () -> Unit) = viewModelScope.launch {
        categories = catalog.categories()
        existing = catalog.allProducts()
        done()
    }

    fun save(p: Product, isEdit: Boolean, onSaved: (FoodEntry) -> Unit) = viewModelScope.launch {
        val id = if (isEdit) catalog.editProduct(p) else catalog.addToLibrary(p)
        catalog.product(id)?.let { onSaved(FoodEntry.of(it, false)) }
    }
}

/**
 * Одна форма «Добавить в библиотеку» на всех экранах (13.1). Сохранение ничего не пишет в журнал приёмов.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductFormDialog(
    initialName: String = "",
    editing: Product? = null,
    pickLabel: String = "Сохранить",
    onDismiss: () -> Unit,
    vm: ProductFormViewModel = hiltViewModel(),
    onSaved: (FoodEntry) -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load { loaded = true } }
    var name by remember { mutableStateOf(editing?.name ?: initialName) }
    var category by remember { mutableStateOf(editing?.category ?: Categories.SAUCES_OTHER) }
    var unit by remember { mutableStateOf(editing?.unit ?: MeasureUnit.G) }
    var basis by remember { mutableStateOf(NutritionBasis.PER_100G) }
    var kcal by remember { mutableStateOf(editing?.kcalPer100?.let { TimeUtil.num(it) } ?: "") }
    var protein by remember { mutableStateOf(editing?.proteinPer100?.let { TimeUtil.num(it) } ?: "") }
    var portion by remember { mutableStateOf("") }
    var gpp by remember { mutableStateOf(editing?.gramsPerPiece?.let { TimeUtil.num(it) } ?: "") }
    var aliases by remember { mutableStateOf(editing?.aliases?.joinToString(", ") ?: "") }
    var tags by remember { mutableStateOf(editing?.tags?.toSet() ?: emptySet()) }
    var cooked by remember { mutableStateOf(editing?.cooked) }
    var edible by remember { mutableStateOf(editing?.edibleFraction?.takeIf { it < 1.0 }?.let { TimeUtil.num(it, 2) } ?: "") }
    var fromLabel by remember { mutableStateOf(editing?.source == ProductSource.LABEL) }
    var error by remember { mutableStateOf<String?>(null) }
    var catMenu by remember { mutableStateOf(false) }
    var similar by remember { mutableStateOf<Product?>(null) }
    var pending by remember { mutableStateOf<Product?>(null) }

    fun build(): Product? {
        val k0 = kcal.toNumberOrNull(); val p0 = protein.toNumberOrNull()
        val por = portion.toNumberOrNull()
        val k = k0?.let { NutritionInput.per100(it, basis, por) }
        val pr = p0?.let { NutritionInput.per100(it, basis, por) }
        val ef = edible.toNumberOrNull()?.let { if (it > 1) it / 100 else it } ?: 1.0
        return when {
            name.isBlank() -> { error = "Введите название"; null }
            k == null || pr == null -> { error = if (basis == NutritionBasis.PER_PORTION && por == null) "Укажите размер порции" else "Введите ккал и белок"; null }
            k !in 0.0..900.0 || pr !in 0.0..100.0 -> { error = "Ккал 0–900 и белок 0–100 на 100 г"; null }
            ef !in 0.05..1.0 -> { error = "Съедобная доля от 0,05 до 1"; null }
            else -> (editing ?: Product(name = "", unit = unit, kcalPer100 = 0.0, proteinPer100 = 0.0)).copy(
                name = name.trim().take(120), category = category.trim().lowercase().ifBlank { Categories.SAUCES_OTHER }, unit = unit,
                kcalPer100 = Math.round(k * 10) / 10.0, proteinPer100 = Math.round(pr * 10) / 10.0,
                gramsPerPiece = gpp.toNumberOrNull()?.takeIf { it > 0 },
                aliases = aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(20),
                tags = tags.toList(), cooked = cooked, edibleFraction = ef,
                source = if (fromLabel) ProductSource.LABEL else if (editing != null) editing.source else ProductSource.USER,
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Продукт" else "Добавить в библиотеку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column {
                    OutlinedTextField(category, { category = it.take(40) }, label = { Text("Категория") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { catMenu = true }) { Text("▾") } })
                    DropdownMenu(catMenu, { catMenu = false }) {
                        vm.categories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catMenu = false }) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeasureUnit.entries.forEach { u -> FilterChip(unit == u, { unit = u }, { Text(u.label) }) }
                }
                Text("Пищевая ценность", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NutritionBasis.entries.forEach { b -> FilterChip(basis == b, { basis = b }, { Text(b.label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Ккал", kcal, { kcal = it }, Modifier.weight(1f))
                    NumberField("Белок, г", protein, { protein = it }, Modifier.weight(1f))
                }
                if (basis == NutritionBasis.PER_PORTION) NumberField("Размер порции, г", portion, { portion = it }, Modifier.fillMaxWidth())
                if (unit == MeasureUnit.PCS) NumberField("Вес штуки, г", gpp, { gpp = it }, Modifier.fillMaxWidth())
                OutlinedTextField(aliases, { aliases = it.take(300) }, label = { Text("Алиасы через запятую (иврит тоже)") }, modifier = Modifier.fillMaxWidth())
                Text("Теги", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Tags.FISH, Tags.FATTY_FISH, Tags.SEAFOOD, Tags.RED_MEAT, Tags.EGG, Tags.PROCESSED, Tags.SALTY).forEach { t ->
                        FilterChip(t in tags, { tags = if (t in tags) tags - t else tags + t }, { Text(t) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(cooked == null, { cooked = null }, { Text("не важно") })
                    CookState.entries.forEach { c -> FilterChip(cooked == c, { cooked = c }, { Text(c.label) }) }
                    FilterChip(fromLabel, { fromLabel = !fromLabel }, { Text("с этикетки") })
                }
                NumberField("Съедобная доля (кость), 0–1", edible, { edible = it }, Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = loaded, onClick = {
                val p = build() ?: return@TextButton
                val sim = if (editing == null) LibraryParser.findSimilar(p.name, vm.existing) else null
                if (sim != null) { similar = sim; pending = p } else vm.save(p, editing != null, onSaved)
            }) { Text(pickLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    similar?.let { s ->
        AlertDialog(
            onDismissRequest = { similar = null },
            title = { Text("Уже есть похожий продукт") },
            text = { Text("«${s.name}» — ${TimeUtil.num(s.kcalPer100)} ккал, ${TimeUtil.num(s.proteinPer100)} г белка на 100 г.") },
            confirmButton = { TextButton(onClick = { similar = null; onSaved(FoodEntry.of(s, false)) }) { Text("Взять существующий") } },
            dismissButton = { TextButton(onClick = { similar = null; pending?.let { vm.save(it, false, onSaved) } }) { Text("Добавить всё равно") } },
        )
    }
}
