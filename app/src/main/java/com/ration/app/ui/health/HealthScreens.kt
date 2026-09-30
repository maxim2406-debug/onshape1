package com.ration.app.ui.health

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.MainActivity
import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.data.repo.HealthRepository
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.report.DoctorReport
import com.ration.app.domain.report.HealthStats
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.ui.nav.SecureScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthRepository,
    private val meals: MealRepository,
    private val settingsRepo: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    val weights = health.weights.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bp = health.bp.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    val today: Long get() = LocalDate.now(clock).toEpochDay()
    val nowMillis: Long get() = clock.millis()

    fun addWeight(kg: Double) = viewModelScope.launch { runCatching { health.addWeight(kg) }.onFailure { _messages.tryEmit(it.message ?: "Ошибка") } }
    fun addBp(s: Int, d: Int, p: Int?) = viewModelScope.launch { runCatching { health.addBp(s, d, p) }.onFailure { _messages.tryEmit(it.message ?: "Ошибка") } }
    fun deleteWeight(w: WeightLog) = viewModelScope.launch { health.deleteWeight(w) }
    fun deleteBp(b: BpLog) = viewModelScope.launch { health.deleteBp(b) }
    fun setBpSeries(on: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(bpSeriesUntilDay = if (on) today + 6 else null) }
    }

    /** Отчёт: файл в cache/reports, выдача через FileProvider с временным доступом. */
    fun exportReport(from: LocalDate, to: LocalDate, pdf: Boolean, share: (Intent) -> Unit) = viewModelScope.launch {
        val s = settingsRepo.current()
        val logs = meals.logsRange(from.toEpochDay(), to.toEpochDay())
        val quick = meals.quickRange(from.toEpochDay(), to.toEpochDay())
        val bpList = health.bpList()
        val r = DoctorReport.build(from, to, clock.zone, s, health.weightsList(), bpList, logs, quick)
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            if (pdf) File(dir, "ration-report.pdf").also { writePdf(it, DoctorReport.toText(r)) }
            else File(dir, "ration-report.csv").also { it.writeText(DoctorReport.toCsv(r, bpList, clock.zone)) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (pdf) "application/pdf" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        share(Intent.createChooser(send, "Отчёт для врача"))
    }

    private fun writePdf(file: File, lines: List<String>) {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 10f; isAntiAlias = true }
        val pageW = 595; val pageH = 842; val margin = 40f; val lineH = 14f
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        var y = margin
        val wrapped = lines.flatMap { wrap(it, paint, pageW - 2 * margin) }
        for (line in wrapped) {
            if (y + lineH > pageH - margin) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()); y = margin
            }
            page.canvas.drawText(line, margin, y, paint); y += lineH
        }
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun wrap(s: String, p: Paint, width: Float): List<String> {
        if (s.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var cur = ""
        for (w in s.split(' ')) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(t) > width && cur.isNotEmpty()) { out += cur; cur = w } else cur = t
        }
        out += cur
        return out
    }
}

