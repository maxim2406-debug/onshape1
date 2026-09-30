package com.ration.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Product
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.domain.library.BulkResult
import com.ration.app.domain.library.FoodEntry
import com.ration.app.domain.library.LibraryParser
import com.ration.app.domain.library.LibraryPrompts
import com.ration.app.ui.components.BackTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(private val catalog: CatalogRepository) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    suspend fun product(e: FoodEntry): Product? = e.productId?.let { catalog.product(it) }
    fun favorite(e: FoodEntry) = viewModelScope.launch { product(e)?.let { catalog.setFavorite(it, !it.favorite) } }
    fun unhide(e: FoodEntry) = viewModelScope.launch { product(e)?.let { catalog.setHidden(it, false); _messages.tryEmit("«${it.name}» снова в поиске") } }
    fun remove(e: FoodEntry) = viewModelScope.launch {
        val p = product(e) ?: return@launch
        val hidden = catalog.removeProduct(p)
        _messages.tryEmit(if (hidden) "«${p.name}» используется — скрыт из поиска" else "«${p.name}» удалён")
    }
    suspend fun parseBulk(text: String): BulkResult = LibraryParser.parse(text, catalog.allProducts())
    fun addBulk(r: BulkResult, includeSimilar: Boolean) = viewModelScope.launch {
        val n = catalog.bulkAdd(r.ok + if (includeSimilar) r.similar.map { it.row } else emptyList())
        _messages.tryEmit("Добавлено продуктов: $n")
    }
    suspend fun fridgePrompt(): String = LibraryPrompts.fridgeList(catalog.allProducts())
}

@Composable
fun LibraryScreen(nav: NavController, vm: LibraryViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var adding by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var bulk by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackTopBar("Библиотека продуктов", { nav.popBackStack() }) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Массовое добавление") }, onClick = { menu = false; bulk = true })
                    DropdownMenuItem(text = { Text("Скопировать промпт для списка продуктов") }, onClick = {
                        menu = false
                        scope.launch { clipboard.setText(AnnotatedString(vm.fridgePrompt())); snackbar.showSnackbar("Промпт скопирован. Вставьте в чат Claude со списком или фото.") }
                    })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        FoodList(
            modifier = Modifier.padding(pad), showHiddenToggle = true,
            onPick = { e -> scope.launch { vm.product(e)?.let { editing = it } } },
            onAddNew = { adding = it },
            trailing = { e ->
                if (e.productId != null) {
                    var m by remember { mutableStateOf(false) }
                    IconButton(onClick = { m = true }) { Icon(Icons.Filled.MoreVert, "Действия") }
                    DropdownMenu(m, { m = false }) {
                        DropdownMenuItem(text = { Text(if (e.favorite) "Убрать из избранного" else "В избранное") }, onClick = { m = false; vm.favorite(e) })
                        DropdownMenuItem(text = { Text("Изменить") }, onClick = { m = false; scope.launch { vm.product(e)?.let { editing = it } } })
                        if (e.hidden) DropdownMenuItem(text = { Text("Вернуть в поиск") }, onClick = { m = false; vm.unhide(e) })
                        else DropdownMenuItem(text = { Text("Удалить или скрыть") }, onClick = { m = false; vm.remove(e) })
                    }
                }
            },
        )
    }
    adding?.let { n -> ProductFormDialog(initialName = n, onDismiss = { adding = null }) { adding = null; scope.launch { snackbar.showSnackbar("«${it.name}» в библиотеке") } } }
    editing?.let { p -> ProductFormDialog(editing = p, onDismiss = { editing = null }) { editing = null } }
    if (bulk) BulkDialog(vm) { bulk = false }
}

@Composable
private fun BulkDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<BulkResult?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Массовое добавление") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("название | ккал на 100 г | белок на 100 г | категория | вес штуки г", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { text = clipboard.getText()?.text.orEmpty() }) { Text("Вставить из буфера") }
                OutlinedTextField(text, { text = it; result = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                result?.let { r ->
                    Text("Готово к добавлению: ${r.ok.size}", style = MaterialTheme.typography.labelLarge)
                    r.ok.take(30).forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall) }
                    if (r.similar.isNotEmpty()) {
                        Text("Похожие на имеющиеся: ${r.similar.size}", style = MaterialTheme.typography.labelLarge)
                        r.similar.forEach { Text("• ${it.row.name} ≈ ${it.existing.name}", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (r.errors.isNotEmpty()) {
                        Text("Строки с ошибками: ${r.errors.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        r.errors.forEach { Text("${it.lineNo}: ${it.raw} — ${it.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            val r = result
            if (r == null) TextButton(onClick = { scope.launch { result = runCatching { vm.parseBulk(text) }.getOrNull() } }) { Text("Разобрать") }
            else Column {
                TextButton(onClick = { vm.addBulk(r, false); onDismiss() }, enabled = r.ok.isNotEmpty()) { Text("Добавить ${r.ok.size}") }
                if (r.similar.isNotEmpty()) TextButton(onClick = { vm.addBulk(r, true); onDismiss() }) { Text("Добавить всё равно, вместе с похожими") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
