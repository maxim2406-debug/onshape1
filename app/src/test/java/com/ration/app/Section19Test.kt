package com.ration.app

import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.db.entity.SlotState
import com.ration.app.domain.backup.BackupCodec
import com.ration.app.domain.backup.BackupData
import com.ration.app.domain.day.SlotStates
import com.ration.app.domain.meal.DishSplitter
import com.ration.app.domain.meal.FoodCatalog
import com.ration.app.domain.meal.MealItems
import com.ration.app.domain.meal.ofDish
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.DayType
import com.ration.app.domain.model.MealSlotStatus
import com.ration.app.domain.model.MealSlotStatus.EMPTY
import com.ration.app.domain.model.MealSlotStatus.LOGGED
import com.ration.app.domain.model.MealSlotStatus.SKIPPED
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.SlotSchedule
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.SlotType.BREAKFAST
import com.ration.app.domain.model.SlotType.DINNER
import com.ration.app.domain.model.SlotType.EVENING
import com.ration.app.domain.model.SlotType.LUNCH
import com.ration.app.domain.model.SlotType.SNACK_AM
import com.ration.app.domain.model.SlotType.SNACK_PM
import com.ration.app.domain.model.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Раздел 19: шесть приёмов, автопропуск, откат, расписание без типов дня, блюда, копии прежней схемы. */
class Section19Test {
    private val day = 20_400L
    private fun st(states: Map<SlotType, SlotState>, s: SlotType) = SlotStates.status(states, s)

    @Test fun sixSlotsInFixedOrder() {
        assertEquals(listOf("З", "С", "О", "П", "У", "Е"), SlotType.entries.map { it.short })
        assertEquals(listOf("Завтрак", "Перекус", "Обед", "Перекус", "Ужин", "Перекус"), SlotType.entries.map { it.label })
    }

    /** 19.7: запись в О при пустых З и С → З и С пропущены автоматически; П пуст; LOGGED не меняется. */
    @Test fun autoSkipEarlierEmptySlots() {
        val ch = SlotStates.onLog(day, LUNCH, emptyMap(), 1)
        val after = ch.apply(emptyMap())
        assertEquals(LOGGED, st(after, LUNCH))
        assertEquals(SKIPPED, st(after, BREAKFAST)); assertTrue(after.getValue(BREAKFAST).autoSkipped)
        assertEquals(SKIPPED, st(after, SNACK_AM)); assertTrue(after.getValue(SNACK_AM).autoSkipped)
        assertEquals(EMPTY, st(after, SNACK_PM))
        assertEquals(EMPTY, st(after, DINNER))
        assertEquals(listOf(BREAKFAST, SNACK_AM), ch.autoSkipped)

        val withBreakfast = mapOf(BREAKFAST to SlotState(day, BREAKFAST, LOGGED, false, 1))
        val ch2 = SlotStates.onLog(day, LUNCH, withBreakfast, 2).apply(withBreakfast)
        assertEquals(LOGGED, st(ch2, BREAKFAST))
        assertFalse(ch2.getValue(BREAKFAST).autoSkipped)
        assertEquals(SKIPPED, st(ch2, SNACK_AM))
    }

    /** Ручной пропуск раньше не перезаписывается автопропуском и не помечается как автоматический. */
    @Test fun manualSkipIsKept() {
        val s0 = SlotStates.skip(day, BREAKFAST, emptyMap(), 1).apply(emptyMap())
        val ch = SlotStates.onLog(day, DINNER, s0, 2)
        assertEquals(listOf(SNACK_AM, LUNCH, SNACK_PM), ch.autoSkipped)
        val after = ch.apply(s0)
        assertFalse(after.getValue(BREAKFAST).autoSkipped)
    }

    /** 19.7: отмена записи в О возвращает автопропущенные З и С; вручную изменённые не трогает. */
    @Test fun undoRestoresAutoSkipped() {
        val ch = SlotStates.onLog(day, LUNCH, emptyMap(), 1)
        var s = ch.apply(emptyMap())
        // пользователь вручную отменил пропуск С и пропустил снова — это уже ручной пропуск
        s = SlotStates.unskip(SNACK_AM, s).apply(s)
        s = SlotStates.skip(day, SNACK_AM, s, 5).apply(s)
        val undo = SlotStates.onUndo(LUNCH, slotStillHasLogs = false, autoSkipped = ch.autoSkipped, states = s)
        val after = undo.apply(s)
        assertEquals(EMPTY, st(after, LUNCH))
        assertEquals(EMPTY, st(after, BREAKFAST))
        assertEquals(SKIPPED, st(after, SNACK_AM))
    }

    @Test fun undoKeepsSlotWhenOtherLogsRemain() {
        val s = SlotStates.onLog(day, LUNCH, emptyMap(), 1).apply(emptyMap())
        val after = SlotStates.onUndo(LUNCH, slotStillHasLogs = true, autoSkipped = emptyList(), states = s).apply(s)
        assertEquals(LOGGED, st(after, LUNCH))
    }

