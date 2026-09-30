package com.ration.app.ui.purchases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Purchase
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.inventory.ShoppingItem
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.EmptyState
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.PlainTopBar
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.ui.theme.LevelColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ShoppingViewModel @Inject constructor(private val inventory: InventoryRepository, private val db: com.ration.app.data.db.AppDatabase) : ViewModel() {
    val items = MutableStateFlow<List<ShoppingItem>>(emptyList())
    val purchases = db.purchases().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Purchase>())

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(inventory.stock, db.products().observeAll()) { _, _ -> }.collect { items.value = inventory.shoppingList() }
        }
    }

    fun bought(item: ShoppingItem, qty: Double) = viewModelScope.launch { inventory.markBought(item, qty) }

    fun asText(list: List<ShoppingItem>): String = list.joinToString("\n") {
        "${if (it.urgent) "! " else ""}${it.product.name} — ${TimeUtil.num(it.toBuy)} ${it.product.unit.label}"
    }
}

@Composable
fun PurchasesScreen(nav: NavController, vm: ShoppingViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val purchases by vm.purchases.collectAsStateWithLifecycle()
    Scaffold(topBar = { PlainTopBar("Закупки") }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.navigate("import") }, modifier = Modifier.fillMaxWidth()) { Text("Внести покупку (чек, файл, текст)") }
                    FilledTonalButton(onClick = { nav.navigate("shopping") }, modifier = Modifier.fillMaxWidth()) {
                        val urgent = items.count { it.urgent }
                        Text("Список в магазин: ${items.size}" + if (urgent > 0) " (срочно $urgent)" else "")
                    }
                }
                SectionTitle("История покупок")
            }
            if (purchases.isEmpty()) item { EmptyState("Покупок ещё нет. Вставьте текст чека или импортируйте файл.") }
            items(purchases, key = { it.id }) { p ->
                Text("${TimeUtil.dateRu(LocalDate.ofEpochDay(p.day))}${if (p.store.isNotBlank()) " · ${p.store}" else ""}", modifier = Modifier.padding(vertical = 8.dp))
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ShoppingScreen(nav: NavController, vm: ShoppingViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { BackTopBar("Список в магазин", { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Text("Всё ниже 40% нормального запаса — одной поездкой. Срочные (20% и ниже) сверху.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(vm.asText(items)))
                    scope.launch { snackbar.showSnackbar("Список скопирован") }
                }, enabled = items.isNotEmpty(), modifier = Modifier.padding(vertical = 8.dp)) { Text("Скопировать текстом") }
            }
            if (items.isEmpty()) item { EmptyState("Всё в норме. Нормальный запас задаётся последней закупкой или вручную в кладовой.") }
            items(items, key = { it.product.id }) { item -> ShoppingRow(item) { vm.bought(item, it) } }
        }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, onBought: (Double) -> Unit) {
    var qty by remember(item.product.id, item.toBuy) { mutableStateOf(TimeUtil.num(item.toBuy)) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.product.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                color = if (item.urgent) LevelColors.red else MaterialTheme.colorScheme.onSurface)
            Text("${TimeUtil.num(item.toBuy)} ${item.product.unit.label}")
        }
        Text("есть ${TimeUtil.num(item.have)} из ${TimeUtil.num(item.par)} · ${Math.round(item.percent)}%" + if (item.urgent) " · срочно" else "",
            style = MaterialTheme.typography.bodySmall)
        if (expanded) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Куплено", qty, { qty = it }, Modifier.weight(1f), item.product.unit.label)
            Button(onClick = { qty.toNumberOrNull()?.takeIf { it > 0 }?.let(onBought) }) { Text("Куплено") }
        }
    }
    HorizontalDivider()
}
