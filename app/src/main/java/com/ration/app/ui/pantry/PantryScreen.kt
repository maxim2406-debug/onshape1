package com.ration.app.ui.pantry

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.ration.app.data.db.entity.Product
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.inventory.StockLevel
import com.ration.app.domain.inventory.Thresholds
import com.ration.app.ui.components.Dot
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.PlainTopBar
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.ui.theme.LevelColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class PantryRow(val product: Product, val total: Double, val percent: Double?, val level: StockLevel, val nearestExpiry: Long?, val approx: Boolean = false)
data class PantryState(val rows: List<PantryRow> = emptyList(), val preps: List<Prep> = emptyList(), val today: Long = 0, val showUntracked: Boolean = false)

@HiltViewModel
class PantryViewModel @Inject constructor(
    private val inventory: InventoryRepository,
    catalog: CatalogRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val showUntracked = kotlinx.coroutines.flow.MutableStateFlow(false)
    val state = combine(catalog.products, inventory.stock, inventory.activePreps, settings.settings, showUntracked) { products, stock, preps, s, su ->
        val byProduct = stock.groupBy { it.productId }
        val rows = products.filter { (su || !it.untracked) && (!it.hidden || byProduct[it.id].orEmpty().any { s -> s.qty > 1e-6 }) }.map { p ->
            val items = byProduct[p.id].orEmpty()
            val total = items.sumOf { it.qty }
            PantryRow(p, total, Thresholds.percent(total, p.parLevel), Thresholds.level(total, p.parLevel, s.buyThresholdPct, s.urgentThresholdPct),
                items.filter { it.qty > 1e-6 }.mapNotNull { it.expiresDay }.minOrNull(), items.any { it.qty > 1e-6 && it.approx })
        }.sortedWith(compareBy<PantryRow> { if (it.product.untracked) 2 else if (it.percent == null) 1 else 0 }.thenBy { it.percent ?: 0.0 }.thenBy { it.product.name })
        PantryState(rows, preps, inventory.today(), su)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PantryState())

    fun toggleUntracked() { showUntracked.value = !showUntracked.value }
    fun setTotal(p: Product, v: Double) = viewModelScope.launch { inventory.setTotal(p.id, v) }
    fun addBatch(p: Product, qty: Double, expiresInDays: Int?) = viewModelScope.launch {
        inventory.addBatch(p.id, qty, expiresInDays?.let { inventory.today() + it })
    }
    fun setPar(p: Product, par: Double?) = viewModelScope.launch { inventory.setPar(p.id, par) }
    fun setUntracked(p: Product, v: Boolean) = viewModelScope.launch { inventory.setUntracked(p.id, v) }
    fun discard(prep: Prep) = viewModelScope.launch { inventory.discardPrep(prep) }
}