@Composable
fun LineChart(points: List<Pair<Float, Float>>, color: Color, modifier: Modifier = Modifier, second: List<Pair<Float, Float>> = emptyList(), secondColor: Color = Color.Gray) {
    val all = points + second
    if (all.size < 2) { Text("Недостаточно данных для графика", style = MaterialTheme.typography.bodySmall); return }
    val minX = all.minOf { it.first }; val maxX = all.maxOf { it.first }
    val minY = all.minOf { it.second }; val maxY = all.maxOf { it.second }
    val axis = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        fun map(p: Pair<Float, Float>) = Offset(
            if (maxX == minX) size.width / 2 else (p.first - minX) / (maxX - minX) * size.width,
            if (maxY == minY) size.height / 2 else size.height - (p.second - minY) / (maxY - minY) * size.height,
        )
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
        listOf(points to color, second to secondColor).forEach { (pts, c) ->
            pts.zipWithNext().forEach { (a, b) -> drawLine(c, map(a), map(b), strokeWidth = 4f) }
            pts.forEach { drawCircle(c, 5f, map(it)) }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(TimeUtil.num(minY.toDouble()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("макс ${TimeUtil.num(maxY.toDouble())}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun HealthScreen(nav: NavController, activity: MainActivity, vm: HealthViewModel = hiltViewModel()) {
    SecureScreen(activity)
    val weights by vm.weights.collectAsStateWithLifecycle()
    val bp by vm.bp.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var kg by remember { mutableStateOf("") }
    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }
    var pulse by remember { mutableStateOf("") }
    val zone = java.time.ZoneId.systemDefault()
    Scaffold(topBar = { BackTopBar("Вес и давление", { nav.popBackStack() }) { TextButton(onClick = { nav.navigate("report") }) { Text("Отчёт") } } },
        snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                SectionTitle("Вес")
                val goal = s.startWeightKg - s.weightLossGoalKg
                val last = weights.lastOrNull()
                Text("Цель: ${TimeUtil.num(goal)} кг (−${TimeUtil.num(s.weightLossGoalKg)} от ${TimeUtil.num(s.startWeightKg)})" +
                    (last?.let { " · сейчас ${TimeUtil.num(it.kg)} кг, осталось ${TimeUtil.num(it.kg - goal)}" } ?: ""))
                val r2 = HealthStats.weightRatePerWeek(weights, vm.today, 15)
                val r4 = HealthStats.weightRatePerWeek(weights, vm.today, 29)
                Text("Темп: 2 недели ${r2?.let { TimeUtil.num(it, 2) + " кг/нед" } ?: "—"}, 4 недели ${r4?.let { TimeUtil.num(it, 2) + " кг/нед" } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall)
                LineChart(weights.map { it.day.toFloat() to it.kg.toFloat() }, MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Вес утром", kg, { kg = it }, Modifier.weight(1f), "кг")
                    Button(onClick = { kg.toNumberOrNull()?.let { vm.addWeight(it); kg = "" } }) { Text("Записать") }
                }
            }
            items(weights.takeLast(8).reversed(), key = { "w${it.id}" }) { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${TimeUtil.dateRu(LocalDate.ofEpochDay(w.day))} — ${TimeUtil.num(w.kg)} кг", Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteWeight(w) }) { Text("Удалить") }
                }
            }
            item {
                SectionTitle("Давление")
                val avg = HealthStats.bpAverage(bp, vm.nowMillis - 7 * 86_400_000L, vm.nowMillis)
                Text(avg?.let { "Среднее за 7 дней: ${Math.round(it.systolic)}/${Math.round(it.diastolic)}" + (it.pulse?.let { p -> ", пульс ${Math.round(p)}" } ?: "") + " (${it.count} изм.)" } ?: "Нет измерений за 7 дней")
                LineChart(bp.map { (it.atMillis / 3_600_000f) to it.systolic.toFloat() }, Color(0xFFC62828),
                    second = bp.map { (it.atMillis / 3_600_000f) to it.diastolic.toFloat() }, secondColor = Color(0xFF1565C0))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Верхнее", sys, { sys = it.filter(Char::isDigit) }, Modifier.weight(1f))
                    NumberField("Нижнее", dia, { dia = it.filter(Char::isDigit) }, Modifier.weight(1f))
                    NumberField("Пульс", pulse, { pulse = it.filter(Char::isDigit) }, Modifier.weight(1f))
                }
                Button(onClick = {
                    val a = sys.toIntOrNull(); val b = dia.toIntOrNull()
                    if (a != null && b != null) { vm.addBp(a, b, pulse.toIntOrNull()); sys = ""; dia = ""; pulse = "" }
                }, modifier = Modifier.fillMaxWidth()) { Text("Записать давление") }
                val seriesOn = (s.bpSeriesUntilDay ?: -1) >= vm.today
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Серия измерений 7 дней (напоминания ${TimeUtil.hm(s.bpMorning)} и ${TimeUtil.hm(s.bpEvening)})", Modifier.weight(1f))
                    Switch(seriesOn, { vm.setBpSeries(it) })
                }
            }
            items(bp.takeLast(10).reversed(), key = { "b${it.id}" }) { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val t = TimeUtil.toLocal(b.atMillis, zone)
                    Text("${TimeUtil.dateShort(t.toLocalDate())} ${TimeUtil.hm(TimeUtil.minuteOf(t.toLocalTime()))} — ${b.systolic}/${b.diastolic}" + (b.pulse?.let { ", $it" } ?: ""), Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteBp(b) }) { Text("Удалить") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(nav: NavController, activity: MainActivity, vm: HealthViewModel = hiltViewModel()) {
    SecureScreen(activity)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var weeks by remember { mutableStateOf(4) }
    val to = LocalDate.ofEpochDay(vm.today)
    val from = to.minusWeeks(weeks.toLong()).plusDays(1)
    Scaffold(topBar = { BackTopBar("Отчёт для врача", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Период")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 8, 13, 26).forEach { w -> FilterChip(weeks == w, { weeks = w }, { Text("$w нед.") }) }
            }
            Text("${TimeUtil.dateRu(from)} — ${TimeUtil.dateRu(to)}")
            InfoCard {
                Text("В отчёт входят: вес, среднее давление, средние ккал и белок по дням, выполнение недельных правил. " +
                    "Файл содержит данные о здоровье — отправляйте только тому, кому доверяете.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { scope.launch { vm.exportReport(from, to, pdf = true) { context.startActivity(it) } } }, modifier = Modifier.fillMaxWidth()) { Text("Экспорт PDF") }
            OutlinedButton(onClick = { scope.launch { vm.exportReport(from, to, pdf = false) { context.startActivity(it) } } }, modifier = Modifier.fillMaxWidth()) { Text("Экспорт CSV") }
        }
    }
}
