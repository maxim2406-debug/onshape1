package com.ration.app.ui.health

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.MainActivity
import com.ration.app.data.db.entity.HealthDocument
import com.ration.app.data.repo.DocumentStore
import com.ration.app.data.repo.HealthRepository
import com.ration.app.domain.health.Labs
import com.ration.app.domain.model.DocType
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Файл, выбранный или присланный, до сохранения: байты в памяти, на диск — только зашифрованными. */
class IncomingDoc(val bytes: ByteArray, val mime: String, val name: String)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthRepository,
) : ViewModel() {
    val documents = health.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val labs = health.labs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val today: Long get() = health.today()

    /** Чтение выбранного файла с проверкой типа и размера (недоверенный ввод). */
    suspend fun readIncoming(uri: Uri, hintMime: String?): Result<IncomingDoc> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            val mime = (cr.getType(uri) ?: hintMime)?.lowercase()
            require(mime in DocumentStore.MIMES) { "Только PDF, JPG или PNG" }
            val size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            require(size <= DocumentStore.MAX_BYTES) { "Файл больше 20 МБ" }
            val bytes = cr.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    require(out.size() <= DocumentStore.MAX_BYTES) { "Файл больше 20 МБ" }
                }
                out.toByteArray()
            } ?: error("Файл недоступен")
            val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Документ"
            IncomingDoc(bytes, mime!!, name.substringBeforeLast('.').take(100))
        }
    }

    fun save(doc: IncomingDoc, day: Long, type: DocType, title: String, issuer: String, note: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { health.addDocument(doc.bytes, doc.mime, day, type, title, issuer, note) }.onFailure { onError(it.message ?: "Ошибка") }
    }

    fun update(d: HealthDocument) = viewModelScope.launch { health.updateDocument(d) }
    fun delete(d: HealthDocument) = viewModelScope.launch { health.deleteDocument(d) }

    /**
     * Просмотр: PDF расшифровывается во временный файл cache/view только на время рендера и сразу затирается;
     * страницы держатся только в памяти.
     */
    suspend fun render(d: HealthDocument, widthPx: Int): Result<List<Bitmap>> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = health.documentBytes(d)
            if (d.mime == "application/pdf") {
                val dir = File(context.cacheDir, "view").apply { mkdirs() }
                dir.listFiles()?.forEach { DocumentStore.wipeFile(it) }
                val tmp = File(dir, "v.pdf")
                try {
                    tmp.writeBytes(bytes)
                    val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(pfd)
                    try {
                        (0 until minOf(r.pageCount, MAX_PAGES)).map { i ->
                            val p = r.openPage(i)
                            try {
                                val w = widthPx.coerceIn(400, 1600)
                                val bmp = Bitmap.createBitmap(w, (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            } finally { p.close() }
                        }
                    } finally { r.close(); pfd.close() }
                } finally { DocumentStore.wipeFile(tmp) }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (opts.outWidth / sample > 2400 || opts.outHeight / sample > 2400) sample *= 2
                listOf(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("Не удалось открыть изображение"))
            }
        }
    }

    companion object { const val MAX_PAGES = 30 }
}

/** «Документы» (20.4): список, добавление через выбор файла или «Поделиться», просмотр внутри приложения, удаление с затиранием. */
@Composable
fun DocumentsScreen(nav: NavController, activity: MainActivity) = HealthGate(nav, activity, "Документы") { DocumentsContent(nav, activity) }

