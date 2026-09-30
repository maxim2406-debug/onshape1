package com.ration.app.data.repo

import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.Deduction
import com.ration.app.data.db.entity.MealItem
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.Substitution
import com.ration.app.data.seed.PrepKeys
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.inventory.Consumption
import com.ration.app.domain.inventory.Shortage
import com.ration.app.domain.inventory.UnitConv
import com.ration.app.domain.meal.FoodCatalog
import com.ration.app.domain.meal.ItemsConsumption
import com.ration.app.domain.meal.MealItems
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.MeatChoice
import com.ration.app.domain.model.QuickType
import com.ration.app.domain.model.SlotStatus
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Tags
import com.ration.app.domain.rules.DayRules
import com.ration.app.domain.rules.DayWarning
import com.ration.app.domain.substitution.NutrientTarget
import com.ration.app.domain.substitution.SubstitutionCalculator
import com.ration.app.domain.substitution.SubstitutionProposal
import com.ration.app.notifications.AppNotifier
import com.ration.app.notifications.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class LogOutcome(
    val logId: Long,
    val shortages: List<Shortage> = emptyList(),
    val notes: List<String> = emptyList(),
    val limitWarnings: List<String> = emptyList(),
    val advice: List<String> = emptyList(),
)

@Singleton
class MealRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val catalog: CatalogRepository,
    private val inventory: InventoryRepository,
    private val plans: PlanRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: AppNotifier,
    private val clock: Clock,
) {
    fun observeLogs(day: Long): Flow<List<MealLog>> = db.meals().observeDay(day)
    fun observeQuick(day: Long): Flow<List<QuickLog>> = db.quick().observeDay(day)
    fun observeRange(from: Long, to: Long): Flow<List<MealLog>> = db.meals().observeRange(from, to)
    suspend fun quickFor(day: Long): List<QuickLog> = db.quick().forDay(day)
    suspend fun logsRange(from: Long, to: Long): List<MealLog> = db.meals().range(from, to)
    suspend fun quickRange(from: Long, to: Long): List<QuickLog> = db.quick().range(from, to)

    private fun nowMillis() = clock.millis()

    /** «Съел по плану» — из уведомления или экрана. */
    suspend fun eatPlanned(slotId: Long, multiplier: Double = 1.0, withFruit: Boolean? = null, meat: MeatChoice? = null): LogOutcome? {
        val slot = plans.slot(slotId) ?: return null
        val blockId = slot.blockId ?: return null
        val block = catalog.block(blockId) ?: return null
        val fruit = withFruit ?: (slot.slot == SlotType.LUNCH)
        return logBlock(slot.day, slot.slot, block, multiplier * slot.multiplier, fruit, meat, MealSource.PLAN)
    }

    /** Ингредиенты блока с учётом замен на день: заменённые не списываются. */
    private suspend fun effectiveIngredients(block: Block, day: Long): Pair<List<BlockIngredient>, List<Substitution>> {
        val subs = db.meals().substitutionsFor(block.id, day)
        val replaced = subs.map { it.ingredientId }.toSet()
        return catalog.ingredientsOf(block.id).filter { it.id !in replaced } to subs
    }

    suspend fun logBlock(
        day: Long, slot: SlotType?, block: Block, multiplier: Double, withFruit: Boolean,
        meat: MeatChoice?, source: MealSource,
    ): LogOutcome {
        val s = settings.current()
        val (ings, subs) = effectiveIngredients(block, day)
        val products = inventory.productsMap()
        val snapshot = inventory.snapshot()
        val deductions = mutableListOf<Deduction>()
        val shortages = mutableListOf<Shortage>()
        val notes = mutableListOf<String>()
        if (block.deductStock) {
            val plan = Consumption.plan(ings, multiplier, products, snapshot)
            deductions += plan.deductions; shortages += plan.shortages; notes += plan.notes
        }
        var kcal = (block.kcal + subs.sumOf { it.kcalDelta }) * multiplier
        var protein = (block.protein + subs.sumOf { it.proteinDelta }) * multiplier
        if (withFruit) {
            val apple = products.values.firstOrNull { it.key == "apple" }
            val pear = products.values.firstOrNull { it.key == "pear" }
            val fp = Consumption.fruit(apple, pear, snapshot)
            deductions += fp.deductions; shortages += fp.shortages
            kcal += s.fruitKcal
        }
        val eggId = products.values.firstOrNull { it.key == "egg" }?.id
        val eggs = Consumption.eggs(ings, eggId, PrepKeys.EGGS) * multiplier
        // Фактический состав и теги по ингредиентам (12.3.3); без изменений состава учитываются и теги блока.
        val items = if (block.deductStock) MealItems.ofBlock(ings, foodCatalog(), multiplier).filter { !it.untracked } else emptyList()
        val tags = MealItems.logTags(items, block, compositionEdited = false) +
            if (Tags.ASK_MEAT in block.tags && meat == MeatChoice.RED) listOf(Tags.RED_MEAT) else emptyList()
        items.mapNotNull { it.productId }.takeIf { it.isNotEmpty() }?.let { db.products().markUsed(it, nowMillis()) }
        return insertLog(
            MealLog(
                day = day, atMillis = logTime(day), slot = slot, blockId = block.id, blockCode = block.code,
                name = "${block.code} ${block.name}", multiplier = multiplier, kcal = kcal, protein = protein,
                source = source, withFruit = withFruit, meatChoice = meat, tags = tags.distinct(), eggs = eggs, deductions = deductions,
                items = items,
            ),
            shortages, notes,
        )
    }

    suspend fun foodCatalog(): FoodCatalog = FoodCatalog(
        inventory.productsMap(),
        catalog.customFoods().associateBy { it.id },
        catalog.templates().flatMap { it.outputs }.associateBy { it.key },
    )

    /**
     * Запись по фактическому составу (12.1–12.3, 18.5): конструктор, изменённый блок, вариант «Что приготовить».
     * Списание по FIFO; нехватка не блокирует запись, строка помечается «без списания».
     * [basedOn] и [originalItems] — исходный блок: итог = таблица блока + разница составов.
     */
    suspend fun logItems(
        day: Long, slot: SlotType?, name: String, items: List<MealItem>,
        basedOn: Block? = null, originalItems: List<MealItem>? = null, multiplier: Double = 1.0,
        withFruit: Boolean = false, source: MealSource = MealSource.CUSTOM,
    ): LogOutcome {
        val s = settings.current()
        val cat = foodCatalog()
        val snapshot = inventory.snapshot()
        val planned = ItemsConsumption.plan(items, cat, snapshot)
        val deductions = planned.deductions.toMutableList()
        val shortages = planned.items.mapNotNull { i ->
            val miss = ItemsConsumption.shortfall(i, cat)
            if (miss > 1e-6) Shortage(i.name + if (i.deducted <= 1e-6) " (без списания)" else " (частично)", miss,
                i.productId?.let(cat.products::get)?.unit ?: i.unit) else null
        }.toMutableList()
        var (kcal, protein) = if (basedOn != null && originalItems != null) MealItems.editedBlockTotals(basedOn, originalItems, items, multiplier)
            else MealItems.sumKcal(items) to MealItems.sumProtein(items)
        if (withFruit) {
            val apple = cat.products.values.firstOrNull { it.key == "apple" }
            val pear = cat.products.values.firstOrNull { it.key == "pear" }
            val fp = Consumption.fruit(apple, pear, snapshot)
            deductions += fp.deductions; shortages += fp.shortages
            kcal += s.fruitKcal
        }
        val tags = MealItems.logTags(planned.items, basedOn, compositionEdited = true)
        items.mapNotNull { it.productId }.takeIf { it.isNotEmpty() }?.let { db.products().markUsed(it, nowMillis()) }
        return insertLog(
            MealLog(
                day = day, atMillis = logTime(day), slot = slot, blockId = basedOn?.id, blockCode = basedOn?.code,
                name = basedOn?.let { "${it.code} ${it.name} (изм.)" } ?: name, multiplier = multiplier, kcal = kcal, protein = protein,
                source = source, withFruit = withFruit, tags = tags, eggs = MealItems.eggs(planned.items, cat.products),
                deductions = deductions, basedOnBlockCode = basedOn?.code, items = planned.items,
            ),
            shortages, emptyList(),
        )
    }

    /** «Свой продукт»: граммы или порции. */
    suspend fun logCustom(day: Long, slot: SlotType?, food: CustomFood, grams: Double?, portions: Double?): LogOutcome {
        val (k100, p100) = SubstitutionCalculator.per100(food)
        val (kcal, protein) = when {
            portions != null && food.kcalPerPortion != null -> food.kcalPerPortion * portions to (food.proteinPerPortion ?: (p100 * (food.portionGrams ?: 0.0) / 100)) * portions
            grams != null -> k100 * grams / 100 to p100 * grams / 100
            else -> (food.kcalPerPortion ?: k100 * (food.portionGrams ?: 100.0) / 100) to (food.proteinPerPortion ?: p100 * (food.portionGrams ?: 100.0) / 100)
        }
        val tags = when {
            food.isProteinBar -> listOf(Tags.BAR, Tags.PROTEIN_BAR)
            food.isBar -> listOf(Tags.BAR, Tags.CEREAL_BAR, Tags.LIGHT)
            else -> emptyList()
        }
        return insertLog(
            MealLog(
                day = day, atMillis = logTime(day), slot = slot, customFoodId = food.id, name = food.name,
                multiplier = portions ?: 1.0, kcal = kcal, protein = protein,
                source = if (food.isBar) MealSource.BAR else MealSource.CUSTOM, tags = tags,
            ),
            emptyList(), emptyList(),
        )
    }

    /** Быстрые кнопки батончиков: блоки П6/П7 или запомненный батончик из этикетки. */
    suspend fun logBar(day: Long, protein: Boolean, slot: SlotType? = null): LogOutcome {
        val code = if (protein) "П6" else "П7"
        val block = catalog.blockByCode(code) ?: error("нет блока $code")
        val remembered = catalog.customFoods().lastOrNull { it.isBar && it.isProteinBar == protein }
        return if (remembered != null) logCustom(day, slot, remembered, null, 1.0)
        else logBlock(day, slot, block, 1.0, false, null, MealSource.BAR)
    }

    private fun logTime(day: Long): Long {
        val today = LocalDate.now(clock).toEpochDay()
        return if (day == today) nowMillis()
        else TimeUtil.toMillis(LocalDate.ofEpochDay(day).atTime(12, 0), clock.zone)
    }

    private suspend fun insertLog(log: MealLog, shortages: List<Shortage>, notes: List<String>): LogOutcome {
        val s = settings.current()
        val before = plans.weekCounters(log.day, includeDay = true)
        val id = db.meals().insert(log)
        inventory.applyDeductions(log.deductions, -1)
        // Закрыть слот: «съеден» — тот же блок, иначе «заменён».
        log.slot?.let { slotType ->
            plans.slots(log.day).firstOrNull { it.slot == slotType }?.let { sl ->
                val status = if (sl.blockId != null && sl.blockId == log.blockId) SlotStatus.EATEN else SlotStatus.REPLACED
                plans.updateSlot(sl.copy(status = status))
            }
        }
        val after = plans.weekCounters(log.day, includeDay = true)
        val limitWarnings = DayRules.onLogWarnings(before, after, s)
        val advice = plans.replan(log.day)
        inventory.checkThresholds()
        scheduler.rescheduleAll()
        notifyNewWarnings(log.day)
        return LogOutcome(id, shortages, notes, limitWarnings, advice)
    }

    suspend fun skip(slotId: Long): List<String> {
        val slot = plans.slot(slotId) ?: return emptyList()
        plans.updateSlot(slot.copy(status = SlotStatus.SKIPPED))
        val advice = plans.replan(slot.day)
        scheduler.rescheduleAll()
        return advice
    }

    /** Отмена записи: запасы возвращаются, слот снова «запланирован». */
    suspend fun undo(logId: Long) {
        val log = db.meals().get(logId) ?: return
        inventory.applyDeductions(log.deductions, +1)
        db.meals().delete(log)
        log.slot?.let { slotType ->
            val others = db.meals().forDay(log.day).any { it.slot == slotType }
            if (!others) plans.slots(log.day).firstOrNull { it.slot == slotType }?.let {
                if (it.status == SlotStatus.EATEN || it.status == SlotStatus.REPLACED) plans.updateSlot(it.copy(status = SlotStatus.PLANNED))
            }
        }
        plans.replan(log.day)
        inventory.checkThresholds()
        scheduler.rescheduleAll()
    }

    suspend fun quick(type: QuickType, amount: Double = 1.0): List<DayWarning> {
        val day = LocalDate.now(clock).toEpochDay()
        db.quick().insert(QuickLog(day = day, atMillis = nowMillis(), type = type, amount = amount))
        return notifyNewWarnings(day)
    }

    suspend fun undoQuick(q: QuickLog) = db.quick().delete(q)

    suspend fun warnings(day: Long): List<DayWarning> {
        val s = settings.current()
        val now = LocalDateTime.now(clock)
        val dayNow = if (day == now.toLocalDate().toEpochDay()) now else LocalDate.ofEpochDay(day).atTime(23, 59)
        return DayRules.warnings(dayNow, clock.zone, db.meals().forDay(day), db.quick().forDay(day), s,
            plans.weekCounters(day, includeDay = true))
    }

    /** Отправить уведомлением только новые предупреждения дня. */
    suspend fun notifyNewWarnings(day: Long): List<DayWarning> {
        val w = warnings(day)
        val sent = settings.warnedToday()
        val fresh = w.filter { "$day:${it.id}" !in sent }
        fresh.forEach { notifier.dayWarning(it) }
        if (fresh.isNotEmpty()) settings.setWarned(sent + fresh.map { "$day:${it.id}" })
        return w
    }

    // ---- Замены ----

    /** Ккал и белок заменяемого ингредиента в порции блока. */
    suspend fun ingredientTarget(ing: BlockIngredient): NutrientTarget? {
        val products = inventory.productsMap()
        val product = ing.productId?.let(products::get)
            ?: ing.prepKey?.let { key -> catalog.templates().flatMap { it.outputs }.firstOrNull { it.key == key }?.productId?.let(products::get) }
            ?: return null
        val qty = ing.qty ?: return null
        val unit = ing.unit ?: return null
        val grams = UnitConv.grams(qty, unit, product) ?: return null
        return NutrientTarget(product.kcalPer100 * grams / 100, product.proteinPer100 * grams / 100)
    }

    suspend fun proposeSubstitution(ing: BlockIngredient, food: CustomFood): SubstitutionProposal? {
        val target = ingredientTarget(ing) ?: return null
        val (k, p) = SubstitutionCalculator.per100(food)
        return SubstitutionCalculator.propose(target, k, p)
    }

    suspend fun saveSubstitution(block: Block, ing: BlockIngredient, food: CustomFood, proposal: SubstitutionProposal, permanent: Boolean, day: Long) {
        // Старые замены того же ингредиента того же типа снимаются.
        db.meals().substitutions().filter { it.blockId == block.id && it.ingredientId == ing.id && (it.permanent == permanent) && (permanent || it.day == day) }
            .forEach { db.meals().deleteSubstitution(it) }
        db.meals().insertSubstitution(
            Substitution(blockId = block.id, ingredientId = ing.id, customFoodId = food.id, qtyGrams = proposal.grams,
                permanent = permanent, day = if (permanent) null else day, kcalDelta = proposal.kcalDelta, proteinDelta = proposal.proteinDelta),
        )
    }

    fun observeSubstitutions() = db.meals().observeSubstitutions()
    suspend fun deleteSubstitution(s: Substitution) = db.meals().deleteSubstitution(s)
}
