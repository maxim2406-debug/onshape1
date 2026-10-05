package com.ration.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.MealItem
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.repo.CatalogRepository
import com.ration.app.data.repo.CookRepository
import com.ration.app.data.repo.Drafts
import com.ration.app.data.repo.InventoryRepository
import com.ration.app.data.repo.MealRepository
import com.ration.app.data.repo.PlanRepository
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.TimeUtil
import com.ration.app.domain.cook.FoodRules
import com.ration.app.domain.library.FoodEntry
import com.ration.app.domain.library.LibraryParser
import com.ration.app.domain.meal.CustomBlocks
import com.ration.app.domain.meal.FoodCatalog
import com.ration.app.domain.meal.MealItems
import com.ration.app.domain.meal.ofDish
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.CookSlot
import com.ration.app.domain.model.FoodRole
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.SlotType
import com.ration.app.domain.model.Tags
import com.ration.app.domain.plan.WeekCounters
import com.ration.app.domain.rules.DayRules
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.components.InfoCard
import com.ration.app.ui.components.NumberField
import com.ration.app.ui.components.SectionTitle
import com.ration.app.ui.components.toNumberOrNull
import com.ration.app.ui.library.FoodPickerDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject

data class BuilderUi(
    val loaded: Boolean = false,
    val slot: PlannedSlot? = null,
    val block: Block? = null,
    val original: List<MealItem>? = null,
    val items: List<MealItem> = emptyList(),
    val withFruit: Boolean = false,
    val eatenKcal: Double = 0.0,
    val eatenProtein: Double = 0.0,
    val slotKcal: Double? = null,
    val slotProtein: Double? = null,
    val settings: AppSettings = AppSettings(),
    val preps: List<Prep> = emptyList(),
    val hint: String? = null,
    val done: String? = null,
    val saveAsBlock: Boolean = false,
    val warnings: List<String> = emptyList(),
    val title: String = "",
    /** «Изменить» записанный приём (19.2): сохранение заменяет записи слота. */
    val edit: Boolean = false,
    /** Свои блоки M… — вкладка «Мои сеты» (19.4). */
    val sets: List<Block> = emptyList(),
)