    /** Ручной пропуск: только статус; «Отменить пропуск» → EMPTY; заполнение пропущенного → LOGGED. */
    @Test fun skipUnskipAndFillSkipped() {
        var s = SlotStates.skip(day, SNACK_PM, emptyMap(), 1).apply(emptyMap())
        assertEquals(SKIPPED, st(s, SNACK_PM))
        assertTrue(SlotStates.skip(day, SNACK_PM, s, 2).upserts.isEmpty())
        s = SlotStates.unskip(SNACK_PM, s).apply(s)
        assertEquals(EMPTY, st(s, SNACK_PM))
        s = SlotStates.skip(day, SNACK_PM, s, 3).apply(s)
        s = SlotStates.onLog(day, SNACK_PM, s, 4).apply(s)
        assertEquals(LOGGED, st(s, SNACK_PM))
        // записанный слот вручную не пропускается
        assertTrue(SlotStates.skip(day, SNACK_PM, s, 5).upserts.isEmpty())
    }

    @Test fun reminderMirror() {
        assertEquals(com.ration.app.domain.model.SlotStatus.PLANNED, SlotStates.reminderStatus(EMPTY))
        assertEquals(com.ration.app.domain.model.SlotStatus.SKIPPED, SlotStates.reminderStatus(SKIPPED))
        assertEquals(com.ration.app.domain.model.SlotStatus.EATEN, SlotStates.reminderStatus(LOGGED))
    }

    /** 19.8: цель на приём — остаток дня / число пустых слотов впереди. */
    @Test fun perSlotTargetFromEmptyAhead() {
        val times = AppSettings().times()
        val s = SlotStates.onLog(day, LUNCH, emptyMap(), 1).apply(emptyMap())
        val ahead = SlotStates.emptyAhead(times, s, nowMinute = 14 * 60 + 30)
        assertEquals(listOf(SNACK_PM, DINNER, EVENING), ahead)
        assertEquals(400.0, SlotStates.perSlotTarget(1200.0, ahead.size), 1e-9)
        assertEquals(0.0, SlotStates.perSlotTarget(-50.0, 2), 1e-9)
    }

    /** 19.8: расписание берётся из типа дня на сегодня, недостающие слоты — из второго; новых чисел нет. */
    @Test fun scheduleFromLegacyTypes() {
        val d = AppSettings()
        val b = SlotSchedule.fromLegacy(d.slotTimesB, d.slotTimesA, DayType.B)
        assertEquals(listOf(9 * 60, 11 * 60 + 30, 14 * 60, 15 * 60, 20 * 60 + 30, 22 * 60), SlotType.entries.map { b.getValue(it) })
        val a = SlotSchedule.fromLegacy(d.slotTimesB, d.slotTimesA, DayType.A)
        assertEquals(12 * 60, a.getValue(LUNCH))
        assertEquals(9 * 60, a.getValue(BREAKFAST))
        assertEquals(10 * 60 + 30, a.getValue(SNACK_AM))
        val custom = SlotSchedule.migrate(d.copy(slotTimesB = d.slotTimesB + ("LUNCH" to 13 * 60)), DayType.B)
        assertEquals(13 * 60, custom.times().getValue(LUNCH))
        // перенесённое расписание больше не пересчитывается
        assertEquals(custom, SlotSchedule.migrate(custom, DayType.A))
        // порядок времени сохраняется
        assertEquals(SlotType.entries, b.entries.sortedBy { it.value }.map { it.key })
    }

    /** Настройки и копии версии 2 со старыми именами слотов читаются. */
    @Test fun legacySettingsJsonDecodes() {
        val json = BackupCodec.json
        val old = """{"kcalTarget":1950,"slotTimesA":{"LUNCH":720,"SNACK_1":900,"SNACK_2":1050,"DINNER":1230,"EVENING":1320},"slotTimesB":{"BREAKFAST":540,"LUNCH":840,"DINNER":1230,"EVENING":1320}}"""
        val s = json.decodeFromString(AppSettings.serializer(), old)
        assertEquals(1950, s.kcalTarget)
        assertNull(s.slotTimes)
        assertEquals(900, SlotSchedule.migrate(s, DayType.A).times().getValue(SNACK_PM))
        assertEquals(SNACK_PM, json.decodeFromString(SlotType.serializer(), "\"SNACK_2\""))
        assertEquals(SNACK_PM, json.decodeFromString(SlotType.serializer(), "\"ROAD_BAR\""))
    }

