package com.ration.app.data.repo

import androidx.room.withTransaction
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.entity.Deduction
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.Purchase
import com.ration.app.data.db.entity.PurchaseLine
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.inventory.Ledger
import com.ration.app.domain.inventory.Shortage
import com.ration.app.domain.inventory.ShoppingItem
import com.ration.app.domain.inventory.StockSnapshot
import com.ration.app.domain.inventory.ThresholdEvaluation
import com.ration.app.domain.inventory.ThresholdTracker
import com.ration.app.domain.inventory.Thresholds
import com.ration.app.domain.inventory.UnitConv
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.notifications.AppNotifier
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Строка покупки после экрана проверки. */
data class PurchaseInput(
    val productId: Long,
    val rawName: String,
    val qty: Double,
    val unit: MeasureUnit,
    val price: Double? = null,
    val expiresDay: Long? = null,
)

data class CookResult(val preps: List<Prep>, val shortages: List<Shortage>)

@Singleton
class InventoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val catalog: CatalogRepository,
    private val notifier: AppNotifier,
    private val clock: Clock,
) {
    val stock: Flow<List<StockItem>> = db.stock().observeAll()
    val activePreps: Flow<List<Prep>> = db.preps().observeActive()

    fun today(): Long = LocalDate.now(clock).toEpochDay()

    suspend fun snapshot(): StockSnapshot = StockSnapshot(db.stock().getAll(), db.preps().active(), today())

    suspend fun totals(): Map<Long, Double> = db.stock().totals().associate { it.productId to it.total }

    suspend fun addBatch(productId: Long, qty: Double, expiresDay: Long? = null, note: String = "") {
        if (qty <= 0) return
        db.stock().insert(StockItem(productId = productId, qty = qty, purchasedDay = today(), expiresDay = expiresDay, note = note))
        checkThresholds()
    }

    /** Ручная правка остатка: уменьшение — по FIFO, увеличение — новой партией. */
    suspend fun setTotal(productId: Long, newTotal: Double) {
        db.withTransaction {
            val items = db.stock().forProduct(productId)
            val cur = items.sumOf { it.qty }
            if (newTotal < cur) {
                val out = mutableListOf<Deduction>()
                StockSnapshot(items, emptyList(), today()).takeStock(productId, cur - newTotal, out)
                val (updated, _) = Ledger.apply(items, emptyList(), out, -1)
                db.stock().updateAll(updated)
            } else if (newTotal > cur) {
                db.stock().insert(StockItem(productId = productId, qty = newTotal - cur, purchasedDay = today(), note = "ручная правка"))
            }
        }
        checkThresholds()
    }

    /** Ручной нормальный запас; null — вернуться к количеству из последней закупки. */
    suspend fun setPar(productId: Long, par: Double?) {
        val p = catalog.product(productId) ?: return
        val updated = if (par != null && par > 0) p.copy(parLevel = par, parManual = true)
        else p.copy(parLevel = db.purchases().lastLineFor(productId)?.qty, parManual = false)
        catalog.saveProduct(updated)
        checkThresholds()
    }

    suspend fun setUntracked(productId: Long, untracked: Boolean) {
        val p = catalog.product(productId) ?: return
        catalog.saveProduct(p.copy(untracked = untracked))
    }

    /** Сохранение покупки: партии, псевдонимы, нормальный запас по последней закупке. */
    suspend fun savePurchase(day: Long, store: String, lines: List<PurchaseInput>) {
        if (lines.isEmpty()) return
        db.withTransaction {
            val purchaseId = db.purchases().insert(Purchase(day = day, store = store.take(100)))
            val products = db.products().getAll().associateBy { it.id }
            val perProduct = mutableMapOf<Long, Double>()
            val purchaseLines = mutableListOf<PurchaseLine>()
            for (l in lines) {
                val p = products[l.productId] ?: continue
                val qty = UnitConv.toProductUnit(l.qty, l.unit, p) ?: l.qty
                purchaseLines += PurchaseLine(purchaseId = purchaseId, productId = p.id, name = l.rawName.take(120), qty = qty, unit = p.unit, price = l.price)
                db.stock().insert(StockItem(productId = p.id, qty = qty, purchasedDay = day, expiresDay = l.expiresDay))
                perProduct[p.id] = (perProduct[p.id] ?: 0.0) + qty
                catalog.addAlias(p.id, l.rawName)
            }
            db.purchases().insertLines(purchaseLines)
            for ((pid, qty) in perProduct) {
                val p = db.products().get(pid) ?: continue
                if (!p.parManual) db.products().update(p.copy(parLevel = qty))
            }
        }
        checkThresholds()
    }

    /** Применение (sign = -1) или отмена (+1) списаний. */
    suspend fun applyDeductions(deductions: List<Deduction>, sign: Int) {
        if (deductions.isEmpty()) return
        db.withTransaction {
            val stock = db.stock().getAll()
            val preps = db.preps().getAll()
            val (s, p) = Ledger.apply(stock, preps, deductions, sign)
            val stockIds = deductions.mapNotNull { it.stockItemId }.toSet()
            val prepIds = deductions.mapNotNull { it.prepId }.toSet()
            db.stock().updateAll(s.filter { it.id in stockIds })
            db.preps().updateAll(p.filter { it.id in prepIds })
        }
    }

    suspend fun shoppingList(): List<ShoppingItem> {
        val s = settings.current()
        return Thresholds.shoppingList(db.products().getAll(), totals(), s.buyThresholdPct, s.urgentThresholdPct)
    }

    /** Проверка порогов после списаний, покупок и ежедневно в 18:00. */
    suspend fun checkThresholds(): ThresholdEvaluation {
        val s = settings.current()
        val list = Thresholds.shoppingList(db.products().getAll(), totals(), s.buyThresholdPct, s.urgentThresholdPct)
        val eval = ThresholdTracker.evaluate(
            settings.alertState(), list.map { it.product.id }.toSet(), list.filter { it.urgent }.map { it.product.id }.toSet(), today(),
        )
        settings.setAlertState(eval.state)
        if (eval.notify) notifier.urgentShopping(list.size)
        return eval
    }

    suspend fun markBought(item: ShoppingItem, qty: Double) {
        savePurchase(today(), "", listOf(PurchaseInput(item.product.id, item.product.name, qty, item.product.unit)))
    }

    // ---- Заготовки ----

    /** «Приготовил»: списать входы × замесы, создать заготовки. Нехватка — предупреждение, не блок. */
    suspend fun cook(template: PrepTemplate, batches: Double, yieldGrams: Map<String, Double?> = emptyMap(),
                     inputOverrides: Map<Long, Double> = emptyMap()): CookResult {
        val today = today()
        val products = db.products().getAll().associateBy { it.id }
        var result = CookResult(emptyList(), emptyList())
        db.withTransaction {
            val snap = StockSnapshot(db.stock().getAll(), emptyList(), today)
            val out = mutableListOf<Deduction>()
            val shortages = mutableListOf<Shortage>()
            for (input in template.inputs) {
                val p = products[input.productId] ?: continue
                if (p.untracked) continue
                val need = (inputOverrides[input.productId] ?: input.qty) * batches
                val missing = snap.takeStock(p.id, need, out)
                if (missing > 1e-6) shortages += Shortage(p.name, missing, p.unit)
            }
            val (stock, _) = Ledger.apply(db.stock().getAll(), emptyList(), out, -1)
            val touched = out.mapNotNull { it.stockItemId }.toSet()
            db.stock().updateAll(stock.filter { it.id in touched })
            val created = template.outputs.map { o ->
                val grams = yieldGrams[o.key]
                val portions = if (grams != null && o.portionGrams != null) grams / o.portionGrams else o.defaultPortions * batches
                Prep(
                    templateId = template.id, outputKey = o.key, name = o.name, madeDay = today,
                    expiresDay = today + template.shelfDays, portionsTotal = portions, portionsLeft = portions,
                    portionGrams = o.portionGrams,
                )
            }.filter { it.portionsTotal > 0 }
            val withIds = created.map { it.copy(id = db.preps().insert(it)) }
            result = CookResult(withIds, shortages)
        }
        checkThresholds()
        return result
    }

    /** Нехватка для набора шаблонов (экран «Заготовка на субботу»). */
    suspend fun shortagesFor(templates: List<PrepTemplate>, batches: Double = 1.0): List<Shortage> {
        val products = db.products().getAll().associateBy { it.id }
        val totals = totals()
        val need = mutableMapOf<Long, Double>()
        templates.forEach { t -> t.inputs.forEach { need[it.productId] = (need[it.productId] ?: 0.0) + it.qty * batches } }
        return need.mapNotNull { (pid, q) ->
            val p = products[pid] ?: return@mapNotNull null
            if (p.untracked) return@mapNotNull null
            val have = totals[pid] ?: 0.0
            if (have + 1e-6 >= q) null else Shortage(p.name, q - have, p.unit)
        }
    }

    suspend fun discardPrep(prep: Prep) {
        db.preps().update(prep.copy(discarded = true, portionsLeft = 0.0))
    }

    suspend fun setPrepPortions(prep: Prep, portions: Double) {
        db.preps().update(prep.copy(portionsLeft = portions.coerceIn(0.0, maxOf(prep.portionsTotal, portions)), portionsTotal = maxOf(prep.portionsTotal, portions)))
    }

    suspend fun activePrepsList(): List<Prep> = db.preps().active()

    suspend fun productsMap(): Map<Long, Product> = db.products().getAll().associateBy { it.id }
}
