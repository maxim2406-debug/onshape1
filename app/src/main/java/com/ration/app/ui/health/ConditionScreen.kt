package com.ration.app.ui.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.MainActivity
import com.ration.app.data.db.entity.LabResult
import com.ration.app.data.repo.HealthInsights
import com.ration.app.data.repo.HealthRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.health.AreaColor
import com.ration.app.domain.health.Condition
import com.ration.app.domain.health.ConditionReport
import com.ration.app.domain.health.LabRow
import com.ration.app.domain.health.LabStatus
import com.ration.app.domain.health.Labs
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

fun dmy(day: Long): String = LocalDate.ofEpochDay(day).let { "%02d.%02d.%04d".format(it.dayOfMonth, it.monthValue, it.year) }

@HiltViewModel
class ConditionViewModel @Inject constructor(
    private val health: HealthRepository,
    private val insights: HealthInsights,
) : ViewModel() {
    val labs = health.labs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val documents = health.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val report = MutableStateFlow<ConditionReport?>(null)
    val rules get() = health.rules
    val today: Long get() = health.today()

    fun refresh() = viewModelScope.launch { report.value = runCatching { insights.condition() }.getOrNull() }

    fun save(r: LabResult) = viewModelScope.launch { health.saveLab(r); refresh() }
    fun delete(r: LabResult) = viewModelScope.launch { health.deleteLab(r); refresh() }

    suspend fun preview(text: String): List<LabRow> = Labs.parse(text, rules, health.labsList())

    fun import(rows: List<LabRow>, documentId: Long?, done: (Int) -> Unit) = viewModelScope.launch {
        val list = Labs.toResults(rows).map { it.copy(documentId = documentId) }
        health.insertLabs(list)
        refresh()
        done(list.size)
    }
}

private fun areaColor(c: AreaColor) = when (c) {
    AreaColor.GREEN -> Color(0xFF2E7D32)
    AreaColor.YELLOW -> Color(0xFFF9A825)
    AreaColor.RED -> Color(0xFFC62828)
    AreaColor.NONE -> Color(0xFF9E9E9E)
}

/** «Состояние» (20.4): карта по 5 областям, ≤5 рекомендаций с «почему», показатели анализов, импорт. Под биометрией. */
@Composable
fun ConditionScreen(nav: NavController, activity: MainActivity) = HealthGate(nav, activity, "Состояние") { ConditionContent(nav) }

