package com.ration.app.domain.inventory

import com.ration.app.data.db.entity.Product
import com.ration.app.domain.importing.ImportParser
import com.ration.app.domain.importing.MatchKind
import com.ration.app.domain.importing.ParseError
import com.ration.app.domain.importing.ProductMatcher
import com.ration.app.domain.model.MeasureUnit
import java.time.LocalDate

/** Строка инвентаризации (17.1): название | количество | единица | приблизительно | до ДД.ММ. */
data class InventoryLine(
    val lineNo: Int,
    val name: String,
    val qty: Double,
    val unit: MeasureUnit,
    val approx: Boolean,
    val expiresDay: Long?,
)

data class InventoryParse(val lines: List<InventoryLine>, val errors: List<ParseError>, val comments: List<String>)

enum class InventoryMode(val label: String) {
    ADD("Добавить к остатку"), SET("Инвентаризация"), SET_ZERO("Инвентаризация с обнулением")
}

data class InventoryRow(
    val product: Product,
    val before: Double,
    val after: Double,
    val approx: Boolean,
    val expiresDay: Long? = null,
    /** Неперечисленный продукт, который обнулится (режим с обнулением). */
    val zeroed: Boolean = false,
)

object InventoryParser {
    private val sep = Regex("""\s*[|;\t]\s*""")
    private val approxWords = setOf("≈", "~", "примерно", "приблизительно", "прибл", "прибл.", "на глаз")
    private val dateRe = Regex("""^(?:до\s*)?(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))?$""")

    fun parseExpiry(s: String, today: LocalDate): Long? {
        val m = dateRe.find(s.trim().lowercase()) ?: return null
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
        return runCatching {
            var date = LocalDate.of(y ?: today.year, mo, d)
            if (y == null && date.isBefore(today.minusMonths(6))) date = date.plusYears(1)
            date.toEpochDay()
        }.getOrNull()
    }

    fun parse(text: String, today: LocalDate): InventoryParse {
        val cleaned = mutableListOf<String>()
        val meta = mutableMapOf<Int, Pair<Boolean, Long?>>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) { cleaned += line; return@forEachIndexed }
            var parts = line.split(sep).map { it.trim() }.toMutableList()
            var approx = false
            var exp: Long? = null
            // хвостовые поля: признак «≈» и срок годности
            while (parts.size > 1) {
                val last = parts.last().lowercase()
                when {
                    last.isEmpty() -> parts.removeAt(parts.size - 1)
                    last in approxWords -> { approx = true; parts.removeAt(parts.size - 1) }
                    parseExpiry(last, today) != null && parts.size > 2 -> { exp = parseExpiry(last, today); parts.removeAt(parts.size - 1) }
                    else -> break
                }
            }
            // «≈» внутри количества: «≈ 300 г» или «300≈»
            if (parts.size >= 2 && (parts[1].contains('≈') || parts[1].contains('~'))) {
                approx = true; parts[1] = parts[1].replace("≈", "").replace("~", "").trim()
            }
            if (parts.size == 1 && (line.contains('≈'))) { approx = true; parts = mutableListOf(line.replace("≈", "").trim()) }
            meta[i + 1] = approx to exp
            cleaned += parts.joinToString(" | ")
        }
        val r = ImportParser.parsePurchases(cleaned.joinToString("\n"))
        return InventoryParse(
            r.lines.map { l -> val (a, e) = meta[l.lineNo] ?: (false to null); InventoryLine(l.lineNo, l.name, l.qty, l.unit, a, e) },
            r.errors, r.comments,
        )
    }

    fun match(line: InventoryLine, catalog: List<Product>): Pair<Product?, MatchKind> {
        val m = ProductMatcher.match(line.name, catalog.filter { !it.hidden })
        return m.product to m.kind
    }
}

object InventoryPlanner {
    /**
     * Предпросмотр (17.2): было → станет. В режиме SET_ZERO неперечисленные отслеживаемые продукты с остатком обнуляются.
     * lines: продукт → (количество в единицах строки, признак ≈, срок).
     */
    fun plan(
        mode: InventoryMode,
        lines: List<Triple<Product, InventoryLine, Double>>,
        totals: Map<Long, Double>,
        allProducts: List<Product>,
    ): List<InventoryRow> {
        val byProduct = linkedMapOf<Long, InventoryRow>()
        for ((p, line, qtyInProductUnit) in lines) {
            val before = totals[p.id] ?: 0.0
            val prev = byProduct[p.id]
            val sum = (prev?.let { if (mode == InventoryMode.ADD) it.after - before else it.after } ?: 0.0) + qtyInProductUnit
            val after = if (mode == InventoryMode.ADD) before + sum else sum
            byProduct[p.id] = InventoryRow(p, before, after, line.approx || prev?.approx == true, line.expiresDay ?: prev?.expiresDay)
        }
        val rows = byProduct.values.toMutableList()
        if (mode == InventoryMode.SET_ZERO) {
            allProducts.filter { !it.untracked && it.id !in byProduct && (totals[it.id] ?: 0.0) > 1e-6 }
                .forEach { rows += InventoryRow(it, totals[it.id] ?: 0.0, 0.0, approx = false, zeroed = true) }
        }
        return rows
    }
}