@HiltViewModel
class BuilderViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val plans: PlanRepository,
    private val meals: MealRepository,
    private val catalog: CatalogRepository,
    private val inventory: InventoryRepository,
    private val cook: CookRepository,
    private val drafts: Drafts,
    private val settingsRepo: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val slotId: Long = savedState["slotId"] ?: 0L
    private val blockId: Long = savedState["blockId"] ?: 0L
    private val edit: Boolean = savedState["edit"] ?: false
    val ui = MutableStateFlow(BuilderUi())
    private var dishes: Map<Long, com.ration.app.data.db.entity.Dish> = emptyMap()
    private lateinit var cat: FoodCatalog
    private var todayLogs: List<MealLog> = emptyList()
    private var week = WeekCounters()

    init {
        viewModelScope.launch {
            cat = meals.foodCatalog()
            val slot = if (slotId > 0) plans.slot(slotId) else null
            val day = slot?.day ?: plans.today()
            val block = if (blockId > 0) catalog.block(blockId) else null
            val original = block?.let { MealItems.ofBlock(catalog.ingredientsOf(it.id), cat, slot?.multiplier ?: 1.0) }
            val draft = drafts.builderItems
            drafts.builderItems = null
            val title = drafts.builderTitle ?: ""
            drafts.builderTitle = null
            todayLogs = meals.logsRange(day, day)
            week = plans.weekCounters(day, includeDay = true)
            dishes = catalog.allDishes().associateBy { it.id }
            // «Изменить»: записанный состав слота; старые записи без состава — строкой с ккал и белком записи
            val slotLogs = if (edit && slot != null) meals.logsForSlot(day, slot.slot) else emptyList()
            val editItems = slotLogs.flatMap { l ->
                l.items.filter { !it.untracked || it.qty > 0 }.map { it.copy(deducted = 0.0) }.ifEmpty {
                    listOf(MealItem(name = l.name, qty = 1.0, unit = MeasureUnit.PCS, grams = 0.0, kcal = l.kcal - if (l.withFruit) settingsRepo.current().fruitKcal else 0,
                        protein = l.protein, tags = l.tags))
                }
            }
            // остаток дня без записей этого слота (они заменятся)
            val otherLogs = todayLogs.filter { it.id !in slotLogs.map { l -> l.id }.toSet() }
            val now = LocalDateTime.now(clock).let { it.hour * 60 + it.minute }
            val (tk, tp) = if (slot != null) plans.targetsFor(day, now) else (null to null)
            ui.value = BuilderUi(
                loaded = true, slot = slot, block = block, original = original,
                items = draft ?: original ?: editItems, withFruit = if (edit) slotLogs.any { it.withFruit } else slot?.slot == SlotType.LUNCH,
                eatenKcal = otherLogs.sumOf { it.kcal }, eatenProtein = otherLogs.sumOf { it.protein },
                slotKcal = tk, slotProtein = tp,
                settings = settingsRepo.current(), preps = inventory.activePrepsList(), title = title, edit = edit && slotLogs.isNotEmpty(),
                sets = catalog.allBlocks().filter { it.custom && it.active && !it.hidden },
            )
            todayLogs = otherLogs
            refreshWarnings()
        }
    }

    fun totals(u: BuilderUi = ui.value): Pair<Double, Double> {
        val b = u.block; val o = u.original
        val (k, p) = if (b != null && o != null) MealItems.editedBlockTotals(b, o, u.items, u.slot?.multiplier ?: 1.0)
            else MealItems.sumKcal(u.items) to MealItems.sumProtein(u.items)
        return (k + if (u.withFruit) u.settings.fruitKcal else 0) to p
    }

    /** Предупреждения — те же правила, что для блоков (разделы 4 и 6): как если бы запись уже сделана. */
    private fun refreshWarnings() {
        val u = ui.value
        val (k, p) = totals(u)
        val tags = MealItems.logTags(u.items, u.block, u.block != null)
        val hyp = MealLog(0, u.slot?.day ?: plans.today(), clock.millis(), u.slot?.slot, name = "?", kcal = k, protein = p,
            source = MealSource.CUSTOM, tags = tags, eggs = MealItems.eggs(u.items, cat.products))
        val w = DayRules.warnings(LocalDateTime.now(clock), clock.zone, todayLogs + hyp, emptyList(), u.settings,
            week + WeekCounters.ofTags(tags, null, hyp.eggs)).map { it.text } +
            DayRules.onLogWarnings(week, week + WeekCounters.ofTags(tags, null, hyp.eggs), u.settings)
        ui.value = u.copy(warnings = w.distinct())
    }

    private fun update(items: List<MealItem>, hint: String? = ui.value.hint) {
        ui.value = ui.value.copy(items = items, hint = hint)
        refreshWarnings()
    }

    fun add(e: FoodEntry, qty: Double? = null) = viewModelScope.launch {
        e.dish?.let { d ->
            MealItems.ofDish(d, qty ?: d.qty, cat)?.let { update(ui.value.items + it) }
            return@launch
        }
        val item = when {
            e.productId != null -> {
                val p = cat.products[e.productId] ?: catalog.product(e.productId) ?: return@launch
                val range = FoodRules.portion(p)
                val q = qty ?: if (p.unit == MeasureUnit.PCS) 1.0 else (if (FoodRules.role(p) == FoodRole.VEG) ui.value.settings.vegQuickGrams.firstOrNull()?.toDouble() ?: 100.0 else range.start)
                val counterpart = LibraryParser.rawCookedCounterpart(p, cat.products.values.toList())
                val hint = counterpart?.let { "«${p.name}»: вес ${p.cooked?.label}? Есть и «${it.name}»." }
                update(ui.value.items + MealItems.ofProduct(p, q, p.unit), hint ?: ui.value.hint)
                return@launch
            }
            e.customFoodId != null -> catalog.customFood(e.customFoodId)?.let { MealItems.ofCustom(it, qty ?: it.portionGrams ?: 100.0) }
            else -> null
        } ?: return@launch
        update(ui.value.items + item)
    }

    /** «Мои сеты»: состав своего блока добавляется отдельными строками. */
    fun addSet(b: Block) = viewModelScope.launch {
        update(ui.value.items + MealItems.ofBlock(catalog.ingredientsOf(b.id), cat).filter { !it.untracked || it.qty > 0 })
    }

    fun addPrep(p: Prep) {
        val unit = if (p.portionGrams != null) MeasureUnit.G else MeasureUnit.PCS
        MealItems.ofPrep(p.outputKey, p.portionGrams ?: 1.0, unit, cat)?.let { update(ui.value.items + it) }
    }

    fun setQty(i: Int, qty: Double) {
        val it0 = ui.value.items.getOrNull(i) ?: return
        val updated = when {
            it0.dishId != null && it0.productId == null && it0.prepKey == null -> dishes[it0.dishId]?.let { MealItems.ofDish(it, qty, cat) }
                ?: it0.copy(qty = qty, kcal = if (it0.qty > 0) it0.kcal / it0.qty * qty else it0.kcal, protein = if (it0.qty > 0) it0.protein / it0.qty * qty else it0.protein)
            it0.prepKey != null -> MealItems.ofPrep(it0.prepKey, qty, it0.unit, cat)
            it0.customFoodId != null -> cat.customFoods[it0.customFoodId]?.let { MealItems.ofCustom(it, qty) }
            it0.productId != null -> cat.products[it0.productId]?.let { MealItems.ofProduct(it, qty, it0.unit) }
            else -> null
        }?.copy(name = it0.name) ?: return
        update(ui.value.items.toMutableList().also { it[i] = updated })
    }

    /** Быстрые кнопки «100/200/300 г» для овощей — всегда в граммах. */
    fun setGrams(i: Int, g: Double) {
        val it0 = ui.value.items.getOrNull(i) ?: return
        val p = it0.productId?.let { cat.products[it] } ?: return
        update(ui.value.items.toMutableList().also { it[i] = MealItems.ofProduct(p, g, MeasureUnit.G).copy(name = it0.name) })
    }

    fun remove(i: Int) = update(ui.value.items.toMutableList().also { it.removeAt(i) })

    fun replace(i: Int, e: FoodEntry) = viewModelScope.launch {
        val old = ui.value.items.getOrNull(i) ?: return@launch
        e.dish?.let { d ->
            MealItems.ofDish(d, d.qty, cat)?.let { item -> update(ui.value.items.toMutableList().also { it[i] = item }) }
            return@launch
        }
        val p = e.productId?.let { cat.products[it] ?: catalog.product(it) }
        val item = when {
            p != null -> MealItems.ofProduct(p, UnitConvQty.convert(old, p), p.unit)
            e.customFoodId != null -> catalog.customFood(e.customFoodId)?.let { MealItems.ofCustom(it, old.grams.takeIf { g -> g > 0 } ?: 100.0) }
            else -> null
        } ?: return@launch
        update(ui.value.items.toMutableList().also { it[i] = item })
    }

    fun isVeg(item: MealItem): Boolean = item.productId?.let { cat.products[it] }?.let { FoodRules.role(it) == FoodRole.VEG } == true

    fun setFruit(v: Boolean) { ui.value = ui.value.copy(withFruit = v); refreshWarnings() }
    fun dismissHint() { ui.value = ui.value.copy(hint = null) }

    fun save() = viewModelScope.launch {
        val u = ui.value
        val tracked = u.items.filter { !(it.untracked && it.qty <= 0) }
        if (tracked.isEmpty()) return@launch
        val day = u.slot?.day ?: plans.today()
        val name = u.title.ifBlank { tracked.joinToString(" + ") { it.name.substringBefore(',') }.take(80) }
        val slot = u.slot
        val out = if (u.edit && slot != null) meals.replaceSlot(day, slot.slot, name, u.items, u.withFruit)
        else meals.logItems(day, slot?.slot, name, u.items, basedOn = u.block, originalItems = u.original, withFruit = u.withFruit)
        ui.value = u.copy(done = out.message() ?: "Записано", saveAsBlock = u.block == null && !u.edit)
    }

    fun saveAsBlock(name: String, kind: MealKind, tags: List<String>) = viewModelScope.launch {
        val u = ui.value
        val code = CustomBlocks.nextCode(catalog.allBlocks().map { it.code })
        val (block, ings) = CustomBlocks.build(code, name.ifBlank { code }, kind, u.items.filter { !it.untracked || it.qty > 0 }, tags)
        catalog.saveCustomBlock(block.copy(tags = (block.tags + tags).distinct()), ings)
        ui.value = u.copy(saveAsBlock = false, done = "Сохранён свой блок $code")
    }

    fun skipBlock() { ui.value = ui.value.copy(saveAsBlock = false) }
    fun kindFor(u: BuilderUi): MealKind = u.slot?.slot?.let { CookSlot.of(it).mealKinds.first() } ?: u.block?.kind ?: MealKind.DINNER
}

