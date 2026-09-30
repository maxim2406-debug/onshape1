package com.ration.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.library.FoodEntry
import com.ration.app.domain.library.FoodFilters
import com.ration.app.domain.library.FoodHit
import com.ration.app.domain.library.FoodSearch
import com.ration.app.domain.library.SortMode
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.ProductSource
import com.ration.app.domain.model.Tags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FoodListViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val entries = catalog.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sort = settings.settings.map { runCatching { SortMode.valueOf(it.librarySort) }.getOrDefault(SortMode.FREQUENT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortMode.FREQUENT)

    /** Выбор сортировки запоминается в DataStore (16.2). */
    fun setSort(m: SortMode) = viewModelScope.launch { settings.update { it.copy(librarySort = m.name) } }
}

/** Подсветка совпадений в названии (16.1.1). */
fun highlighted(name: String, ranges: List<IntRange>): AnnotatedString = buildAnnotatedString {
    append(name)
    ranges.forEach { r -> if (r.first >= 0 && r.last < name.length) addStyle(SpanStyle(fontWeight = FontWeight.Bold), r.first, r.last + 1) }
}

private fun unitLabel(u: MeasureUnit) = if (u == MeasureUnit.ML) "100 мл" else "100 г"

/**
 * Общий список библиотеки: поиск с задержкой ~150 мс, сортировка, фильтры (16).
 * Используется в Библиотеке и во всех экранах выбора продукта.
 */
@Composable
fun FoodList(
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    showHiddenToggle: Boolean = false,
    includeCustomFoods: Boolean = true,
    onPick: (FoodEntry) -> Unit,
    onAddNew: (String) -> Unit,
    trailing: (@Composable (FoodEntry) -> Unit)? = null,
    vm: FoodListViewModel = hiltViewModel(),
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var debounced by remember { mutableStateOf(initialQuery) }
    var filters by remember { mutableStateOf(FoodFilters()) }
    var sortMenu by remember { mutableStateOf(false) }
    var catMenu by remember { mutableStateOf(false) }
    LaunchedEffect(query) { delay(150); debounced = query }
    val source = remember(entries, includeCustomFoods) { if (includeCustomFoods) entries else entries.filter { it.productId != null } }
    val hits: List<FoodHit> = remember(source, debounced, sort, filters) { FoodSearch.search(source, debounced, sort, filters) }
    val categories = remember(entries) { entries.map { it.category }.filter { it.isNotBlank() }.distinct().sortedWith { a, b -> FoodSearch.compareNames(a, b) } }

    Column(modifier) {
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(80) }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Очистить") } },
            placeholder = { Text("Поиск: название, алиас, иврит, категория") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                FilterChip(true, { sortMenu = true }, { Text("↕ ${sort.label}") })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SortMode.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { vm.setSort(m); sortMenu = false }) }
                }
            }
            Column {
                FilterChip(filters.categories.isNotEmpty(), { catMenu = true }, { Text(filters.categories.firstOrNull() ?: "Категория") })
                DropdownMenu(catMenu, { catMenu = false }) {
                    DropdownMenuItem(text = { Text("Все категории") }, onClick = { filters = filters.copy(categories = emptySet()); catMenu = false })
                    categories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { filters = filters.copy(categories = setOf(c)); catMenu = false }) }
                }
            }
            FilterChip(filters.inStockOnly, { filters = filters.copy(inStockOnly = !filters.inStockOnly) }, { Text("Есть на складе") })
            FilterChip(filters.favoritesOnly, { filters = filters.copy(favoritesOnly = !filters.favoritesOnly) }, { Text("Избранное") })
            listOf(Tags.FISH to "рыба", Tags.RED_MEAT to "красное мясо", Tags.SALTY to "солёное", Tags.PROCESSED to "переработанное").forEach { (t, l) ->
                FilterChip(t in filters.tags, { filters = filters.copy(tags = if (t in filters.tags) filters.tags - t else filters.tags + t) }, { Text(l) })
            }
            ProductSource.entries.forEach { s ->
                FilterChip(s in filters.sources, { filters = filters.copy(sources = if (s in filters.sources) filters.sources - s else filters.sources + s) }, { Text(s.label) })
            }
            if (showHiddenToggle) FilterChip(filters.showHidden, { filters = filters.copy(showHidden = !filters.showHidden) }, { Text("Скрытые") })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (hits.isEmpty() || debounced.isNotBlank()) item {
                val text = debounced.trim()
                Row(Modifier.fillMaxWidth().clickable { onAddNew(text) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(if (text.isNotEmpty() && hits.isEmpty()) "Добавить «$text» в библиотеку" else "Добавить в библиотеку",
                        color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
            items(hits, key = { it.entry.id }) { h -> FoodRow(h, onClick = { onPick(h.entry) }, trailing = trailing) }
        }
    }
}

@Composable
fun FoodRow(h: FoodHit, onClick: () -> Unit, trailing: (@Composable (FoodEntry) -> Unit)? = null) {
    val e = h.entry
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.favorite) Icon(Icons.Filled.Star, "Избранное", Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(highlighted(e.name, h.highlights), color = if (e.hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            }
            val approx = if (e.source == ProductSource.REFERENCE) "≈ " else ""
            val per = unitLabel(e.unit)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$approx${e.kcal100?.let { TimeUtil.num(it) } ?: "—"} ккал · ${e.protein100?.let { TimeUtil.num(it) } ?: "—"} г белка на $per",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (Tags.SALTY in e.tags) Text("соль", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                if (e.hidden) Text("скрыт", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (e.inStock) Icon(Icons.Filled.Check, "Есть на складе", tint = MaterialTheme.colorScheme.primary)
        trailing?.invoke(e)
    }
    HorizontalDivider()
}

/** Полноэкранный выбор продукта с «Добавить в библиотеку» и «Добавить и выбрать» (13.1.3). */
@Composable
fun FoodPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    includeCustomFoods: Boolean = true,
    onPick: (FoodEntry) -> Unit,
) {
    var adding by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                FoodList(includeCustomFoods = includeCustomFoods, onPick = onPick, onAddNew = { adding = it })
            }
        }
    }
    adding?.let { name ->
        ProductFormDialog(initialName = name, onDismiss = { adding = null }, pickLabel = "Добавить и выбрать") { entry ->
            adding = null
            onPick(entry)
        }
    }
}
