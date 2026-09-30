package com.ration.app.domain.importing

import com.ration.app.data.db.entity.Product
import com.ration.app.domain.model.MeasureUnit

/** Строка покупки после нормализации: количество в г, мл или шт. */
data class ParsedPurchaseLine(
    val lineNo: Int,
    val raw: String,
    val name: String,
    val qty: Double,
    val unit: MeasureUnit,
    val price: Double?,
)

data class ParseError(val lineNo: Int, val raw: String, val message: String)

data class PurchaseParseResult(
    val lines: List<ParsedPurchaseLine>,
    val errors: List<ParseError>,
    /** Строки с # — неразборчивые позиции чека, показываются пользователю как подсказка. */
    val comments: List<String>,
)

data class ParsedLabel(
    val name: String,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val portionGrams: Double?,
    val kcalPerPortion: Double?,
    val proteinPerPortion: Double?,
)

object ImportLimits {
    /** Импорт текста — недоверенный ввод (11.3.4). */
    const val MAX_TEXT_BYTES = 1_000_000
    const val MAX_LINES = 2_000
    const val MAX_NAME = 120
    const val MAX_QTY = 1_000_000.0
    const val MAX_PRICE = 100_000.0
}

object ImportParser {
    private val separators = Regex("""\s*[|;\t]\s*""")
    private val numberUnit = Regex("""^([0-9]+(?:[.,][0-9]+)?)\s*([^\d\s.,]+\.?)?$""")