@Composable
private fun ConditionContent(nav: NavController, vm: ConditionViewModel = hiltViewModel()) {
    val labs by vm.labs.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<LabResult?>(null) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    Scaffold(topBar = { BackTopBar("Состояние", { nav.popBackStack() }) {
        TextButton(onClick = { nav.navigate("documents") }) { Text("Документы") }
    } }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                InfoCard { Text(Condition.DISCLAIMER, style = MaterialTheme.typography.bodySmall) }
                SectionTitle("Карта состояния")
                val r = report
                if (r == null) Text("Считаю…") else r.areas.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.ration.app.ui.components.Dot(areaColor(a.color), 14)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(a.title, fontWeight = FontWeight.SemiBold)
                            Text(a.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (r != null && r.advice.isNotEmpty()) {
                    SectionTitle("Рекомендации")
                    r.advice.take(5).forEach { a ->
                        InfoCard {
                            Text(a.text, fontWeight = FontWeight.Medium)
                            Text("Почему: ${a.why}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                SectionTitle("Анализы")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = LabResult(day = vm.today, indicator = "", value = 0.0) }) { Text("+ Показатель") }
                    OutlinedButton(onClick = { importing = true }) { Text("Импорт") }
                }
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Запрос", Labs.PROMPT))
                    scope.launch { snackbar.showSnackbar("Запрос скопирован: вставьте его в Claude вместе с фото бланка") }
                }) { Text("Скопировать запрос для Claude") }
                if (labs.isEmpty()) Text("Результатов пока нет.", modifier = Modifier.padding(vertical = 8.dp))
            }
            val groups = labs.groupBy { it.indicator }.toList().sortedBy { it.first.lowercase() }
            items(groups, key = { it.first }) { (name, list) ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    list.sortedByDescending { it.day }.take(4).forEach { r ->
                        val e = Labs.eval(r, vm.rules)
                        val arrow = Labs.trend(list, r)?.arrow ?: ""
                        val color = when {
                            e.critical -> MaterialTheme.colorScheme.error
                            e.status == LabStatus.HIGH || e.status == LabStatus.LOW -> Color(0xFFE65100)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 2.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${dmy(r.day)}  ${TimeUtil.num(r.value, 2)} ${r.unit} $arrow — ${e.status.label}" + if (e.critical) " · критично" else "", color = color)
                                val range = listOfNotNull(e.low?.let { "от ${TimeUtil.num(it, 2)}" }, e.high?.let { "до ${TimeUtil.num(it, 2)}" }).joinToString(" ")
                                if (range.isNotEmpty()) Text("Норма $range" + if (!e.fromBlank) " (ориентир)" else " (с бланка)", style = MaterialTheme.typography.bodySmall)
                                r.documentId?.let { id -> docs.firstOrNull { it.id == id }?.let { Text("Документ: ${it.title}", style = MaterialTheme.typography.bodySmall) } }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            item { Text(Condition.DOCTOR + " Лекарства приложение не советует.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp)) }
        }
    }
    editing?.let { r -> LabDialog(r, docs.map { it.id to it.title }, onSave = { vm.save(it); editing = null }, onDelete = { vm.delete(r); editing = null },
        onDismiss = { editing = null }) }
    if (importing) LabImportDialog(vm, docs.map { it.id to it.title }, onDone = { n -> importing = false; scope.launch { snackbar.showSnackbar("Импортировано: $n") } },
        onDismiss = { importing = false })
}

@Composable
private fun LabDialog(r: LabResult, docs: List<Pair<Long, String>>, onSave: (LabResult) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(r.indicator) }
    var value by remember { mutableStateOf(if (r.id == 0L) "" else TimeUtil.num(r.value, 3)) }
    var unit by remember { mutableStateOf(r.unit) }
    var low by remember { mutableStateOf(r.refLow?.let { TimeUtil.num(it, 3) } ?: "") }
    var high by remember { mutableStateOf(r.refHigh?.let { TimeUtil.num(it, 3) } ?: "") }
    var date by remember { mutableStateOf(dmy(r.day)) }
    var doc by remember { mutableStateOf(r.documentId) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (r.id == 0L) "Новый показатель" else "Показатель") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Показатель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField("Значение", value, { value = it }, Modifier.fillMaxWidth())
                OutlinedTextField(unit, { unit = it.take(20) }, label = { Text("Единица") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Норма от", low, { low = it }, Modifier.weight(1f))
                    NumberField("до", high, { high = it }, Modifier.weight(1f))
                }
                Text("Пустая норма — используется ориентир справочника.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата ДД.ММ.ГГГГ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (docs.isNotEmpty()) {
                    Text("Документ", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.clickable { doc = null }.padding(2.dp)) { Text((if (doc == null) "● " else "○ ") + "без документа") }
                    docs.take(10).forEach { (id, t) -> Row(Modifier.clickable { doc = id }.padding(2.dp)) { Text((if (doc == id) "● " else "○ ") + t) } }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = value.toNumberOrNull(); val d = Labs.parseDate(date)
                error = when {
                    name.isBlank() -> "Укажите показатель"
                    v == null -> "Значение — число"
                    d == null -> "Дата ДД.ММ.ГГГГ"
                    else -> null
                }
                if (error == null) onSave(r.copy(indicator = name.trim(), value = v!!, unit = unit.trim(), refLow = low.toNumberOrNull(),
                    refHigh = high.toNumberOrNull(), day = d!!, documentId = doc))
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (r.id != 0L) TextButton(onClick = onDelete) { Text("Удалить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun LabImportDialog(vm: ConditionViewModel, docs: List<Pair<Long, String>>, onDone: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<LabRow>?>(null) }
    var doc by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт анализов") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Формат: показатель | значение | единица | норма от | норма до | дата", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it; rows = null }, label = { Text("Строки") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                if (docs.isNotEmpty()) {
                    Text("Привязать к документу", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.clickable { doc = null }.padding(2.dp)) { Text((if (doc == null) "● " else "○ ") + "без документа") }
                    docs.take(10).forEach { (id, t) -> Row(Modifier.clickable { doc = id }.padding(2.dp)) { Text((if (doc == id) "● " else "○ ") + t) } }
                }
                rows?.let { list ->
                    val ok = list.count { it.error == null && !it.duplicate }
                    Text("Будет импортировано: $ok из ${list.size}", fontWeight = FontWeight.SemiBold)
                    list.forEach { r ->
                        val bad = r.error != null || r.duplicate
                        Text(
                            "${r.lineNo}. ${r.indicator} ${r.value?.let { TimeUtil.num(it, 3) } ?: "—"} ${r.unit} ${r.day?.let { dmy(it) } ?: ""}" +
                                when {
                                    r.error != null -> " — ошибка: ${r.error}"
                                    r.duplicate -> " — дубликат, пропущен"
                                    !r.known -> " — нет в справочнике (норма только с бланка)"
                                    else -> ""
                                },
                            color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val list = rows
            if (list == null) TextButton(onClick = { scope.launch { rows = vm.preview(text) } }) { Text("Проверить") }
            else TextButton(onClick = { vm.import(list, doc, onDone) }, enabled = list.any { it.error == null && !it.duplicate }) { Text("Импортировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
