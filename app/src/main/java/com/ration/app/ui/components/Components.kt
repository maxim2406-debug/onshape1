package com.ration.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ration.app.data.db.entity.RecipeStep
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.importing.ImportParser
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
        actions = { actions() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainTopBar(title: String, actions: @Composable () -> Unit = {}) {
    TopAppBar(title = { Text(title) }, actions = { actions() })
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun Dot(color: Color, size: Int = 12) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** Полоса прогресса с диапазоном цели. */
@Composable
fun RangeProgress(label: String, value: Double, min: Int, max: Int, unit: String) {
    val over = value > max
    val color = when {
        over -> MaterialTheme.colorScheme.error
        value >= min -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$label: ${Math.round(value)} $unit", style = MaterialTheme.typography.bodyMedium)
            val left = min - value
            Text(
                when {
                    over -> "перебор ${Math.round(value - max)} $unit"
                    left > 0 -> "осталось ${Math.round(left)}–${Math.round(max - value)} $unit"
                    else -> "в цели $min–$max"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (value / max).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
        )
    }
}

@Composable
fun InfoCard(title: String? = null, container: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(10)) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        suffix = if (suffix != null) { { Text(suffix) } } else null,
    )
}

fun String.toNumberOrNull(): Double? = ImportParser.parseNumber(this)

/** Поле времени ЧЧ:ММ. */
@Composable
fun TimeField(label: String, minute: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(minute) { mutableStateOf(TimeUtil.hm(minute)) }
    val parsed = TimeUtil.parseHm(text)
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.take(5)
            TimeUtil.parseHm(text)?.let(onChange)
        },
        isError = parsed == null,
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Да", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Шаги рецепта с таймерами. */
@Composable
fun RecipeSteps(steps: List<RecipeStep>) {
    steps.forEachIndexed { i, step ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}.", modifier = Modifier.width(24.dp), fontWeight = FontWeight.SemiBold)
            Text(step.text, modifier = Modifier.weight(1f))
            step.timerSeconds?.let { StepTimer(it) }
        }
    }
}

@Composable
fun StepTimer(seconds: Int) {
    var left by rememberSaveable { mutableIntStateOf(seconds) }
    var running by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(running) {
        while (running && left > 0) {
            delay(1000)
            left--
        }
        running = false
    }
    TextButton(onClick = {
        if (left == 0) left = seconds
        running = !running
    }) {
        val m = left / 60
        val s = left % 60
        Text(
            if (left == 0) "Готово" else (if (running) "⏸ " else "▶ ") + (if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)),
            color = if (left == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
