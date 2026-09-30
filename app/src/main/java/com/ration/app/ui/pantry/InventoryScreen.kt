package com.ration.app.ui.pantry

import android.content.Context
import android.net.Uri
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.api.Recognizer
import com.ration.app.data.db.entity.Product
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.Drafts
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.data.settings.SecureKeyStore
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.importing.ImportLimits
import com.ration.app.domain.importing.MatchKind
import com.ration.app.domain.inventory.InventoryLine
import com.ration.app.domain.inventory.InventoryMode
import com.ration.app.domain.inventory.InventoryParser
import com.ration.app.domain.inventory.InventoryPlanner
import com.ration.app.domain.inventory.InventoryRow
import com.ration.app.domain.inventory.UnitConv
import com.ration.app.domain.library.LibraryPrompts
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.ui.library.FoodPickerDialog
import com.ration.app.ui.library.ProductFormDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

data class InvLine(val key: Int, val line: InventoryLine, val product: Product?, val match: MatchKind)

data class InventoryUi(
    val text: String = "",
    val mode: InventoryMode = InventoryMode.SET,
    val lines: List<InvLine> = emptyList(),
    val errors: List<String> = emptyList(),
    val rows: List<InventoryRow> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val canUndo: Boolean = false,
    val apiEnabled: Boolean = false,
    val apiWarningAccepted: Boolean = false,
)

