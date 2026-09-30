package com.ration.app.ui.purchases

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Product
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.importing.MatchKind
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportScreen(nav: NavController, sharedText: String?, onSharedConsumed: () -> Unit, vm: ImportViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var store by remember { mutableStateOf("") }
    var showApiWarning by remember { mutableStateOf(false) }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) { vm.setMode(ImportMode.RECEIPT); vm.setText(sharedText); vm.parse(); onSharedConsumed() }
    }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadFile) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::recognizePhoto) }

    Scaffold(topBar = { BackTopBar("Импорт", { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(ui.mode == ImportMode.RECEIPT, { vm.setMode(ImportMode.RECEIPT) }, { Text("Чек") })
                FilterChip(ui.mode == ImportMode.LABEL, { vm.setMode(ImportMode.LABEL) }, { Text("Этикетка") })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { clipboard.setText(AnnotatedString(vm.prompt())); snackbar.showSnackbar("Промпт скопирован. Вставьте его в чат Claude вместе с фото.") } }) {
                    Text(if (ui.mode == ImportMode.RECEIPT) "Скопировать промпт для чека" else "Скопировать промпт для этикетки")
                }
                OutlinedButton(onClick = { vm.setText(clipboard.getText()?.text.orEmpty()); vm.parse() }) { Text("Вставить из буфера") }
                if (ui.mode == ImportMode.RECEIPT) OutlinedButton(onClick = { fileLauncher.launch(arrayOf("text/plain", "text/csv", "text/comma-separated-values")) }) { Text("Файл .txt/.csv") }
                if (ui.apiEnabled) OutlinedButton(onClick = {
                    if (ui.apiWarningAccepted) photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else showApiWarning = true
                }) { Text("Распознать по фото") }
            }
            OutlinedTextField(
                ui.text, vm::setText, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text(if (ui.mode == ImportMode.RECEIPT) "название | количество | единица | цена" else "название | ккал/100 | белок/100 | порция | ккал/порц | белок/порц") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::parse) { Text("Разобрать") }
                if (ui.busy) CircularProgressIndicator(Modifier.padding(start = 16.dp))
            }
            ui.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (ui.comments.isNotEmpty()) Text("Неразборчивые строки чека: " + ui.comments.joinToString("; "), style = MaterialTheme.typography.bodySmall)

            if (ui.mode == ImportMode.RECEIPT && ui.lines.isNotEmpty()) {
                SectionTitle("Проверка")
                ui.lines.forEach { line -> ReviewRow(line, products, vm) }
                OutlinedTextField(store, { store = it.take(60) }, label = { Text("Магазин (необязательно)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { vm.savePurchase(store) }, modifier = Modifier.fillMaxWidth()) { Text("Добавить в кладовую") }
            }
            ui.label?.let { l -> LabelPreview(l, products) { bar, pbar, repl -> vm.saveLabel(l, bar, pbar, repl) } }
        }
    }

    if (showApiWarning) {
        AlertDialog(
            onDismissRequest = { showApiWarning = false },
            title = { Text("Платный запрос") },
            text = { Text("Фото и промпт будут отправлены в Claude API. Запрос платный и выполняется по вашему ключу. Личные данные (вес, давление, журнал) не отправляются.") },
            confirmButton = { TextButton(onClick = { showApiWarning = false; vm.acceptApiWarning(); photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Понятно, продолжить") } },
            dismissButton = { TextButton(onClick = { showApiWarning = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ReviewRow(line: ReviewLine, products: List<Product>, vm: ImportViewModel) {
    var menu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    val product = products.firstOrNull { it.id == line.productId }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(line.keep, { vm.update(line.copy(keep = it)) })
                Text(line.rawName, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var qtyText by remember(line.key) { mutableStateOf(TimeUtil.num(line.qty, 3)) }
                NumberField("Кол-во", qtyText, { v -> qtyText = v; v.toNumberOrNull()?.takeIf { it > 0 }?.let { vm.update(line.copy(qty = it)) } }, Modifier.weight(1f), line.unit.label)
                Text(line.price?.let { "${TimeUtil.num(it, 2)} ₪" } ?: "")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        product == null -> "Не найдено в каталоге"
                        line.match == MatchKind.FUZZY -> "Похоже на: ${product.name}"
                        else -> "→ ${product.name}"
                    },
                    color = if (product == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { menu = true }) { Text("Выбрать") }
                TextButton(onClick = { create = true }) { Text("Новый") }
                DropdownMenu(menu, { menu = false }) {
                    products.filter { !it.untracked }.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { vm.update(line.copy(productId = p.id, match = MatchKind.EXACT)); menu = false })
                    }
                }
            }
        }
    }
    if (create) NewProductDialog(line, onDismiss = { create = false }) { name, unit, k, p -> vm.createProduct(line, name, unit, k, p); create = false }
}

@Composable
private fun NewProductDialog(line: ReviewLine, onDismiss: () -> Unit, onCreate: (String, MeasureUnit, Double, Double) -> Unit) {
    var name by remember { mutableStateOf(line.rawName) }
    var unit by remember { mutableStateOf(line.unit) }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый продукт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Название") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeasureUnit.entries.forEach { u -> FilterChip(unit == u, { unit = u }, { Text(u.label) }) }
                }
                NumberField("Ккал на 100 г", kcal, { kcal = it })
                NumberField("Белок на 100 г", protein, { protein = it })
                Text("Название чека сохранится как псевдоним.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onCreate(name, unit, (kcal.toNumberOrNull() ?: 0.0).coerceIn(0.0, 900.0), (protein.toNumberOrNull() ?: 0.0).coerceIn(0.0, 100.0))
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun LabelPreview(l: com.ration.app.domain.importing.ParsedLabel, products: List<Product>, onSave: (Boolean, Boolean, Long?) -> Unit) {
    var bar by remember { mutableStateOf(false) }
    var pbar by remember { mutableStateOf(false) }
    var replaces by remember { mutableStateOf<Product?>(null) }
    var menu by remember { mutableStateOf(false) }
    SectionTitle("Этикетка")
    Text(l.name)
    Text("${TimeUtil.num(l.kcalPer100)} ккал · ${TimeUtil.num(l.proteinPer100)} г белка на 100 г" +
        (l.portionGrams?.let { " · порция ${TimeUtil.num(it)} г = ${TimeUtil.num(l.kcalPerPortion ?: 0.0)} ккал / ${TimeUtil.num(l.proteinPerPortion ?: 0.0)} г" } ?: ""))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(bar, { bar = it }); Text("Батончик")
        Checkbox(pbar, { pbar = it }); Text("протеиновый")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Заменяет: ${replaces?.name ?: "—"}", Modifier.weight(1f))
        TextButton(onClick = { menu = true }) { Text("Выбрать") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("—") }, onClick = { replaces = null; menu = false })
            products.filter { !it.untracked }.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { replaces = p; menu = false }) }
        }
    }
    Button(onClick = { onSave(bar, pbar, replaces?.id) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить продукт") }
}