/** Пересчёт количества при замене продукта: граммы сохраняются, штуки — по весу штуки. */
object UnitConvQty {
    fun convert(old: MealItem, p: com.ration.app.data.db.entity.Product): Double {
        val grams = if (old.grams > 0) old.grams else 100.0
        return if (p.unit == MeasureUnit.PCS) maxOf(1.0, Math.round(grams / (p.gramsPerPiece ?: 100.0)).toDouble()) else grams
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuilderScreen(nav: NavController, vm: BuilderViewModel = hiltViewModel()) {
    val u by vm.ui.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<Int?>(null) }
    var prepMenu by remember { mutableStateOf(false) }
    val (kcal, protein) = vm.totals(u)
    val s = u.settings
    val remainKcal = s.kcalTarget - u.eatenKcal
    val remainProtein = s.proteinTarget - u.eatenProtein

    var menu by remember { mutableStateOf(false) }
    var setsOpen by remember { mutableStateOf(false) }
    val slotTitle = u.slot?.let { "${it.slot.title} ${TimeUtil.hm(it.minuteOfDay)}" }
    val title = when {
        u.block != null -> "Состав: ${u.block!!.code}"
        u.edit && slotTitle != null -> "Изменить: $slotTitle"
        slotTitle != null -> slotTitle
        else -> "Собрать из продуктов"
    }
    Scaffold(topBar = {
        BackTopBar(title, { nav.popBackStack() }) {
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    // 19.4: «Что приготовить» доступен только отсюда (и из уведомления о нехватке белка)
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Что приготовить") }, onClick = {
                        menu = false
                        nav.navigate("cook" + (u.slot?.let { "?slot=${it.slot.name}" } ?: ""))
                    })
                }
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                item {
                    Text("Масло, заправку и соус добавляйте отдельными строками. Приправы «по вкусу» не учитываются.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    u.hint?.let { h ->
                        InfoCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(h, Modifier.weight(1f)); TextButton(onClick = vm::dismissHint) { Text("OK") }
                            }
                        }
                    }
                }
                itemsIndexed(u.items) { i, item -> ItemRow(item, u.settings, u.loaded && vm.isVeg(item), onQty = { vm.setQty(i, it) }, onGrams = { vm.setGrams(i, it) }, onRemove = { vm.remove(i) }, onReplace = { replacing = i }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        Button(onClick = { picking = true }) { Text("+ Продукт или блюдо") }
                        if (u.preps.isNotEmpty()) Column {
                            OutlinedButton(onClick = { prepMenu = true }) { Text("+ Заготовка") }
                            androidx.compose.material3.DropdownMenu(prepMenu, { prepMenu = false }) {
                                u.preps.distinctBy { it.outputKey }.forEach { p ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(p.name) }, onClick = { prepMenu = false; vm.addPrep(p) })
                                }
                            }
                        }
                    }
                    if (u.sets.isNotEmpty()) {
                        TextButton(onClick = { setsOpen = !setsOpen }) { Text((if (setsOpen) "▾ " else "▸ ") + "Мои сеты (${u.sets.size})") }
                        if (setsOpen) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            u.sets.forEach { b -> AssistChip(onClick = { vm.addSet(b) }, label = { Text("${b.code} ${b.name}") }) }
                        }
                    }
                    if (u.slot?.slot == SlotType.LUNCH || u.withFruit) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(u.withFruit, vm::setFruit); Text("+ фрукт (+${s.fruitKcal} ккал)")
                    }
                    if (u.warnings.isNotEmpty()) InfoCard(container = MaterialTheme.colorScheme.errorContainer) {
                        u.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Итог: ${Math.round(kcal)} ккал · ${Math.round(protein)} г белка", fontWeight = FontWeight.Bold)
                    u.slotKcal?.let { sk -> Text("Цель приёма: ${Math.round(sk)} ккал · ${Math.round(u.slotProtein ?: 0.0)} г (разница ${signed(kcal - sk)} ккал)", style = MaterialTheme.typography.bodySmall) }
                    Text("Остаток дня до записи: ${Math.round(remainKcal)} ккал · ${Math.round(remainProtein)} г; после: ${Math.round(remainKcal - kcal)} ккал · ${Math.round(remainProtein - protein)} г",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::save, enabled = u.loaded && u.items.any { !it.untracked || it.qty > 0 }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Записать") }
                }
            }
        }
    }
    if (picking) FoodPickerDialog("Продукт или блюдо", onDismiss = { picking = false }, includeDishes = true) { e -> picking = false; vm.add(e) }
    replacing?.let { i -> FoodPickerDialog("Заменить на", onDismiss = { replacing = null }, includeDishes = true) { e -> replacing = null; vm.replace(i, e) } }

    if (u.saveAsBlock) SaveBlockDialog(u, vm)
    else u.done?.let { msg ->
        AlertDialog(onDismissRequest = { nav.popBackStack() }, confirmButton = { TextButton(onClick = { nav.popBackStack() }) { Text("OK") } },
            title = { Text("Записано") }, text = { Text(msg) })
    }
}