    fun parseNumber(s: String): Double? {
        val t = s.trim().replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Нормализация единиц: кг → г, л → мл. Возвращает (множитель, единица хранения). */
    fun normalizeUnit(u: String): Pair<Double, MeasureUnit>? = when (u.trim().lowercase().removeSuffix(".")) {
        "г", "гр", "g", "gr" -> 1.0 to MeasureUnit.G
        "кг", "kg" -> 1000.0 to MeasureUnit.G
        "мл", "ml" -> 1.0 to MeasureUnit.ML
        "л", "l" -> 1000.0 to MeasureUnit.ML
        "шт", "pcs", "pc" -> 1.0 to MeasureUnit.PCS
        else -> null
    }

    fun parsePurchases(text: String): PurchaseParseResult {
        require(text.toByteArray(Charsets.UTF_8).size <= ImportLimits.MAX_TEXT_BYTES) { "Текст больше 1 МБ" }
        val lines = mutableListOf<ParsedPurchaseLine>()
        val errors = mutableListOf<ParseError>()
        val comments = mutableListOf<String>()
        text.lineSequence().take(ImportLimits.MAX_LINES).forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) {
                comments += line.removePrefix("#").trim()
                return@forEachIndexed
            }
            when (val r = parsePurchaseLine(lineNo, line)) {
                is ParsedPurchaseLine -> lines += r
                is ParseError -> errors += r
            }
        }
        return PurchaseParseResult(lines, errors, comments)
    }

    /**
     * CSV (запятые, кавычки) → формат с «|». Строки, где уже есть «|», «;» или табуляция, не трогаем.
     */
    fun csvToPipes(text: String): String = text.lineSequence().joinToString("\n") { line ->
        if (line.isBlank() || line.trimStart().startsWith("#") || line.contains('|') || line.contains(';') || line.contains('\t')) line
        else {
            val fields = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { fields += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            fields += cur.toString()
            fields.joinToString(" | ") { it.trim() }
        }
    }

    /** Запись без разделителей: «Картофель 1.5кг» или «Яйцо 10 шт». */
    private val inlineQty = Regex("""^(.*\D)\s+([0-9]+(?:[.,][0-9]+)?)\s*(кг|г|гр|мл|л|шт)?\.?$""", RegexOption.IGNORE_CASE)

    private fun parsePurchaseLine(lineNo: Int, line: String): Any {
        var parts = line.split(separators).map { it.trim() }
        if (parts.size == 1) {
            inlineQty.find(line)?.let { m ->
                parts = listOf(m.groupValues[1].trim(), m.groupValues[2], m.groupValues[3])
            }
        }
        val name = parts.getOrNull(0)?.take(ImportLimits.MAX_NAME).orEmpty()
        if (name.isBlank()) return ParseError(lineNo, line, "Нет названия")
        val qtyField = parts.getOrNull(1).orEmpty()
        var unitField = parts.getOrNull(2).orEmpty()
        var priceField = parts.getOrNull(3).orEmpty()

        var qty: Double?
        // Слитная запись: «1.5кг» или «250 г» в поле количества.
        val m = numberUnit.find(qtyField.replace(" ", "").let { if (it.isEmpty()) qtyField else it })
        if (m != null) {
            qty = parseNumber(m.groupValues[1])
            val embeddedUnit = m.groupValues[2]
            if (embeddedUnit.isNotEmpty()) {
                // единица внутри количества: третье поле, если число, — это цена
                if (unitField.isNotEmpty() && normalizeUnit(unitField) == null && parseNumber(unitField) != null && priceField.isEmpty()) {
                    priceField = unitField
                }
                unitField = embeddedUnit
            }
        } else if (qtyField.isEmpty()) {
            qty = 1.0
        } else {
            return ParseError(lineNo, line, "Не удалось прочитать количество «$qtyField»")
        }
        if (qty == null || qty <= 0 || qty > ImportLimits.MAX_QTY) return ParseError(lineNo, line, "Недопустимое количество")
        val (factor, unit) = if (unitField.isEmpty()) 1.0 to MeasureUnit.PCS
        else normalizeUnit(unitField) ?: return ParseError(lineNo, line, "Неизвестная единица «$unitField»")
        val price = if (priceField.isEmpty()) null else {
            val p = parseNumber(priceField.replace("₪", "").replace("шек", ""))
                ?: return ParseError(lineNo, line, "Не удалось прочитать цену «$priceField»")
            if (p < 0 || p > ImportLimits.MAX_PRICE) return ParseError(lineNo, line, "Недопустимая цена")
            p
        }
        return ParsedPurchaseLine(lineNo, line, name, qty * factor, unit, price)
    }

    /**
     * Строка этикетки: название | ккал/100 г | белок/100 г | порция г | ккал/порция | белок/порция.
     * Если на 100 г нет, но есть порция — пересчитываем на 100 г.
     */
    fun parseLabel(text: String): ParsedLabel? {
        require(text.length <= ImportLimits.MAX_TEXT_BYTES) { "Текст больше 1 МБ" }
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val p = line.split(separators).map { it.trim() }
        val name = p.getOrNull(0)?.take(ImportLimits.MAX_NAME)?.takeIf { it.isNotBlank() } ?: return null
        fun num(i: Int) = p.getOrNull(i)?.let(::parseNumber)?.takeIf { it >= 0 && it < 100_000 }
        var kcal100 = num(1)
        var prot100 = num(2)
        val portion = num(3)?.takeIf { it > 0 }
        val kcalPortion = num(4)
        val protPortion = num(5)
        if (portion != null) {
            if (kcal100 == null && kcalPortion != null) kcal100 = kcalPortion / portion * 100
            if (prot100 == null && protPortion != null) prot100 = protPortion / portion * 100
        }
        if (kcal100 == null || prot100 == null) return null
        if (kcal100 > 900 || prot100 > 100) return null
        return ParsedLabel(
            name, round1(kcal100), round1(prot100), portion,
            kcalPortion ?: portion?.let { round1(kcal100 * it / 100) },
            protPortion ?: portion?.let { round1(prot100 * it / 100) },
        )
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
}

enum class MatchKind { EXACT, ALIAS, FUZZY, NONE }

data class ProductMatch(val product: Product?, val kind: MatchKind, val score: Double = 0.0)

/** Сопоставление названия с каталогом: точное → псевдоним → нечёткое. */
object ProductMatcher {
    fun normalize(s: String): String = s.lowercase()
        .replace('ё', 'е')
        .replace(Regex("""[֑-ׇ]"""), "") // огласовки иврита
        .replace(Regex("""["'׳״`().,%]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun match(name: String, catalog: List<Product>, fuzzyThreshold: Double = 0.72): ProductMatch {
        val n = normalize(name)
        if (n.isEmpty()) return ProductMatch(null, MatchKind.NONE)
        catalog.firstOrNull { normalize(it.name) == n }?.let { return ProductMatch(it, MatchKind.EXACT, 1.0) }
        catalog.firstOrNull { p -> p.aliases.any { normalize(it) == n } }?.let { return ProductMatch(it, MatchKind.ALIAS, 1.0) }
        var best: Product? = null
        var bestScore = 0.0
        for (p in catalog) {
            val candidates = listOf(p.name) + p.aliases
            for (c in candidates) {
                val score = similarity(n, normalize(c))
                if (score > bestScore) { bestScore = score; best = p }
            }
        }
        return if (best != null && bestScore >= fuzzyThreshold) ProductMatch(best, MatchKind.FUZZY, bestScore)
        else ProductMatch(null, MatchKind.NONE, bestScore)
    }

    /** Похожесть: максимум из нормированного Левенштейна и покрытия слов. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val lev = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
        val ta = a.split(' ').filter { it.length > 2 }.toSet()
        val tb = b.split(' ').filter { it.length > 2 }.toSet()
        val token = if (ta.isEmpty() || tb.isEmpty()) 0.0 else {
            val common = ta.count { x -> tb.any { y -> y.startsWith(x.take(5)) || x.startsWith(y.take(5)) } }
            common.toDouble() / minOf(ta.size, tb.size) * 0.9
        }
        return maxOf(lev, token)
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}

object Prompts {
    const val CATALOG_PLACEHOLDER = "{{список названий продуктов приложения}}"

    private const val RECEIPT_TEMPLATE = """Ты получишь фото или текст чека из израильского магазина (иврит). Преобразуй его в список покупок.

Правила:
1. Выводи только строки формата: название | количество | единица | цена. Без пояснений и без таблиц.
2. Название бери из списка ниже, если товар соответствует. Если нет в списке, пиши название по-русски как на чеке.
3. Единицы только: г, кг, мл, л, шт. Вес и объём бери из названия товара (например, 250 г, 1 л). Если не указан, ставь шт.
4. Пакеты по нескольку штук раскрывай в общее количество.
5. Не включай пакеты, депозит на тару, скидки и не пищевые товары.
6. Неразборчивую строку выводи с префиксом # и оригинальным текстом.

Список названий продуктов приложения:
$CATALOG_PLACEHOLDER"""

    const val LABEL = """На фото этикетка продукта. Выведи одну строку формата:
название | ккал на 100 г | белок на 100 г | размер порции г | ккал на порцию | белок на порцию
Без пояснений. Если значения нет на этикетке, оставь поле пустым. Числа с точкой, без единиц."""

    fun receipt(catalog: List<Product>): String =
        RECEIPT_TEMPLATE.replace(CATALOG_PLACEHOLDER, catalog.filter { !it.untracked }.joinToString("\n") { it.name })
}
