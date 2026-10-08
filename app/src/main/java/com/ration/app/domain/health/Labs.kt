package com.ration.app.domain.health

import com.ration.app.data.db.entity.LabResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.abs

@Serializable
data class BpRules(
    val normalSys: Int = 130, val normalDia: Int = 85, val highSys: Int = 140, val highDia: Int = 90,
    val repeatHighCount: Int = 2, val urgentSys: Int = 180, val urgentDia: Int = 120,
)

@Serializable
data class TrendRules(
    val weightNoiseKg: Double = 0.3, val weightFastLossPctWeek: Double = 1.0, val bpSysNoise: Double = 3.0, val bpDiaNoise: Double = 2.0,
    val activityDropPct: Double = 20.0, val proteinDropPct: Double = 10.0, val intakeFloorKcal: Double = 1500.0, val labChangePct: Double = 5.0,
    val minMeasurements: Int = 3, val minFoodDays: Int = 3, val dismissDays: Int = 3, val notifyEveryDays: Int = 3,
)

@Serializable
data class NutritionRules(val processedSaltyYellowPct: Double = 20.0, val processedSaltyRedPct: Double = 35.0, val fishPerWeekMin: Int = 2)

@Serializable
data class ActivityRules(val greenMinWeek: Double = 150.0, val yellowMinWeek: Double = 60.0)

/** Показатель анализа: ориентировочные границы (бланк важнее), направление «хуже», критические границы. */
@Serializable
data class Indicator(
    val key: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val unit: String = "",
    val refLow: Double? = null,
    val refHigh: Double? = null,
    /** up — хуже выше, down — хуже ниже, range — хуже отклонение от нормы. */
    val worse: String = "range",
    val criticalLow: Double? = null,
    val criticalHigh: Double? = null,
)

/** Правила и пороги из assets/health_rules.json (20.4, 20.6). */
@Serializable
data class HealthRules(
    val version: Int = 1,
    val bp: BpRules = BpRules(),
    val trend: TrendRules = TrendRules(),
    val nutrition: NutritionRules = NutritionRules(),
    val activity: ActivityRules = ActivityRules(),
    val indicators: List<Indicator> = emptyList(),
) {
    private val byName by lazy {
        indicators.flatMap { i -> (listOf(i.name, i.key) + i.aliases).map { Labs.norm(it) to i } }.toMap()
    }
    fun indicator(name: String): Indicator? = byName[Labs.norm(name)]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): HealthRules = json.decodeFromString(serializer(), text)
    }
}

enum class LabStatus(val label: String) { NORMAL("в норме"), HIGH("выше нормы"), LOW("ниже нормы"), UNKNOWN("нет нормы") }

/** Оценка результата: границы с бланка, иначе ориентир из справочника. */
data class LabEval(val status: LabStatus, val low: Double?, val high: Double?, val fromBlank: Boolean, val critical: Boolean)

enum class Trend(val arrow: String) { UP("↑"), DOWN("↓"), SAME("→") }

/** Строка импорта показателей; [error] — почему не импортируется. */
data class LabRow(
    val lineNo: Int, val raw: String, val indicator: String, val value: Double?, val unit: String,
    val refLow: Double?, val refHigh: Double?, val day: Long?, val error: String?, val known: Boolean, val duplicate: Boolean = false,
)

