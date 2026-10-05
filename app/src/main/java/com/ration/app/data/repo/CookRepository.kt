package com.ration.app.data.repo

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.MealItem
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.cook.CookTargets
import com.ration.app.domain.cook.FoodRules
import com.ration.app.domain.cook.SuggestRequest
import com.ration.app.domain.cook.SuggestResult
import com.ration.app.domain.cook.Suggester
import com.ration.app.domain.cook.Suggestion
import com.ration.app.domain.meal.CustomBlocks
import com.ration.app.domain.model.CookSlot
import com.ration.app.domain.model.FoodRole
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.day.SlotStates
import com.ration.app.domain.model.SlotType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Промежуточные данные между экранами (вариант → конструктор). */
@Singleton
class Drafts @Inject constructor() {
    @Volatile var builderItems: List<MealItem>? = null
    @Volatile var builderTitle: String? = null
    @Volatile var inventoryText: String? = null
}

@Singleton
class CookRepository @Inject constructor(
    private val catalog: CatalogRepository,
    private val inventory: InventoryRepository,
    private val plans: PlanRepository,
    private val meals: MealRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private var cacheKey: Any? = null
    private var cache: SuggestResult? = null
    private var suggesterCache: Pair<Any, Suggester>? = null

    private suspend fun signature(): Any {
        val stock = inventory.stockList().filter { it.qty > 1e-6 }.map { Triple(it.productId, it.qty, it.expiresDay) }
        val preps = inventory.activePrepsList().map { it.id to it.portionsLeft }
        return listOf(stock, preps, catalog.allProducts().size, catalog.recipesList().size, catalog.allBlocks().size)
    }

    private suspend fun suggester(): Suggester {
        val sig = signature()
        suggesterCache?.let { if (it.first == sig) return it.second }
        val products = catalog.allProducts().associateBy { it.id }
        val cb = catalog.cookbook()
        val s = Suggester(
            products, inventory.stockList(), inventory.prepsList(),
            catalog.templates().flatMap { it.outputs }.associateBy { it.key },
            catalog.allBlocks(), catalog.allIngredients().groupBy { it.blockId },
            catalog.recipesList(), cb.defaults, LocalDate.now(clock).toEpochDay(),
        )
        suggesterCache = sig to s
        return s
    }

    private fun nowMinute() = LocalTime.now(clock).let { it.hour * 60 + it.minute }

    /** Ближайший неотмеченный слот по времени (18.1, 19.8). */
    suspend fun nearestOpenSlot(): SlotType {
        val today = plans.today()
        plans.ensurePlan(today)
        return plans.nearestEmpty(today, nowMinute()) ?: SlotType.DINNER
    }

    /** Цель по умолчанию: остаток дневной цели / число слотов «не отмечен» впереди по времени (19.8). */
    suspend fun defaultTargets(): Pair<Double, Double> {
        val today = plans.today()
        val s = plans.settingsWithSchedule()
        val logs = meals.logsRange(today, today)
        val open = SlotStates.emptyAhead(s.times(), plans.states(today), nowMinute()).size
        return CookTargets.default(s, logs.sumOf { it.kcal }, logs.sumOf { it.protein }, open)
    }

    suspend fun baseRequest(slot: CookSlot, kcal: Double, protein: Double): SuggestRequest {
        val today = plans.today()
        val s = settings.current()
        val date = LocalDate.ofEpochDay(today)
        // будни вечером: вс–чт (израильская неделя), ужин и вечер
        val weekdayEvening = date.dayOfWeek !in setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY) && slot in setOf(CookSlot.DINNER, CookSlot.EVENING)
        val products = catalog.allProducts().associateBy { it.id }
        val lastProtein = meals.logsRange(today - 1, today).sortedByDescending { it.atMillis }.flatMap { it.items }
            .mapNotNull { it.productId?.let(products::get) }.firstOrNull { FoodRules.role(it) == FoodRole.PROTEIN }?.id
        return SuggestRequest(
            slot = slot, targetKcal = kcal, targetProtein = protein, weekdayEvening = weekdayEvening,
            week = plans.weekCounters(today, includeDay = true), settings = s, lastMainProteinId = lastProtein,
            hiddenKeys = s.hiddenSuggestions.toSet(),
        )
    }

    /** Подбор в фоне (Dispatchers.Default), кэш до изменения остатков. */
    suspend fun suggest(req: SuggestRequest): SuggestResult = withContext(Dispatchers.Default) {
        val key = signature() to req
        if (key == cacheKey) cache?.let { return@withContext it }
        val r = suggester().suggest(req)
        cacheKey = key; cache = r
        r
    }

    suspend fun leftovers(base: SuggestRequest): List<Pair<String, Suggestion>> = withContext(Dispatchers.Default) {
        val (k, p) = defaultTargets()
        suggester().useLeftovers(base, 2, mapOf(CookSlot.LUNCH to (maxOf(k, 450.0) to maxOf(p, 30.0)), CookSlot.DINNER to (maxOf(k, 450.0) to maxOf(p, 30.0))))
    }

    /** Запись варианта (18.5): тем же путём, что и конструктор — FIFO, счётчики, отмена. */
    suspend fun record(s: Suggestion, slotType: SlotType): LogOutcome {
        val day = plans.today()
        val b = s.block
        return if (b != null) meals.logBlock(day, slotType, b, s.multiplier, false, null, MealSource.BLOCK)
        else meals.logItems(day, slotType, s.title, s.items.map { it.toMealItem() }, source = MealSource.CUSTOM)
    }

    suspend fun saveAsBlock(name: String, slot: CookSlot, items: List<MealItem>): Block {
        val code = CustomBlocks.nextCode(catalog.allBlocks().map { it.code })
        val kind = slot.mealKinds.first()
        val (block, ings) = CustomBlocks.build(code, name, kind, items)
        val id = catalog.saveCustomBlock(block, ings)
        return block.copy(id = id)
    }

    suspend fun hide(key: String) = settings.update { it.copy(hiddenSuggestions = (it.hiddenSuggestions + key).takeLast(300)) }

    fun kindFor(slot: CookSlot): MealKind = slot.mealKinds.first()
}