@HiltViewModel
class InventoryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogRepository,
    private val inventory: InventoryRepository,
    private val recognizer: Recognizer,
    private val settings: SettingsRepository,
    private val keys: SecureKeyStore,
    drafts: Drafts,
) : ViewModel() {
    val ui = MutableStateFlow(InventoryUi())
    private var nextKey = 100_000

    init {
        val shared = drafts.inventoryText
        drafts.inventoryText = null
        viewModelScope.launch {
            val s = settings.current()
            ui.value = ui.value.copy(
                apiEnabled = recognizer.available && s.claudeApiEnabled && keys.hasApiKey(), apiWarningAccepted = s.claudeWarningAccepted,
                canUndo = inventory.lastInventoryUndo != null,
            )
            if (!shared.isNullOrBlank()) { setText(shared); parse() }
        }
    }

    fun setText(t: String) { ui.value = ui.value.copy(text = t.take(ImportLimits.MAX_TEXT_BYTES)) }
    fun consumeMessage() { ui.value = ui.value.copy(message = null) }
    fun setMode(m: InventoryMode) { ui.value = ui.value.copy(mode = m); preview() }

    suspend fun listPrompt(): String = LibraryPrompts.fridgeList(catalog.allProducts())
    suspend fun photoPrompt(): String = LibraryPrompts.fridgePhoto(catalog.allProducts())

    fun parse() = viewModelScope.launch {
        val r = InventoryParser.parse(ui.value.text, LocalDate.now())
        val products = catalog.allProducts()
        val lines = r.lines.map { l ->
            val (p, kind) = InventoryParser.match(l, products)
            InvLine(l.lineNo, l, p, kind)
        }
        ui.value = ui.value.copy(lines = lines, errors = r.errors.map { "Строка ${it.lineNo}: ${it.message}" })
        preview()
    }

    fun setProduct(key: Int, p: Product) {
        ui.value = ui.value.copy(lines = ui.value.lines.map { if (it.key == key) it.copy(product = p, match = MatchKind.EXACT) else it })
        viewModelScope.launch { if (p.id > 0) ui.value.lines.firstOrNull { it.key == key }?.let { catalog.addAlias(p.id, it.line.name) } }
        preview()
    }

    fun setQty(key: Int, qty: Double) {
        ui.value = ui.value.copy(lines = ui.value.lines.map { if (it.key == key) it.copy(line = it.line.copy(qty = qty)) else it })
        preview()
    }

    fun toggleApprox(key: Int) {
        ui.value = ui.value.copy(lines = ui.value.lines.map { if (it.key == key) it.copy(line = it.line.copy(approx = !it.line.approx)) else it })
        preview()
    }

    fun remove(key: Int) { ui.value = ui.value.copy(lines = ui.value.lines.filter { it.key != key }); preview() }

    fun pickFor(key: Int, productId: Long?) = viewModelScope.launch {
        productId?.let { catalog.product(it) }?.let { setProduct(key, it) }
    }

    fun addManualId(productId: Long?) = viewModelScope.launch {
        productId?.let { catalog.product(it) }?.let { addManual(it) }
    }

    fun addManual(p: Product) {
        val l = InventoryLine(nextKey, p.name, if (p.unit == com.ration.app.domain.model.MeasureUnit.PCS) 1.0 else 100.0, p.unit, approx = false, expiresDay = null)
        ui.value = ui.value.copy(lines = ui.value.lines + InvLine(nextKey++, l, p, MatchKind.EXACT))
        preview()
    }

    /** Предпросмотр «было → станет» (17.2). Строки без продукта или без пересчёта единиц не применяются. */
    fun preview() = viewModelScope.launch {
        val u = ui.value
        val errors = mutableListOf<String>()
        val triples = u.lines.mapNotNull { l ->
            val p = l.product ?: return@mapNotNull null
            val q = UnitConv.toProductUnit(l.line.qty, l.line.unit, p)
            if (q == null) { errors += "${l.line.name}: нет веса штуки, укажите количество в ${p.unit.label}"; null } else Triple(p, l.line, q)
        }
        val rows = InventoryPlanner.plan(u.mode, triples, inventory.totals(), catalog.allProducts())
        ui.value = ui.value.copy(rows = rows, errors = ui.value.errors.filterNot { it.contains(": нет веса штуки") } + errors)
    }

    fun apply() = viewModelScope.launch {
        val u = ui.value
        if (u.rows.isEmpty()) return@launch
        ui.value = u.copy(busy = true)
        inventory.applyInventory(u.mode, u.rows)
        ui.value = ui.value.copy(busy = false, lines = emptyList(), rows = emptyList(), text = "", canUndo = true,
            message = "Остатки обновлены: ${u.rows.size} продукт(ов). Можно отменить.")
    }

    fun undo() = viewModelScope.launch {
        val ok = inventory.undoInventory()
        ui.value = ui.value.copy(canUndo = false, message = if (ok) "Инвентаризация отменена" else "Нечего отменять")
    }

    fun loadFile(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (out.size() <= ImportLimits.MAX_TEXT_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    val bytes = out.toByteArray()
                    if (bytes.size > ImportLimits.MAX_TEXT_BYTES) null else String(bytes, Charsets.UTF_8)
                }
            }.getOrNull()
        }
        if (text == null) ui.value = ui.value.copy(message = "Файл не прочитан или больше 1 МБ")
        else { setText(text); parse() }
    }

    fun acceptApiWarning() = viewModelScope.launch {
        settings.update { it.copy(claudeWarningAccepted = true) }
        ui.value = ui.value.copy(apiWarningAccepted = true)
    }

    fun recognizePhoto(uri: Uri) = viewModelScope.launch {
        ui.value = ui.value.copy(busy = true)
        try {
            val answer = recognizer.recognize(uri, null, photoPrompt())
            ui.value = ui.value.copy(busy = false)
            setText(answer); parse()
        } catch (e: Exception) {
            ui.value = ui.value.copy(busy = false, message = (e.message ?: "Ошибка") + ". Можно скопировать промпт и вставить ответ вручную.")
        }
    }
}