private fun signed(v: Double) = (if (v > 0) "+" else "") + Math.round(v)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(item: MealItem, s: AppSettings, isVeg: Boolean, onQty: (Double) -> Unit, onGrams: (Double) -> Unit, onRemove: () -> Unit, onReplace: () -> Unit) {
    var text by remember(item.name, item.productId, item.prepKey) { mutableStateOf(TimeUtil.num(item.qty)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, Modifier.weight(1f), color = if (item.untracked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = onReplace) { Icon(Icons.Filled.Refresh, "Заменить") }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Убрать") }
        }
        if (item.untracked && item.qty <= 0) {
            Text("по вкусу, не учитывается", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Кол-во", text, { v -> text = v; v.toNumberOrNull()?.takeIf { it > 0 && it < 10_000 }?.let(onQty) }, Modifier.width(140.dp), item.unit.label)
                Text("${Math.round(item.kcal)} ккал · ${TimeUtil.num(item.protein)} г", style = MaterialTheme.typography.bodySmall)
            }
            if (isVeg) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.vegQuickGrams.forEach { g -> AssistChip(onClick = { text = g.toString(); onGrams(g.toDouble()) }, label = { Text("$g г") }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveBlockDialog(u: BuilderUi, vm: BuilderViewModel) {
    var name by remember { mutableStateOf(u.title.ifBlank { u.items.firstOrNull()?.name?.substringBefore(',') ?: "" }) }
    var kind by remember { mutableStateOf(vm.kindFor(u)) }
    val auto = remember(u.items) { u.items.flatMap { it.tags }.filter { it in Tags.FOOD_TAGS }.toSet() }
    var tags by remember { mutableStateOf(auto) }
    AlertDialog(
        onDismissRequest = vm::skipBlock,
        title = { Text("Сохранить как мой блок?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                u.done?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Название") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealKind.entries.filter { it != MealKind.LUNCH_STREET }.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.label) }) }
                }
                Text("Теги (по составу)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Tags.FISH, Tags.FATTY_FISH, Tags.SEAFOOD, Tags.RED_MEAT, Tags.EGG, Tags.CARRY, Tags.HOME).forEach { t ->
                        FilterChip(t in tags, { tags = if (t in tags) tags - t else tags + t }, { Text(t) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveAsBlock(name, kind, tags.toList()) }) { Text("Сохранить блок") } },
        dismissButton = { TextButton(onClick = vm::skipBlock) { Text("Не нужно") } },
    )
}
