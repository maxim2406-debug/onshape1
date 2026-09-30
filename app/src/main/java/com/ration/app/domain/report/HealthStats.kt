package com.ration.app.domain.report

import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.WeightLog

object HealthStats {
    /**
     * Темп изменения веса, кг/неделю, за последние [days] суток:
     * наклон линейной регрессии по записям окна. null — меньше двух записей.
     */
    fun weightRatePerWeek(weights: List<WeightLog>, todayDay: Long, days: Int): Double? {
        val w = weights.filter { it.day > todayDay - days && it.day <= todayDay }.sortedBy { it.day }
        if (w.size < 2 || w.first().day == w.last().day) return null
        val xs = w.map { it.day.toDouble() }
        val ys = w.map { it.kg }
        val mx = xs.average()
        val my = ys.average()
        val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val den = xs.sumOf { (it - mx) * (it - mx) }
        return if (den == 0.0) null else num / den * 7.0
    }

    data class BpAverage(val systolic: Double, val diastolic: Double, val pulse: Double?, val count: Int)

    fun bpAverage(bp: List<BpLog>, fromMillis: Long, toMillis: Long): BpAverage? {
        val l = bp.filter { it.atMillis in fromMillis..toMillis }
        if (l.isEmpty()) return null
        return BpAverage(l.map { it.systolic }.average(), l.map { it.diastolic }.average(),
            l.mapNotNull { it.pulse }.takeIf { it.isNotEmpty() }?.average(), l.size)
    }
}