/** Инвентаризация (17): текст/файл/фото → проверка → предпросмотр → применить, с отменой. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(nav: NavController, vm: InventoryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showApiWarning by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf<InvLine?>(null) }
    var addManual by remember { mutableStateOf(false) }
    var confirmZero by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.consumeMessage() } }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadFile) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::recognizePhoto) }

    Scaffold(topBar = { BackTopBar("Инвентаризация", { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { clipboard.setText(AnnotatedString(vm.photoPrompt())); snackbar.showSnackbar("Промпт скопирован. Вставьте его в чат Claude вместе с фото холодильника.") } }) {
                    Text("Промпт для фото холодильника")
                }
                OutlinedButton(onClick = { scope.launch { clipboard.setText(AnnotatedString(vm.listPrompt())); snackbar.showSnackbar("Промпт для списка скопирован") } }) {
                    Text("Промпт для списка")
                }
                OutlinedButton(onClick = { vm.setText(clipboard.getText()?.text.orEmpty()); vm.parse() }) { Text("Вставить из буфера") }
                OutlinedButton(onClick = { fileLauncher.launch(arrayOf("text/plain", "text/csv", "text/comma-separated-values")) }) { Text("Файл .txt/.csv") }
                OutlinedButton(onClick = { addManual = true }) { Text("Добавить вручную") }
                if (ui.apiEnabled) OutlinedButton(onClick = {
                    if (ui.apiWarningAccepted) photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else showApiWarning = true
                }) { Text("Распознать по фото") }
            }
            OutlinedTextField(
                ui.text, vm::setText, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                label = { Text("название | количество | единица | ≈ | до ДД.ММ") },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.parse() }) { Text("Разобрать") }
                if (ui.canUndo) OutlinedButton(onClick = vm::undo) { Text("Отменить последнюю") }
                if (ui.busy) CircularProgressIndicator()
            }
            ui.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (ui.lines.isNotEmpty()) {
                SectionTitle("Строки")
                ui.lines.forEach { l ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(l.line.name + (l.line.expiresDay?.let { " · до ${TimeUtil.dateShort(LocalDate.ofEpochDay(it))}" } ?: ""), fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                var q by remember(l.key) { mutableStateOf(TimeUtil.num(l.line.qty, 3)) }
                                NumberField("Кол-во", q, { v -> q = v; v.toNumberOrNull()?.takeIf { it >= 0 }?.let { vm.setQty(l.key, it) } }, Modifier.weight(1f), l.line.unit.label)
                                FilterChip(l.line.approx, { vm.toggleApprox(l.key) }, { Text("≈") })
                                TextButton(onClick = { vm.remove(l.key) }) { Text("Убрать") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        l.product == null -> "Не найдено в библиотеке"
                                        l.match == MatchKind.FUZZY -> "Похоже на: ${l.product.name}"
                                        else -> "→ ${l.product.name}"
                                    },
                                    color = if (l.product == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { picking = l.key }) { Text("Выбрать") }
                                TextButton(onClick = { creating = l }) { Text("Новый") }
                            }
                        }
                    }
                }
            }

            SectionTitle("Режим")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InventoryMode.entries.forEach { m -> FilterChip(ui.mode == m, { vm.setMode(m) }, { Text(m.label) }) }
            }
            Text(
                when (ui.mode) {
                    InventoryMode.ADD -> "Количество добавится к текущему остатку новой партией."
                    InventoryMode.SET -> "Остаток перечисленных продуктов станет равен указанному; остальные не меняются."
                    InventoryMode.SET_ZERO -> "Перечисленные — как указано; все остальные отслеживаемые продукты обнулятся."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (ui.rows.isNotEmpty()) {
                SectionTitle("Предпросмотр")
                ui.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(r.product.name, Modifier.weight(1f), color = if (r.zeroed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text("${TimeUtil.num(r.before)} → ${if (r.approx) "≈" else ""}${TimeUtil.num(r.after)} ${r.product.unit.label}")
                    }
                }
                Button(
                    onClick = { if (ui.mode == InventoryMode.SET_ZERO && ui.rows.any { it.zeroed }) confirmZero = true else vm.apply() },
                    enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
                ) { Text("Применить") }
            }
        }
    }

    picking?.let { key ->
        FoodPickerDialog("Продукт для строки", onDismiss = { picking = null }, includeCustomFoods = false) { e ->
            vm.pickFor(key, e.productId)
            picking = null
        }
    }
    creating?.let { l ->
        ProductFormDialog(initialName = l.line.name, onDismiss = { creating = null }, pickLabel = "Добавить и выбрать") { e ->
            vm.pickFor(l.key, e.productId)
            creating = null
        }
    }
    if (addManual) {
        FoodPickerDialog("Добавить строку", onDismiss = { addManual = false }, includeCustomFoods = false) { e ->
            vm.addManualId(e.productId)
            addManual = false
        }
    }
    if (confirmZero) {
        val zeroed = ui.rows.filter { it.zeroed }
        AlertDialog(
            onDismissRequest = { confirmZero = false },
            title = { Text("Обнулить ${zeroed.size} продукт(ов)?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { zeroed.forEach { Text("• ${it.product.name}: ${TimeUtil.num(it.before)} ${it.product.unit.label} → 0") } } },
            confirmButton = { TextButton(onClick = { confirmZero = false; vm.apply() }) { Text("Обнулить и применить") } },
            dismissButton = { TextButton(onClick = { confirmZero = false }) { Text("Отмена") } },
        )
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
