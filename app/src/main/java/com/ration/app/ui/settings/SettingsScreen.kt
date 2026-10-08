package com.ration.app.ui.settings

import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.domain.model.Sex
import androidx.compose.material3.Checkbox
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.BuildConfig
import com.ration.app.data.repo.BackupRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.data.settings.SecureKeyStore
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.backup.BackupCodec
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.SlotType
import com.ration.app.notifications.ReminderScheduler
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.ConfirmDialog
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.TimeField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val keys: SecureKeyStore,
    private val backup: BackupRepository,
    private val scheduler: ReminderScheduler,
    private val plans: PlanRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    fun hasKey() = keys.hasApiKey()
    fun canExact() = scheduler.canScheduleExact()

    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch {
        repo.update(t)
        scheduler.rescheduleAll()
    }

    /** Смена расписания или напоминаний слотов: сегодня и завтра пересобираются (время и напоминания). */
    fun updateSlots(t: (AppSettings) -> AppSettings) = viewModelScope.launch {
        plans.settingsWithSchedule()
        repo.update(t)
        plans.applySchedule()
    }

    fun setApiKey(k: String?) { keys.setApiKey(k); if (k.isNullOrBlank()) update { it.copy(claudeApiEnabled = false) } }

    fun export(uri: Uri, password: String, includeDocuments: Boolean) = viewModelScope.launch {
        runCatching { backup.export(uri, password.takeIf { it.isNotEmpty() }?.toCharArray(), includeDocuments && password.isNotEmpty()) }
            .onSuccess { _messages.tryEmit("Резервная копия сохранена") }
            .onFailure { _messages.tryEmit("Ошибка экспорта: ${it.message}") }
    }

    fun import(uri: Uri, password: String) = viewModelScope.launch {
        runCatching {
            val text = backup.readText(uri)
            if (BackupCodec.isEncrypted(text) && password.isEmpty()) error("Файл зашифрован: введите пароль")
            backup.import(text, password.takeIf { it.isNotEmpty() }?.toCharArray())
        }.onSuccess { _messages.tryEmit("Данные восстановлены") }
            .onFailure { _messages.tryEmit("Импорт отклонён: ${it.message}. Данные не изменены.") }
    }

    fun deleteAll() = viewModelScope.launch {
        backup.deleteAll()
        plans.ensurePlan(plans.today())
        _messages.tryEmit("Все данные удалены, справочники засеяны заново")
    }
}

