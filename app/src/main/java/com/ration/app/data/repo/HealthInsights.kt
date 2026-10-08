package com.ration.app.data.repo

import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.health.ConditionReport
import com.ration.app.domain.health.Condition
import com.ration.app.domain.health.FormAnalysis
import com.ration.app.domain.health.FormReport
import com.ration.app.domain.health.FormTarget
import com.ration.app.domain.health.FormTargets
import com.ration.app.domain.health.HealthWarning
import com.ration.app.domain.health.ProfileCheck
import com.ration.app.domain.health.TrendCheck
import com.ration.app.domain.health.Trends
import com.ration.app.notifications.AppNotifier
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** «По форме» для «Сегодня»: рекомендация или список недостающих данных профиля. */
data class FormHint(val target: FormTarget?, val missing: List<String>)

/** Расчёты раздела 20 поверх журналов (только на телефоне, без сети). */
@Singleton
class HealthInsights @Inject constructor(
    private val health: HealthRepository,
    private val meals: MealRepository,
    private val settings: SettingsRepository,
    private val notifier: AppNotifier,
    private val clock: Clock,
) {
    private fun today() = LocalDate.now(clock).toEpochDay()

    suspend fun formHint(): FormHint {
        val s = settings.current()
        val d = today()
        val (p, missing) = ProfileCheck.profile(s, health.weightsList(), d)
        p ?: return FormHint(null, missing)
        return FormHint(FormTargets.today(p, s, health.workoutsRange(d, d).sumOf { it.kcalNet }), emptyList())
    }

    suspend fun form(days: Int): FormReport {
        val s = settings.current()
        val to = today(); val from = to - days + 1
        val weights = health.weightsList()
        val r = FormAnalysis.report(from, to, s, weights, meals.logsRange(from, to), health.workoutsRange(from, to), meals.quickRange(from, to))
        health.cacheForm(r.days)
        return r
    }

    suspend fun condition(): ConditionReport {
        val s = settings.current()
        val to = today()
        return Condition.report(to, clock.zone, s, health.rules, health.weightsList(), health.bpList(), meals.logsRange(to - 6, to),
            meals.quickRange(to - 6, to), health.workoutsRange(to - 6, to), health.labsList())
    }

    suspend fun trends(): TrendCheck {
        val s = settings.current()
        val to = today()
        return Trends.check(to, clock.zone, health.rules, goalLoss = s.targetWeight < (health.weightsList().lastOrNull()?.kg ?: s.startWeightKg),
            health.weightsList(), health.bpList(), meals.logsRange(to - 21, to - 1), health.workoutsRange(to - 21, to - 1), health.labsList())
    }

    /** Карточки для «Сегодня»: срочные всегда, остальные — если не скрыты на 3 дня. */
    suspend fun visibleWarnings(): List<HealthWarning> {
        val s = settings.current()
        val d = today()
        val urgent = Trends.urgent(health.rules, health.bpList(), health.labsList(), clock.millis())
        val trend = trends().warnings.filter { (s.healthWarningsDismissed[it.key] ?: Long.MIN_VALUE) < d }
        return urgent + trend
    }

    suspend fun dismiss(key: String) {
        val until = today() + health.rules.trend.dismissDays
        settings.update { st -> st.copy(healthWarningsDismissed = (st.healthWarningsDismissed + (key to until)).filterValues { it >= today() }) }
    }

    /** Фоновая проверка: одно уведомление не чаще раза в 3 дня, текст без значений (20.6). */
    suspend fun notifyIfNeeded() {
        val w = visibleWarnings()
        if (w.isEmpty()) return
        val s = settings.current()
        val d = today()
        val last = s.healthWarnNotifiedDay
        if (last != null && d - last < health.rules.trend.notifyEveryDays && w.none { it.urgent }) return
        if (last == d) return
        notifier.healthWarning()
        settings.update { it.copy(healthWarnNotifiedDay = d) }
    }
}
