package com.ration.app.domain.library

import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.Dish
import com.ration.app.data.db.entity.Product
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.ProductSource
import java.text.Collator
import java.util.Locale

/** Единая запись библиотеки: Product или CustomFood (13.1). */
data class FoodEntry(
    val id: String,
    val productId: Long?,
    val customFoodId: Long?,
    val name: String,
    val category: String,
    val unit: MeasureUnit,
    val kcal100: Double?,
    val protein100: Double?,
    val aliases: List<String>,
    val tags: List<String>,
    val source: ProductSource,
    val favorite: Boolean,
    val hidden: Boolean,
    val untracked: Boolean,
    val useCount: Int,
    val inStock: Boolean,
    val searchKey: String,
    /** Отдельное блюдо (19.4): порция со своим весом, ккал и белком. */
    val dish: Dish? = null,
) {
    val proteinPer100Kcal: Double?
        get() = if (kcal100 != null && protein100 != null && kcal100 > 0) protein100 / kcal100 * 100 else null

    companion object {
        fun of(p: Product, inStock: Boolean) = FoodEntry(
            "p:${p.id}", p.id, null, p.name, p.category, p.unit, p.kcalPer100, p.proteinPer100, p.aliases, p.tags,
            p.source, p.favorite, p.hidden, p.untracked, p.useCount, inStock,
            p.searchKey.ifEmpty { FoodSearch.buildKey(p.name, p.aliases, p.category, p.tags) },
        )

        /** Блюдо: ккал и белок на 100 г — только если порция в граммах; в строке показывается порция. */
        fun of(d: Dish, inStock: Boolean): FoodEntry {
            val per100 = d.unit != MeasureUnit.PCS && d.qty > 0
            return FoodEntry(
                "d:${d.id}", null, null, d.name, DISHES, d.unit, if (per100) d.kcal / d.qty * 100 else null,
                if (per100) d.protein / d.qty * 100 else null, emptyList(), d.tags, ProductSource.REFERENCE, false, d.hidden, false, 0, inStock,
                FoodSearch.buildKey(d.name, listOf(d.blockCode), DISHES, d.tags), dish = d,
            )
        }

        const val DISHES = "блюда"

        fun of(f: CustomFood): FoodEntry {
            val tags = buildList { if (f.isBar) add("bar") }
            return FoodEntry(
                "c:${f.id}", null, f.id, f.name, "свои продукты", MeasureUnit.G, f.kcalPer100, f.proteinPer100, emptyList(), tags,
                ProductSource.LABEL, false, false, false, 0, false, FoodSearch.buildKey(f.name, emptyList(), "свои продукты", tags),
            )
        }
    }
}

enum class SortMode(val label: String) {
    FREQUENT("Частые"),
    ALPHA_ASC("А→Я"), ALPHA_DESC("Я→А"),
    KCAL_ASC("Ккал ↑"), KCAL_DESC("Ккал ↓"),
    PROTEIN_ASC("Белок ↑"), PROTEIN_DESC("Белок ↓"),
    PROTEIN_PER_KCAL("Белок на 100 ккал"),
}

data class FoodFilters(
    val categories: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val inStockOnly: Boolean = false,
    val sources: Set<ProductSource> = emptySet(),
    val favoritesOnly: Boolean = false,
    val showHidden: Boolean = false,
    val includeUntracked: Boolean = true,
)

data class FoodHit(val entry: FoodEntry, val score: Int, val highlights: List<IntRange>)

object FoodSearch {
    private val niqqud = Regex("[֑-ׇ]")
    private val punct = Regex("""[^\p{L}\p{Nd}%]+""")
    private val finals = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')
    private val collator: Collator = Collator.getInstance(Locale("ru", "RU")).apply { strength = Collator.TERTIARY }

    /** Нормализация 16.1.2: регистр, ё=е, огласовки и конечные буквы иврита, пунктуация и лишние пробелы. */
    fun normalize(s: String): String {
        val lower = s.lowercase(Locale.ROOT).replace('ё', 'е')
        val noNiqqud = niqqud.replace(lower, "")
        val mapped = buildString(noNiqqud.length) { noNiqqud.forEach { append(finals[it] ?: it) } }
        return punct.replace(mapped, " ").trim()
    }

    fun buildKey(name: String, aliases: List<String>, category: String, tags: List<String>): String =
        normalize((listOf(name) + aliases + category + tags).joinToString(" "))

    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    /** Основа слова для «курица» → «кури…» (находит «куриная»): длинные слова без двух последних букв. */
    private fun stem(t: String): String? = if (t.length >= 5) t.take(t.length - 2) else null