private fun granted(context: Context, perm: String) = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    var backupPassword by remember { mutableStateOf("") }
    var showExportWarning by remember { mutableStateOf(false) }
    var withDocs by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.export(it, backupPassword, withDocs) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it, backupPassword) } }

    Scaffold(topBar = { BackTopBar("Настройки", { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            InfoCard {
                Text("«Рацион» не является медицинским приложением: он показывает цели и предупреждения по заданным правилам и не заменяет врача.",
                    style = MaterialTheme.typography.bodySmall)
            }

            SectionTitle("Разрешения")
            key(refresh) {
                val notifOk = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
                PermissionRow("Уведомления", notifOk, "Без разрешения напоминания не приходят, остальное работает.") {
                    if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val exactOk = vm.canExact()
                PermissionRow("Точные будильники", exactOk, "Без разрешения напоминания приходят с задержкой до нескольких минут.") {
                    if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }

            SectionTitle("Цели")
            IntRow("Ккал: минимум", s.kcalMin) { v -> vm.update { it.copy(kcalMin = v) } }
            IntRow("Ккал: максимум", s.kcalMax) { v -> vm.update { it.copy(kcalMax = v) } }
            IntRow("Ккал: ориентир", s.kcalTarget) { v -> vm.update { it.copy(kcalTarget = v) } }
            IntRow("Белок: минимум, г", s.proteinMin) { v -> vm.update { it.copy(proteinMin = v) } }
            IntRow("Белок: максимум, г", s.proteinMax) { v -> vm.update { it.copy(proteinMax = v) } }
            IntRow("Белок: ориентир, г", s.proteinTarget) { v -> vm.update { it.copy(proteinTarget = v.coerceIn(0, 400)) } }
            IntRow("Вода: цель, мл", s.waterGoalMl) { v -> vm.update { it.copy(waterGoalMl = v.coerceIn(250, 6000)) } }

            var veg by remember(s.vegQuickGrams) { mutableStateOf(s.vegQuickGrams.joinToString(", ")) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(veg, { veg = it.take(40) }, label = { Text("Овощи: быстрые граммовки") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val list = veg.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.filter { it in 10..1000 }.distinct().take(5)
                    if (list.isNotEmpty()) vm.update { it.copy(vegQuickGrams = list) }
                }) { Text("OK") }
            }

            SectionTitle("Профиль (для расчёта формы)")
            // 20.1: значения не придумываются; пустой вес — расчёт не выполняется
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sex.entries.forEach { x -> FilterChip(s.sex == x, { vm.update { it.copy(sex = x) } }, { Text(x.label) }) }
            }
            IntRow("Возраст, лет", s.ageYears) { v -> vm.update { it.copy(ageYears = v.coerceIn(14, 110)) } }
            IntRow("Рост, см", s.heightCm) { v -> if (v in 100..250) vm.update { it.copy(heightCm = v) } }
            DecRow("Целевой вес, кг", s.targetWeight) { v -> if (v in 30.0..300.0) vm.update { it.copy(targetWeightKg = v) } }
            DecRow("Коэффициент активности (PAL) 1,2–1,5", s.pal) { v -> if (v in 1.2..1.5) vm.update { it.copy(pal = v) } }
            Text("1,2 — сидячая работа; 1,3 — немного ходьбы; 1,4–1,5 — много на ногах. Тренировки считаются отдельно.",
                style = MaterialTheme.typography.bodySmall)
            DecRow("Темп снижения, кг/нед", s.lossPaceKgPerWeek) { v -> if (v in 0.1..1.0) vm.update { it.copy(lossPaceKgPerWeek = v) } }
            Text("Текущий вес берётся из журнала «Вес и давление».", style = MaterialTheme.typography.bodySmall)

            SectionTitle("Время приёмов")
            // 19.8: одно расписание шести приёмов, без типов дня
            val times = s.times()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlotType.entries.forEach { slot ->
                    TimeField(slot.title, times[slot] ?: 0, { m -> vm.updateSlots { st -> st.copy(slotTimes = st.times() + (slot to m)) } },
                        Modifier.padding(vertical = 2.dp).fillMaxWidth(0.45f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Напоминать о перекусах (С, П, Е)")
                    Text("Завтрак, обед и ужин напоминают всегда.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(s.snackReminders, { on -> vm.updateSlots { it.copy(snackReminders = on) } })
            }

            SectionTitle("Закупки и напоминания")
            IntRow("Порог закупки, %", s.buyThresholdPct) { v -> vm.update { it.copy(buyThresholdPct = v.coerceIn(1, 100)) } }
            IntRow("Срочный порог, %", s.urgentThresholdPct) { v -> vm.update { it.copy(urgentThresholdPct = v.coerceIn(0, it.buyThresholdPct)) } }
            IntRow("Напоминание об отметке через, мин", s.markReminderDelayMin) { v -> vm.update { it.copy(markReminderDelayMin = v.coerceIn(15, 600)) } }
            TimeField("Вечерний прогноз", s.forecastTime, { m -> vm.update { it.copy(forecastTime = m) } })
            TimeField("Проверка закупок", s.shoppingCheckTime, { m -> vm.update { it.copy(shoppingCheckTime = m) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Тихие часы с", s.quietStart, { m -> vm.update { it.copy(quietStart = m) } }, Modifier.weight(1f))
                TimeField("до", s.quietEnd, { m -> vm.update { it.copy(quietEnd = m) } }, Modifier.weight(1f))
            }
            TimeField("Кофейный лимит", s.coffeeLimit, { m -> vm.update { it.copy(coffeeLimit = m) } })
            DayPicker("День взвешивания", s.weighDay) { d -> vm.update { it.copy(weighDay = d) } }
            TimeField("Время взвешивания", s.weighTime, { m -> vm.update { it.copy(weighTime = m) } })
            DayPicker("День заготовки", s.prepDay) { d -> vm.update { it.copy(prepDay = d) } }
            TimeField("Время заготовки", s.prepTime, { m -> vm.update { it.copy(prepTime = m) } })

            SectionTitle("Защита")
            val canBio = remember { BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Блокировка биометрией или PIN")
                    if (!canBio) Text("На устройстве не настроены биометрия или PIN.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(s.biometricLock, { on -> vm.update { it.copy(biometricLock = on) } }, enabled = canBio || s.biometricLock)
            }

            SectionTitle("Режим Claude API")
            if (!BuildConfig.API_MODE_AVAILABLE) {
                Text("В этой сборке (offline) сети нет: распознавание работает через «Скопировать промпт». Режим API доступен только в сборке api.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                ApiSection(s, vm)
            }

            SectionTitle("Резервная копия")
            Text("Экспорт и импорт всей базы в JSON через системный выбор файла. API-ключ в копию не входит.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(backupPassword, { backupPassword = it.take(128) }, label = { Text("Пароль шифрования (необязательно)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text("Пароль нигде не сохраняется. Без него зашифрованную копию не восстановить.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(withDocs && backupPassword.isNotEmpty(), { withDocs = it }, enabled = backupPassword.isNotEmpty())
                Text("Включить документы (только с паролем)")
            }
            Text("Тренировки и анализы входят в копию всегда; файлы документов — только по этой отметке и только в зашифрованной копии.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = { showExportWarning = true }) { Text("Экспорт") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Импорт") }
            }

            SectionTitle("Данные")
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Text("Удалить все данные", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showExportWarning) ConfirmDialog(
        "Данные о здоровье",
        "Файл будет содержать вес, давление, тренировки, анализы и журнал питания" + (if (withDocs && backupPassword.isNotEmpty()) ", файлы документов" else "") + (if (backupPassword.isEmpty()) " в открытом виде. Задайте пароль, чтобы зашифровать копию." else ", зашифрованные паролем.") +
            " Храните его в надёжном месте.",
        confirm = "Экспортировать",
        onConfirm = { exportLauncher.launch("ration-backup.json") },
        onDismiss = { showExportWarning = false },
    )
    if (confirmDelete) ConfirmDialog(
        "Удалить все данные?",
        "Будут удалены база, настройки, API-ключ, файлы отчётов и документы (с затиранием). Справочники засеются заново. Действие необратимо.",
        confirm = "Удалить", onConfirm = vm::deleteAll, onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun PermissionRow(title: String, ok: Boolean, whenOff: String, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(if (ok) "разрешено" else "выключено. $whenOff", style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        if (!ok) TextButton(onClick = onRequest) { Text("Разрешить") }
    }
}

@Composable
private fun DecRow(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(TimeUtil.num(value, 2)) }
    NumberField(label, text, { v -> text = v; v.toNumberOrNull()?.let(onChange) }, Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

@Composable
private fun IntRow(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    NumberField(label, text, { v ->
        text = v.filter(Char::isDigit)
        text.toIntOrNull()?.takeIf { it in 0..10_000 }?.let(onChange)
    }, Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(label: String, iso: Int, onChange: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..7).forEach { d -> FilterChip(iso == d, { onChange(d) }, { Text(TimeUtil.dayShort(d)) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiSection(s: AppSettings, vm: SettingsViewModel) {
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(vm.hasKey()) }
    var warn by remember { mutableStateOf(false) }
    Text("Необязательно. Фото чека или этикетки отправляется в Claude API по вашему ключу; запрос платный. Другой сетевой активности нет.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(key, { key = it.trim().take(200) }, label = { Text(if (hasKey) "Ключ сохранён — ввести новый" else "API-ключ") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.setApiKey(key); hasKey = key.isNotBlank() || hasKey; key = "" }, enabled = key.isNotBlank()) { Text("Сохранить ключ") }
        if (hasKey) OutlinedButton(onClick = { vm.setApiKey(null); hasKey = false }) { Text("Удалить ключ") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Режим Claude API", Modifier.weight(1f))
        Switch(s.claudeApiEnabled && hasKey, { on -> if (on && !s.claudeWarningAccepted) warn = true else vm.update { it.copy(claudeApiEnabled = on) } }, enabled = hasKey)
    }
    Text("Модель", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppSettings.CLAUDE_MODELS.forEach { m -> FilterChip(s.claudeModel == m, { vm.update { it.copy(claudeModel = m) } }, { Text(m) }) }
    }
    if (warn) ConfirmDialog("Платный режим", "Каждое распознавание — платный запрос по вашему ключу. Отправляются только фото и промпт.",
        confirm = "Включить", onConfirm = { vm.update { it.copy(claudeApiEnabled = true, claudeWarningAccepted = true) } }, onDismiss = { warn = false })
}