    /** Копия схемы 2: слоты переименованы, дубликаты расписания убраны, блоки скрыты, статусы по записям. */
    @Test fun backupOfSchema2Upgrades() {
        val lunch = TestData.block("С1")
        val v2 = BackupData(
            schemaVersion = 2,
            products = TestData.seed.products, blocks = TestData.seed.blocks, blockIngredients = TestData.seed.ingredients,
            prepTemplates = TestData.seed.templates,
            mealLogs = listOf(
                MealLog(1, day, 1_780_000_000_000, LUNCH, lunch.id, "С1", null, "С1", 1.0, 620.0, 43.0, MealSource.PLAN),
                MealLog(2, day, 1_780_000_100_000, SNACK_PM, null, null, null, "яблоко", 1.0, 90.0, 0.5, MealSource.CUSTOM),
            ),
            plannedSlots = listOf(
                PlannedSlot(1, day, SNACK_PM, 15 * 60, null),
                PlannedSlot(2, day, SNACK_PM, 17 * 60 + 30, null),
            ),
        )
        val text = BackupCodec.encode(v2, null)
            .replaceFirst("\"slot\":\"SNACK_PM\"", "\"slot\":\"SNACK_1\"")
            .replaceFirst("\"slot\":\"SNACK_PM\"", "\"slot\":\"SNACK_2\"")
        assertTrue(text.contains("SNACK_1") && text.contains("SNACK_2"))
        val d = BackupCodec.decode(text, null)
        assertEquals(BackupCodec.SCHEMA_VERSION, d.schemaVersion)
        assertEquals(1, d.plannedSlots.size)
        assertEquals(setOf(LUNCH, SNACK_PM), d.slotStates.filter { it.status == LOGGED }.map { it.slot }.toSet())
        assertTrue(d.blocks.all { it.hidden == !it.custom })
        assertEquals(2, d.mealLogs.size)
        assertEquals(TestData.seed.products.size, d.products.size)
    }

    private val cat by lazy {
        FoodCatalog(TestData.products, emptyMap(), TestData.seed.templates.flatMap { it.outputs }.associateBy { it.key })
    }
    private val dishes by lazy { DishSplitter.split(TestData.seed.blocks, TestData.seed.ingredients.groupBy { it.blockId }, cat) }

    /** 19.4: блоки разбиты на отдельные блюда, ключ (legacyBlockId, componentIndex) уникален, повтор не даёт дубликатов. */
    @Test fun blocksSplitIntoDishes() {
        val keys = dishes.map { it.legacyBlockId to it.componentIndex }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(dishes, DishSplitter.split(TestData.seed.blocks, TestData.seed.ingredients.groupBy { it.blockId }, cat))
        // У1: рыба, батат, брокколи — отдельные позиции со своим весом
        val u1 = TestData.block("У1")
        val u1Dishes = dishes.filter { it.legacyBlockId == u1.id }
        assertTrue(u1Dishes.size >= 3)
        assertTrue(u1Dishes.any { Tags.FATTY_FISH in it.tags && it.qty == 180.0 })
        // блюдо «на улице» — одно, с ккал и белком из таблицы, без списания
        val o1 = TestData.block("О1")
        val street = dishes.single { it.legacyBlockId == o1.id }
        assertEquals(o1.kcal, street.kcal, 1e-9)
        assertNull(street.productId); assertNull(street.prepKey)
        assertTrue(Tags.FISH in street.tags)
        // каждый блок засева дал хотя бы одно блюдо
        assertEquals(TestData.seed.blocks.filter { !it.custom }.map { it.id }.toSet(), dishes.map { it.legacyBlockId }.toSet())
    }

    @Test fun dishListIsDeduplicated() {
        val list = DishSplitter.distinctForList(dishes)
        assertTrue(list.size < dishes.size)
        val eggs = list.filter { it.prepKey == com.ration.app.data.seed.PrepKeys.EGGS && it.qty == 2.0 }
        assertEquals(1, eggs.size)
    }

    /** Строка из блюда: продукт со списанием по сырому весу, фиксированное блюдо — пропорционально порции. */
    @Test fun dishItems() {
        val salmon = dishes.first { Tags.FATTY_FISH in it.tags && it.productId != null }
        val item = MealItems.ofDish(salmon, salmon.qty, cat)!!
        assertEquals(salmon.kcal, item.kcal, 1e-6)
        assertEquals(salmon.id, item.dishId)
        val street = dishes.first { it.productId == null && it.prepKey == null }
        val half = MealItems.ofDish(street, 0.5, cat)!!
        assertEquals(street.kcal / 2, half.kcal, 1e-9)
        assertEquals(0.0, com.ration.app.domain.meal.ItemsConsumption.shortfall(half, cat), 1e-9)
    }

    /** Пропуск не меняет итоги дня и недельные счётчики: статусы — отдельная таблица, журнал не трогается. */
    @Test fun skipDoesNotTouchTotals() {
        val logs = listOf(MealLog(1, day, 1, LUNCH, null, null, null, "x", 1.0, 500.0, 40.0, MealSource.CUSTOM, tags = listOf(Tags.RED_MEAT)))
        val before = com.ration.app.domain.plan.WeekCounters.of(logs, emptyList())
        SlotStates.skip(day, DINNER, emptyMap(), 1)
        assertEquals(before, com.ration.app.domain.plan.WeekCounters.of(logs, emptyList()))
        assertEquals(500.0, logs.sumOf { it.kcal }, 1e-9)
    }

    @Test fun statusEnum() {
        assertEquals(listOf("EMPTY", "SKIPPED", "LOGGED"), MealSlotStatus.entries.map { it.name })
    }
}
