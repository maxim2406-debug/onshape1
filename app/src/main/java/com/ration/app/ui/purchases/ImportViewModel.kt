package com.ration.app.ui.purchases

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ration.app.data.api.Recognizer
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.Product
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.data.repo.PurchaseInput
import com.ration.app.data.settings.SecureKeyStore
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.importing.ImportLimits
import com.ration.app.domain.importing.ImportParser
import com.ration.app.domain.importing.MatchKind
import com.ration.app.domain.importing.ParsedLabel
import com.ration.app.domain.importing.ProductMatcher
import com.ration.app.domain.importing.Prompts
import com.ration.app.domain.model.MeasureUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ImportMode { RECEIPT, LABEL }

data class ReviewLine(
    val key: Int,
    val rawName: String,
    val qty: Double,
    val unit: MeasureUnit,
    val price: Double?,
    val productId: Long?,
    val match: MatchKind,
    val keep: Boolean = true,
)

data class ImportUi(
    val mode: ImportMode = ImportMode.RECEIPT,
    val text: String = "",
    val lines: List<ReviewLine> = emptyList(),
    val errors: List<String> = emptyList(),
    val comments: List<String> = emptyList(),
    val label: ParsedLabel? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false,
    val apiAvailable: Boolean = false,
    val apiEnabled: Boolean = false,
    val apiWarningAccepted: Boolean = false,
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogRepository,
    private val inventory: InventoryRepository,
    private val recognizer: Recognizer,
    private val settings: SettingsRepository,
    private val keys: SecureKeyStore,
) : ViewModel() {
    val ui = MutableStateFlow(ImportUi(apiAvailable = recognizer.available))
    val products: StateFlow<List<Product>> = catalog.products.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val s = settings.current()
            ui.value = ui.value.copy(apiEnabled = recognizer.available && s.claudeApiEnabled && keys.hasApiKey(), apiWarningAccepted = s.claudeWarningAccepted)
        }
    }

    fun setMode(m: ImportMode) { ui.value = ui.value.copy(mode = m, lines = emptyList(), label = null, errors = emptyList(), message = null) }
    fun setText(t: String) { ui.value = ui.value.copy(text = t.take(ImportLimits.MAX_TEXT_BYTES)) }
    fun consumeMessage() { ui.value = ui.value.copy(message = null) }

    suspend fun prompt(): String = if (ui.value.mode == ImportMode.RECEIPT) Prompts.receipt(catalog.allProducts()) else Prompts.LABEL

    fun parse() = viewModelScope.launch {
        val u = ui.value
        try {
            if (u.mode == ImportMode.LABEL) {
                val l = ImportParser.parseLabel(u.text)
                ui.value = u.copy(label = l, message = if (l == null) "Не удалось разобрать строку этикетки" else null)
                return@launch
            }
            val r = ImportParser.parsePurchases(ImportParser.csvToPipes(u.text))
            val catalogList = catalog.allProducts()
            val lines = r.lines.mapIndexed { i, l ->
                val m = ProductMatcher.match(l.name, catalogList)
                ReviewLine(i, l.name, l.qty, l.unit, l.price, m.product?.id, m.kind)
            }
            ui.value = u.copy(lines = lines, errors = r.errors.map { "Строка ${it.lineNo}: ${it.message}" }, comments = r.comments,
                message = if (lines.isEmpty()) "Строк покупок не найдено" else null)
        } catch (e: IllegalArgumentException) {
            ui.value = u.copy(message = e.message)
        }
    }

    fun update(line: ReviewLine) { ui.value = ui.value.copy(lines = ui.value.lines.map { if (it.key == line.key) line else it }) }

    fun createProduct(line: ReviewLine, name: String, unit: MeasureUnit, kcal: Double, protein: Double) = viewModelScope.launch {
        val id = catalog.saveProduct(Product(name = name.trim().take(120), unit = unit, kcalPer100 = kcal, proteinPer100 = protein, category = "Своё"))
        update(line.copy(productId = id, match = MatchKind.EXACT))
    }

    fun savePurchase(store: String) = viewModelScope.launch {
        val lines = ui.value.lines.filter { it.keep }
        if (lines.any { it.productId == null }) {
            ui.value = ui.value.copy(message = "Свяжите все строки с продуктами или снимите галочку")
            return@launch
        }
        inventory.savePurchase(inventory.today(), store, lines.map { PurchaseInput(it.productId!!, it.rawName, it.qty, it.unit, it.price) })
        ui.value = ui.value.copy(saved = true, message = "Добавлено в кладовую: ${lines.size} поз.", lines = emptyList(), text = "")
    }

    fun saveLabel(label: ParsedLabel, isBar: Boolean, isProteinBar: Boolean, replaces: Long?) = viewModelScope.launch {
        catalog.saveCustomFood(CustomFood(name = label.name, kcalPer100 = label.kcalPer100, proteinPer100 = label.proteinPer100,
            portionGrams = label.portionGrams, kcalPerPortion = label.kcalPerPortion, proteinPerPortion = label.proteinPerPortion,
            replacesProductId = replaces, isBar = isBar || isProteinBar, isProteinBar = isProteinBar))
        ui.value = ui.value.copy(message = "Продукт «${label.name}» сохранён в справочник", label = null, text = "")
    }

    /** Импорт .txt/.csv через системный выбор файла — недоверенный ввод, не больше 1 МБ. */
    fun loadFile(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytesLimited(ImportLimits.MAX_TEXT_BYTES + 1)
                    if (bytes.size > ImportLimits.MAX_TEXT_BYTES) null else String(bytes, Charsets.UTF_8)
                }
            }.getOrNull()
        }
        if (text == null) ui.value = ui.value.copy(message = "Файл не прочитан или больше 1 МБ")
        else { ui.value = ui.value.copy(text = text); parse() }
    }

    fun acceptApiWarning() = viewModelScope.launch {
        settings.update { it.copy(claudeWarningAccepted = true) }
        ui.value = ui.value.copy(apiWarningAccepted = true)
    }

    fun recognizePhoto(uri: Uri) = viewModelScope.launch {
        ui.value = ui.value.copy(busy = true)
        try {
            val answer = recognizer.recognize(uri, null, prompt())
            ui.value = ui.value.copy(text = answer.take(ImportLimits.MAX_TEXT_BYTES), busy = false)
            parse()
        } catch (e: Exception) {
            ui.value = ui.value.copy(busy = false, message = (e.message ?: "Ошибка") + ". Можно скопировать промпт и вставить ответ вручную.")
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n); total += n
    }
    return out.toByteArray()
}