fun levelColor(l: StockLevel) = when (l) {
    StockLevel.GREEN -> LevelColors.green
    StockLevel.YELLOW -> LevelColors.yellow
    StockLevel.RED -> LevelColors.red
    StockLevel.UNKNOWN -> LevelColors.grey
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PantryScreen(nav: NavController, vm: PantryViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PantryRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(topBar = { PlainTopBar("Кладовая") { FilterChip(st.showUntracked, vm::toggleUntracked, { Text("Мелочи") }) } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    androidx.compose.material3.AssistChip(onClick = { nav.navigate("inventory") }, label = { Text("Инвентаризация") })
                    androidx.compose.material3.AssistChip(onClick = { adding = true }, label = { Text("Добавить в библиотеку") })
                    androidx.compose.material3.AssistChip(onClick = { nav.navigate("library") }, label = { Text("Библиотека") })
                    androidx.compose.material3.AssistChip(onClick = { nav.navigate("cook") }, label = { Text("Что приготовить") })
                }
            }
            item { SectionTitle("Заготовки") }
            if (st.preps.isEmpty()) item { Text("Заготовок нет. Приготовьте на вкладке «Заготовки».", style = MaterialTheme.typography.bodySmall) }
            items(st.preps, key = { "p${it.id}" }) { prep -> PrepRow(prep, st.today, onDiscard = { vm.discard(prep) }) }
            item { SectionTitle("Продукты") }
            if (st.rows.isEmpty()) item { Text("Каталог пуст.") }
            items(st.rows, key = { it.product.id }) { row ->
                Row(Modifier.fillMaxWidth().clickable { editing = row }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(levelColor(row.level))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.product.name, fontWeight = FontWeight.Medium)
                        val exp = row.nearestExpiry?.let { " · до ${TimeUtil.dateShort(LocalDate.ofEpochDay(it))}" } ?: ""
                        val par = row.product.parLevel?.let { " из ${TimeUtil.num(it)}" } ?: " · норма не задана"
                        Text("${if (row.approx) "≈" else ""}${TimeUtil.num(row.total)} ${row.product.unit.label}$par$exp" + if (row.product.untracked) " · не отслеживается" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.nearestExpiry != null && row.nearestExpiry < st.today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    row.percent?.let { Text("${Math.round(it)}%", fontWeight = FontWeight.SemiBold, color = levelColor(row.level)) }
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { row -> EditProductDialog(row, vm) { editing = null } }
    if (adding) com.ration.app.ui.library.ProductFormDialog(onDismiss = { adding = false }) { adding = false }
}

@Composable
fun PrepRow(prep: Prep, today: Long, onDiscard: () -> Unit) {
    val expired = prep.expiresDay < today
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(prep.name, fontWeight = FontWeight.Medium, color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("${TimeUtil.num(prep.portionsLeft)} из ${TimeUtil.num(prep.portionsTotal)} порц." +
                (prep.portionGrams?.let { " по ${TimeUtil.num(it)} г" } ?: " (шт)") +
                " · " + (if (expired) "просрочено ${TimeUtil.dateShort(LocalDate.ofEpochDay(prep.expiresDay))}" else "до ${TimeUtil.dateShort(LocalDate.ofEpochDay(prep.expiresDay))}"),
                style = MaterialTheme.typography.bodySmall, color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expired || prep.expiresDay == today) TextButton(onClick = onDiscard) { Text("Выбросить") }
    }
}

@Composable
private fun EditProductDialog(row: PantryRow, vm: PantryViewModel, onDismiss: () -> Unit) {
    val p = row.product
    var total by remember { mutableStateOf(TimeUtil.num(row.total)) }
    var batch by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf("") }
    var par by remember { mutableStateOf(p.parLevel?.let { TimeUtil.num(it) } ?: "") }
    var untracked by remember { mutableStateOf(p.untracked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("Остаток", total, { total = it }, Modifier.fillMaxWidth(), p.unit.label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Новая партия", batch, { batch = it }, Modifier.weight(1f), p.unit.label)
                    NumberField("Срок, дней", expires, { expires = it.filter(Char::isDigit) }, Modifier.weight(1f))
                }
                NumberField("Нормальный запас" + if (p.parManual) " (вручную)" else " (по последней закупке)", par, { par = it }, Modifier.fillMaxWidth(), p.unit.label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(untracked, { untracked = it }); Text("Не отслеживать (мелочи)")
                }
                if (p.unit.name == "PCS") Text("Вес штуки: ${p.gramsPerPiece?.let { TimeUtil.num(it) + " г" } ?: "не задан"}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                total.toNumberOrNull()?.takeIf { kotlin.math.abs(it - row.total) > 1e-6 && it >= 0 }?.let { vm.setTotal(p, it) }
                batch.toNumberOrNull()?.takeIf { it > 0 }?.let { vm.addBatch(p, it, expires.toIntOrNull()) }
                val newPar = par.toNumberOrNull()
                if (newPar != p.parLevel && !(par.isBlank() && !p.parManual)) vm.setPar(p, if (par.isBlank()) null else newPar)
                if (untracked != p.untracked) vm.setUntracked(p, untracked)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