object Labs {
    fun norm(s: String): String = s.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}%()]+"""), " ").trim()

    fun eval(r: LabResult, rules: HealthRules): LabEval {
        val ind = rules.indicator(r.indicator)
        val fromBlank = r.refLow != null || r.refHigh != null
        val low = if (fromBlank) r.refLow else ind?.refLow
        val high = if (fromBlank) r.refHigh else ind?.refHigh
        val status = when {
            low == null && high == null -> LabStatus.UNKNOWN
            high != null && r.value > high -> LabStatus.HIGH
            low != null && r.value < low -> LabStatus.LOW
            else -> LabStatus.NORMAL
        }
        val critical = ind != null && unitMatches(r.unit, ind.unit) &&
            ((ind.criticalLow != null && r.value <= ind.criticalLow) || (ind.criticalHigh != null && r.value >= ind.criticalHigh))
        return LabEval(status, low, high, fromBlank, critical)
    }

    /** Критические границы сравниваются только в единицах справочника (или если единица не указана). */
    fun unitMatches(unit: String, ref: String): Boolean = unit.isBlank() || norm(unit) == norm(ref)

    /** Стрелка динамики к предыдущему результату того же показателя. */
    fun trend(results: List<LabResult>, r: LabResult): Trend? {
        val prev = results.filter { norm(it.indicator) == norm(r.indicator) && it.day < r.day }.maxByOrNull { it.day } ?: return null
        return when {
            r.value > prev.value * 1.0001 -> Trend.UP
            r.value < prev.value * 0.9999 -> Trend.DOWN
            else -> Trend.SAME
        }
    }

    /** Ухудшение от a к b больше [pct]% в нежелательную сторону показателя. */
    fun worsened(a: Double, b: Double, ind: Indicator?, low: Double?, high: Double?, pct: Double): Boolean {
        if (a == 0.0) return false
        val change = (b - a) / abs(a) * 100
        if (abs(change) <= pct) return false
        return when (ind?.worse ?: "range") {
            "up" -> change > 0
            "down" -> change < 0
            else -> {
                val mid = if (low != null && high != null) (low + high) / 2 else return false
                abs(b - mid) > abs(a - mid)
            }
        }
    }

    private val dateRe = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})$""")

    fun parseDate(s: String): Long? {
        val m = dateRe.find(s.trim()) ?: return null
        return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()).toEpochDay() }.getOrNull()
    }

    private fun num(s: String): Double? = s.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    /**
     * Импорт (20.4): «показатель | значение | единица | норма от | норма до | дата». Строки с «?», без числа или с неверной
     * датой помечаются и не импортируются; повтор (показатель, дата, значение) — дубликат, тоже не импортируется.
     */
    fun parse(text: String, rules: HealthRules, existing: List<LabResult>): List<LabRow> {
        val seen = existing.map { Triple(norm(it.indicator), it.day, it.value) }.toMutableSet()
        return text.lineSequence().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val p = line.split('|', ';', '\t').map { it.trim() }
            val name = p.getOrElse(0) { "" }
            val valueStr = p.getOrElse(1) { "" }
            val value = num(valueStr)
            val unit = p.getOrElse(2) { "" }
            val lowStr = p.getOrElse(3) { "" }; val highStr = p.getOrElse(4) { "" }
            val low = num(lowStr); val high = num(highStr)
            val day = parseDate(p.getOrElse(5) { "" })
            val ind = rules.indicator(name)
            val canonical = ind?.name ?: name
            val error = when {
                name.isBlank() -> "нет названия показателя"
                valueStr.contains('?') -> "значение под вопросом (?)"
                value == null -> "нет числа"
                (lowStr.isNotEmpty() && low == null) || (highStr.isNotEmpty() && high == null) -> "норма не число"
                day == null -> "дата не в формате ДД.ММ.ГГГГ"
                else -> null
            }
            val dup = error == null && !seen.add(Triple(norm(canonical), day!!, value!!))
            LabRow(i + 1, line, canonical, value, unit, low, high, day, error, ind != null, dup)
        }.toList()
    }

    fun toResults(rows: List<LabRow>): List<LabResult> = rows.filter { it.error == null && !it.duplicate }.map {
        LabResult(day = it.day!!, indicator = it.indicator, value = it.value!!, unit = it.unit, refLow = it.refLow, refHigh = it.refHigh, source = "import")
    }

    /** Запрос для Claude: прочитать бланк и выдать только строки формата, без интерпретаций, «?» при сомнении. */
    const val PROMPT = """Прочитай приложенный бланк анализов и выведи только строки формата:
показатель | значение | единица | норма от | норма до | дата

Правила:
1. Одна строка — один показатель. Без пояснений, таблиц и интерпретаций.
2. Числа с точкой. Если границы нормы на бланке нет, оставь поле пустым.
3. Дата анализа в формате ДД.ММ.ГГГГ.
4. Если сомневаешься в значении, напиши ? вместо числа.
5. Названия показателей пиши по-русски, как принято: АЛТ, АСТ, ГГТ, общий холестерин, ЛПНП, ЛПВП, триглицериды, глюкоза натощак, HbA1c, креатинин, калий, натрий, мочевая кислота, ТТГ, витамин D, B12, магний, гемоглобин; остальные — как на бланке."""
}
