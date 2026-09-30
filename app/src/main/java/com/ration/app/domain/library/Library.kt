package com.ration.app.domain.library

import com.ration.app.data.db.entity.Product
import com.ration.app.domain.importing.ImportLimits
import com.ration.app.domain.importing.ImportParser
import com.ration.app.domain.importing.ProductMatcher
import com.ration.app.domain.model.CookState
import com.ration.app.domain.model.MeasureUnit

/** Основа ввода пищевой ценности в форме (13.1.4). */
enum class NutritionBasis(val label: String) { PER_100G("на 100 г"), PER_100ML("на 100 мл"), PER_PORTION("на порцию") }

object NutritionInput {
    /** Пересчёт в «на 100 г» (для мл — на 100 мл, хранение одинаковое). */
    fun per100(value: Double, basis: NutritionBasis, portionGrams: Double?): Double? = when (basis) {
        NutritionBasis.PER_100G, NutritionBasis.PER_100ML -> value
        NutritionBasis.PER_PORTION -> portionGrams?.takeIf { it > 0 }?.let { value / it * 100.0 }
    }
}

data class BulkRow(val lineNo: Int, val name: String, val kcal100: Double, val protein100: Double,
                   val category: String?, val gramsPerPiece: Double?, val uncertain: Boolean)

data class BulkError(val lineNo: Int, val raw: String, val message: String)
data class BulkSimilar(val row: BulkRow, val existing: Product)

data class BulkResult(
    val ok: List<BulkRow>,
    val errors: List<BulkError>,
    /** Похоже на существующий продукт — добавить только по подтверждению. */
    val similar: List<BulkSimilar>,
)

object LibraryParser {
    private val sep = Regex("""\s*[|;\t]\s*""")

    /**
     * Массовое добавление (13.1.5): название | ккал/100 | белок/100 | категория | вес штуки.
     * Первые три поля обязательны. «# ...» в конце строки — комментарий (например «# не уверен»).
     */
    fun parse(text: String, existing: List<Product>, similarThreshold: Double = 0.86): BulkResult {
        require(text.length <= ImportLimits.MAX_TEXT_BYTES) { "Текст больше 1 МБ" }
        val ok = mutableListOf<BulkRow>(); val errors = mutableListOf<BulkError>(); val similar = mutableListOf<BulkSimilar>()
        val seen = mutableSetOf<String>()
        text.lineSequence().take(ImportLimits.MAX_LINES).forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            var line = raw.trim().removePrefix("﻿")
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val hash = line.indexOf('#')
            val uncertain = hash >= 0 && line.substring(hash).contains("не уверен")
            if (hash >= 0) line = line.substring(0, hash).trim()
            val f = line.split(sep).map { it.trim() }
            val name = f.getOrNull(0)?.take(ImportLimits.MAX_NAME).orEmpty()
            if (name.isBlank()) { errors += BulkError(lineNo, raw, "нет названия"); return@forEachIndexed }
            val kcal = f.getOrNull(1)?.let(ImportParser::parseNumber)
            val prot = f.getOrNull(2)?.let(ImportParser::parseNumber)
            if (kcal == null || prot == null) { errors += BulkError(lineNo, raw, "нет ккал или белка" + if (uncertain) " (не уверен)" else ""); return@forEachIndexed }
            if (kcal !in 0.0..900.0 || prot !in 0.0..100.0) { errors += BulkError(lineNo, raw, "значения вне диапазона"); return@forEachIndexed }
            val gpp = f.getOrNull(4)?.let(ImportParser::parseNumber)?.takeIf { it > 0 && it < 10_000 }
            val norm = FoodSearch.normalize(name)
            if (!seen.add(norm)) { errors += BulkError(lineNo, raw, "повтор строки"); return@forEachIndexed }
            val row = BulkRow(lineNo, name, kcal, prot, f.getOrNull(3)?.takeIf { it.isNotBlank() }?.lowercase(), gpp, uncertain)
            val exact = existing.firstOrNull { FoodSearch.normalize(it.name) == norm }
            if (exact != null) { errors += BulkError(lineNo, raw, "уже есть: ${exact.name}"); return@forEachIndexed }
            val sim = findSimilar(name, existing, similarThreshold)
            if (sim != null) similar += BulkSimilar(row, sim) else ok += row
        }
        return BulkResult(ok, errors, similar)
    }

    /** «Уже есть похожий продукт» (13.1.6). */
    fun findSimilar(name: String, existing: List<Product>, threshold: Double = 0.86): Product? {
        val n = FoodSearch.normalize(name)
        if (n.isEmpty()) return null
        return existing.asSequence()
            .map { it to maxOf(lev(n, FoodSearch.normalize(it.name)), it.aliases.maxOfOrNull { a -> lev(n, FoodSearch.normalize(a)) } ?: 0.0) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }?.first
    }

    private fun lev(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - ProductMatcher.levenshtein(a, b).toDouble() / maxOf(a.length, b.length)

    private val stateWords = Regex("""(?U)\b(сыр(ой|ая|ое|ые)|варён(ый|ая|ое|ые)|варен(ый|ая|ое|ые)|сух(ой|ая|ое|ие)|готов(ый|ая|ое|ые)|на гриле|жарен(ый|ая|ое|ые))\b""")

    private fun baseName(n: String) = stateWords.replace(FoodSearch.normalize(n), "").replace(Regex("\\s+"), " ").trim()

    /** Подсказка «вес сырой или готовый?» (13.2.7): есть продукт с тем же названием в другом состоянии. */
    fun rawCookedCounterpart(p: Product, all: List<Product>): Product? {
        val state = p.cooked ?: return null
        val base = baseName(p.name)
        if (base.isEmpty()) return null
        return all.firstOrNull { o -> o.id != p.id && o.cooked != null && o.cooked != state && !o.hidden && baseName(o.name) == base }
    }

    fun cookedFromName(name: String): CookState? {
        val n = FoodSearch.normalize(name)
        return when {
            Regex("""(?U)\bсыр(ой|ая|ое|ые)\b""").containsMatchIn(n) || Regex("""(?U)\bсух(ой|ая|ое|ие)\b""").containsMatchIn(n) -> CookState.RAW
            Regex("""(?U)\b(варен|варён|готов|жарен)""").containsMatchIn(n) || n.contains("на гриле") -> CookState.COOKED
            else -> null
        }
    }

    fun unitFor(row: BulkRow): MeasureUnit = MeasureUnit.G
}