@Composable
private fun DocumentsContent(nav: NavController, activity: MainActivity, vm: DocumentsViewModel = hiltViewModel()) {
    val docs by vm.documents.collectAsStateWithLifecycle()
    val labs by vm.labs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var incoming by remember { mutableStateOf<IncomingDoc?>(null) }
    var editing by remember { mutableStateOf<HealthDocument?>(null) }
    var viewing by remember { mutableStateOf<HealthDocument?>(null) }
    var deleting by remember { mutableStateOf<HealthDocument?>(null) }
    var filter by remember { mutableStateOf<DocType?>(null) }
    fun accept(uri: Uri, mime: String?) = scope.launch {
        vm.readIncoming(uri, mime).onSuccess { incoming = it }.onFailure { snackbar.showSnackbar(it.message ?: "Ошибка") }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { accept(it, null) } }
    val shared by activity.sharedDocument
    LaunchedEffect(shared) {
        shared?.let { (uri, mime) -> activity.sharedDocument.value = null; accept(uri, mime) }
    }
    Scaffold(
        topBar = { BackTopBar("Документы", { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(DocumentStore.MIMES.toTypedArray()) }) { Text("+ Документ") }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(filter == null, { filter = null }, { Text("Все") })
                    DocType.entries.forEach { t -> FilterChip(filter == t, { filter = t }, { Text(t.label) }) }
                }
                Text("Файлы хранятся только на телефоне в зашифрованном виде. Добавить можно здесь или через «Поделиться» из другого приложения.",
                    style = MaterialTheme.typography.bodySmall)
                if (docs.isEmpty()) Text("Документов нет.", modifier = Modifier.padding(vertical = 16.dp))
            }
            items(docs.filter { filter == null || it.type == filter }, key = { it.id }) { d ->
                Row(Modifier.fillMaxWidth().clickable { viewing = d }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontWeight = FontWeight.Medium)
                        val n = labs.count { it.documentId == d.id }
                        Text("${dmy(d.day)} · ${d.type.label}" + (if (d.issuer.isNotBlank()) " · ${d.issuer}" else "") +
                            " · ${if (d.mime == "application/pdf") "PDF" else "фото"} ${d.sizeBytes / 1024} КБ" + (if (n > 0) " · показателей: $n" else ""),
                            style = MaterialTheme.typography.bodySmall)
                        if (d.note.isNotBlank()) Text(d.note, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { editing = d }) { Text("Изменить") }
                    TextButton(onClick = { deleting = d }) { Text("Удалить") }
                }
                HorizontalDivider()
            }
            item { Text(" ", modifier = Modifier.padding(40.dp)) }
        }
    }
    incoming?.let { inc ->
        DocMetaDialog(null, inc.name, vm.today, onSave = { day, type, title, issuer, note ->
            vm.save(inc, day, type, title, issuer, note) { scope.launch { snackbar.showSnackbar(it) } }
            incoming = null
        }, onDismiss = { incoming = null })
    }
    editing?.let { d ->
        DocMetaDialog(d, d.title, d.day, onSave = { day, type, title, issuer, note ->
            vm.update(d.copy(day = day, type = type, title = title, issuer = issuer, note = note)); editing = null
        }, onDismiss = { editing = null })
    }
    deleting?.let { d ->
        ConfirmDialog("Удалить документ?", "Файл будет затёрт без возможности восстановления. Показатели анализов останутся без ссылки на документ.",
            "Удалить", onConfirm = { vm.delete(d); deleting = null }, onDismiss = { deleting = null })
    }
    viewing?.let { d -> DocViewer(vm, d) { viewing = null } }
}

@Composable
private fun DocMetaDialog(
    existing: HealthDocument?, title0: String, day0: Long,
    onSave: (Long, DocType, String, String, String) -> Unit, onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(dmy(day0)) }
    var type by remember { mutableStateOf(existing?.type ?: DocType.LAB) }
    var title by remember { mutableStateOf(title0) }
    var issuer by remember { mutableStateOf(existing?.issuer ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Новый документ" else "Документ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата ДД.ММ.ГГГГ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocType.entries.forEach { t -> FilterChip(type == t, { type = t }, { Text(t.label) }) }
                }
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(issuer, { issuer = it.take(200) }, label = { Text("Лаборатория или врач") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(1000) }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = Labs.parseDate(date)
                error = when { d == null -> "Дата ДД.ММ.ГГГГ"; title.isBlank() -> "Укажите название"; else -> null }
                if (error == null) onSave(d!!, type, title.trim(), issuer.trim(), note.trim())
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun DocViewer(vm: DocumentsViewModel, d: HealthDocument, onClose: () -> Unit) {
    var pages by remember { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 2
    LaunchedEffect(d.id) { vm.render(d, width).onSuccess { pages = it }.onFailure { error = it.message ?: "Не удалось открыть" } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
            when {
                error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                pages == null -> Text("Открываю…", Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.fillMaxSize().background(Color(0xFF616161)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(pages!!) { b ->
                        Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                    if (d.mime == "application/pdf" && pages!!.size == DocumentsViewModel.MAX_PAGES) item {
                        Text("Показаны первые ${DocumentsViewModel.MAX_PAGES} страниц.", Modifier.padding(16.dp), color = Color.White)
                    }
                }
            }
        }
    }
}