    fun tokenMatches(key: String, t: String): Boolean {
        if (key.contains(t)) return true
        val st = stem(t) ?: return false
        return key.split(' ').any { it.startsWith(st) }
    }

    /** Каждое слово запроса должно найтись; порядок не важен. */
    fun matches(entry: FoodEntry, tokens: List<String>): Boolean = tokens.all { tokenMatches(entry.searchKey, it) }

    /** Ранжирование 16.1.5: начало слова в названии > подстрока в названии > алиасы и теги. */
    fun relevance(entry: FoodEntry, tokens: List<String>): Int {
        val nameWords = normalize(entry.name).split(' ')
        return tokens.sumOf { t ->
            val st = stem(t)
            when {
                nameWords.any { it.startsWith(t) } -> 3
                nameWords.any { it.contains(t) } || (st != null && nameWords.any { it.startsWith(st) }) -> 2
                else -> 1
            }.toInt()
        }
    }

    /** Подсветка совпадений в исходном названии (длина строки сохраняется: только регистр и ё→е). */
    fun highlights(name: String, tokens: List<String>): List<IntRange> {
        val lower = name.lowercase(Locale.ROOT).replace('ё', 'е')
        return tokens.mapNotNull { t ->
            val i = lower.indexOf(t)
            if (i >= 0) return@mapNotNull i until i + t.length
            val st = stem(t) ?: return@mapNotNull null
            val j = lower.indexOf(st)
            if (j >= 0) j until j + st.length else null
        }
    }

    fun compareNames(a: String, b: String): Int = collator.compare(a, b)

    private fun passes(e: FoodEntry, f: FoodFilters): Boolean {
        if (e.hidden && !f.showHidden) return false
        if (e.untracked && !f.includeUntracked) return false
        if (f.categories.isNotEmpty() && e.category !in f.categories) return false
        if (f.tags.isNotEmpty() && e.tags.none { it in f.tags }) return false
        if (f.inStockOnly && !e.inStock) return false
        if (f.sources.isNotEmpty() && e.source !in f.sources) return false
        if (f.favoritesOnly && !e.favorite) return false
        return true
    }

    /** Фильтр, затем порядок (16.2). Сортировка стабильная, вторичный ключ — алфавит, пустые значения в конце. */
    fun search(entries: List<FoodEntry>, query: String, sort: SortMode, filters: FoodFilters = FoodFilters()): List<FoodHit> {
        val tokens = tokens(query)
        val found = entries.asSequence()
            .filter { passes(it, filters) }
            .filter { tokens.isEmpty() || matches(it, tokens) }
            .map { FoodHit(it, if (tokens.isEmpty()) 0 else relevance(it, tokens), highlights(it.name, tokens)) }
            .toList()
        val alpha = Comparator<FoodHit> { a, b -> compareNames(a.entry.name, b.entry.name) }
        fun byValue(sel: (FoodEntry) -> Double?, desc: Boolean): Comparator<FoodHit> = Comparator { a, b ->
            val va = sel(a.entry); val vb = sel(b.entry)
            when {
                va == null && vb == null -> 0
                va == null -> 1
                vb == null -> -1
                else -> if (desc) vb.compareTo(va) else va.compareTo(vb)
            }
        }
        val usage = compareByDescending<FoodHit> { it.entry.favorite }
            .thenByDescending { it.entry.useCount }
            .thenByDescending { it.entry.inStock }
        val cmp: Comparator<FoodHit> = when (sort) {
            SortMode.FREQUENT ->
                if (tokens.isNotEmpty()) compareByDescending<FoodHit> { it.score }.then(usage).then(alpha)
                else compareByDescending<FoodHit> { it.entry.useCount }.then(alpha)
            SortMode.ALPHA_ASC -> alpha
            SortMode.ALPHA_DESC -> alpha.reversed()
            SortMode.KCAL_ASC -> byValue({ it.kcal100 }, false).then(alpha)
            SortMode.KCAL_DESC -> byValue({ it.kcal100 }, true).then(alpha)
            SortMode.PROTEIN_ASC -> byValue({ it.protein100 }, false).then(alpha)
            SortMode.PROTEIN_DESC -> byValue({ it.protein100 }, true).then(alpha)
            SortMode.PROTEIN_PER_KCAL -> byValue({ it.proteinPer100Kcal }, true).then(alpha)
        }
        return found.sortedWith(cmp)
    }
}