object LibraryPrompts {
    const val PLACEHOLDER = "{{список названий продуктов приложения}}"

    private const val FRIDGE_LIST = """Преврати список продуктов из моего холодильника в строки для приложения питания.

Формат каждой строки:
название | ккал на 100 г | белок на 100 г | категория | вес штуки г

Правила:
1. Бери средние справочные значения. Если не уверен, оставь число пустым и добавь в конце строки «# не уверен».
2. Для мяса и рыбы пиши в названии «сырое» или «варёное». Для круп — «сухая» или «варёная».
3. Категория — одно из: птица, говядина, свинина, баранина, рыба, яйца и молочные, крупы, хлеб, овощи, фрукты, орехи и масла, соусы, прочее.
4. Вес штуки указывай только для штучных продуктов.
5. Без пояснений и без таблиц. Только строки.

Уже есть в приложении (не повторяй):
$PLACEHOLDER

Мой список:
"""

    private const val FRIDGE_PHOTO = """На фото холодильник, морозилка или полка с продуктами. Составь список продуктов для приложения питания.

Формат каждой строки:
название | количество | единица | приблизительно

Правила:
1. Единицы только: г, кг, мл, л, шт.
2. Если вес или объём написан на упаковке и виден, бери его. Если не виден, оцени на глаз и поставь в четвёртое поле слово «приблизительно».
3. Для овощей и фруктов штучно указывай шт, если нельзя оценить вес.
4. Названия бери из списка ниже, если продукт соответствует. Иначе пиши по-русски просто и точно.
5. Не включай напитки, приправы, пустые контейнеры и непищевые предметы.
6. Если не уверен, что это за продукт, выведи строку с префиксом # и опиши кратко, что видишь.
7. Без пояснений и без таблиц. Только строки.

Уже есть в приложении:
$PLACEHOLDER"""

    private fun names(catalog: List<Product>) = catalog.filter { !it.untracked && !it.hidden }.joinToString("\n") { it.name }

    fun fridgeList(catalog: List<Product>) = FRIDGE_LIST.replace(PLACEHOLDER, names(catalog))
    fun fridgePhoto(catalog: List<Product>) = FRIDGE_PHOTO.replace(PLACEHOLDER, names(catalog))
}
