package com.ration.app.domain.backup

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.CustomFood
import com.ration.app.data.db.entity.DayPlan
import com.ration.app.data.db.entity.Dish
import com.ration.app.data.db.entity.SlotState
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.PlannedSlot
import com.ration.app.data.db.entity.Prep
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.Purchase
import com.ration.app.data.db.entity.PurchaseLine
import com.ration.app.data.db.entity.QuickLog
import com.ration.app.data.db.entity.Recipe
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.db.entity.Substitution
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.domain.model.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Полный снимок базы. API-ключ сюда не входит никогда (он в EncryptedSharedPreferences). */
@Serializable
data class BackupData(
    val schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
    val exportedAtMillis: Long = 0,
    val settings: AppSettings = AppSettings(),
    val products: List<Product> = emptyList(),
    val stock: List<StockItem> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val purchaseLines: List<PurchaseLine> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val blockIngredients: List<BlockIngredient> = emptyList(),
    val prepTemplates: List<PrepTemplate> = emptyList(),
    val preps: List<Prep> = emptyList(),
    val mealLogs: List<MealLog> = emptyList(),
    val customFoods: List<CustomFood> = emptyList(),
    val substitutions: List<Substitution> = emptyList(),
    val dayPlans: List<DayPlan> = emptyList(),
    val plannedSlots: List<PlannedSlot> = emptyList(),
    val quickLogs: List<QuickLog> = emptyList(),
    val weights: List<WeightLog> = emptyList(),
    val bp: List<BpLog> = emptyList(),
    /** Пользовательские рецепты (source = user); встроенная база восстанавливается из приложения. */
    val recipes: List<Recipe> = emptyList(),
    /** Статусы приёмов (схема 3). */
    val slotStates: List<SlotState> = emptyList(),
    /** Отдельные блюда (схема 3): id сохраняются, на них ссылаются строки состава в журнале. */
    val dishes: List<Dish> = emptyList(),
)

/**
 * Приведение копии прежней схемы к текущей (19.6): имена слотов SNACK_1/SNACK_2/ROAD_BAR читает [com.ration.app.domain.model.SlotTypeSerializer];
 * здесь — то же, что делает MIGRATION_2_3 с базой.
 */
object BackupUpgrade {
    fun toCurrent(d: BackupData): BackupData {
        // после слияния П1/П2/дороги в П в расписании дня может оказаться две строки одного слота — остаётся первая
        val slots = d.plannedSlots.distinctBy { it.day to it.slot }
        if (d.schemaVersion >= 3) return d.copy(plannedSlots = slots)
        val states = d.mealLogs.filter { it.slot != null }.groupBy { it.day to it.slot!! }
            .map { (k, logs) -> SlotState(k.first, k.second, com.ration.app.domain.model.MealSlotStatus.LOGGED, false, logs.maxOf { it.atMillis }) }
        // копия схемы 1: у продуктов нет источника — свои (без ключа засева) USER, как в MIGRATION_1_2
        val products = if (d.schemaVersion >= 2) d.products else d.products.map {
            when {
                it.key == null -> it.copy(source = com.ration.app.domain.model.ProductSource.USER)
                it.key == "cottage" || it.key == "protein_yogurt" -> it.copy(source = com.ration.app.domain.model.ProductSource.LABEL)
                else -> it
            }
        }
        return d.copy(
            products = products,
            blocks = d.blocks.map { it.copy(hidden = !it.custom) },
            plannedSlots = slots,
            slotStates = states,
            schemaVersion = BackupCodec.SCHEMA_VERSION,
        )
    }
}

@Serializable
data class BackupEnvelope(
    val format: String = BackupCodec.FORMAT,
    val schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
    val encrypted: Boolean,
    val kdf: String? = null,
    val iterations: Int? = null,
    val salt: String? = null,
    val iv: String? = null,
    /** JSON BackupData (открытый) или base64 шифротекста. */
    val payload: String,
)

class BackupException(message: String) : Exception(message)

object BackupCodec {
    const val FORMAT = "ration-backup"
    const val SCHEMA_VERSION = 3
    const val MAX_BYTES = 20_000_000
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun encode(data: BackupData, password: CharArray?): String {
        val plain = json.encodeToString(BackupData.serializer(), data)
        val env = if (password == null || password.isEmpty()) {
            BackupEnvelope(encrypted = false, payload = plain)
        } else {
            val rnd = SecureRandom()
            val salt = ByteArray(16).also(rnd::nextBytes)
            val iv = ByteArray(12).also(rnd::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, PBKDF2_ITERATIONS), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(FORMAT.toByteArray())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            BackupEnvelope(
                encrypted = true, kdf = "PBKDF2WithHmacSHA256", iterations = PBKDF2_ITERATIONS,
                salt = b64(salt), iv = b64(iv), payload = b64(ct),
            )
        }
        return json.encodeToString(BackupEnvelope.serializer(), env)
    }

    fun isEncrypted(text: String): Boolean = runCatching { parseEnvelope(text).encrypted }.getOrDefault(false)

    /** Разбор с полной проверкой. Ошибка — исключение, частичной записи не бывает. */
    fun decode(text: String, password: CharArray?): BackupData {
        val env = parseEnvelope(text)
        val plain = if (env.encrypted) {
            if (password == null || password.isEmpty()) throw BackupException("Файл зашифрован: нужен пароль")
            if (env.kdf != "PBKDF2WithHmacSHA256") throw BackupException("Неизвестный алгоритм ключа")
            val iter = env.iterations ?: throw BackupException("Повреждённый файл")
            if (iter !in 10_000..5_000_000) throw BackupException("Недопустимые параметры шифрования")
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, unb64(env.salt), iter), GCMParameterSpec(GCM_TAG_BITS, unb64(env.iv)))
                cipher.updateAAD(FORMAT.toByteArray())
                String(cipher.doFinal(unb64(env.payload)), Charsets.UTF_8)
            } catch (e: Exception) {
                throw BackupException("Неверный пароль или повреждённый файл")
            }
        } else env.payload
        val data = try {
            json.decodeFromString(BackupData.serializer(), plain)
        } catch (e: Exception) {
            throw BackupException("Файл не соответствует схеме резервной копии")
        }
        validate(data)
        return BackupUpgrade.toCurrent(data).also { validate(it) }
    }

    private fun parseEnvelope(text: String): BackupEnvelope {
        if (text.length > MAX_BYTES) throw BackupException("Файл больше ${MAX_BYTES / 1_000_000} МБ")
        val env = try {
            json.decodeFromString(BackupEnvelope.serializer(), text)
        } catch (e: Exception) {
            throw BackupException("Это не файл резервной копии «Рациона»")
        }
        if (env.format != FORMAT) throw BackupException("Это не файл резервной копии «Рациона»")
        if (env.schemaVersion > SCHEMA_VERSION) throw BackupException("Файл создан более новой версией приложения")
        return env
    }

    /** Проверка типов, диапазонов и ссылочной целостности. */
    fun validate(d: BackupData) {
        fun check(ok: Boolean, msg: String) { if (!ok) throw BackupException("Ошибка данных: $msg") }
        fun finite(v: Double) = v.isFinite()
        check(d.schemaVersion in 1..SCHEMA_VERSION, "версия схемы")
        val s = d.settings
        check(s.kcalMin in 500..6000 && s.kcalMax in 500..6000 && s.kcalMin <= s.kcalMax, "цели ккал")
        check(s.proteinMin in 0..400 && s.proteinMax in 0..400 && s.proteinMin <= s.proteinMax, "цели белка")
        val minutes = listOf(s.forecastTime, s.shoppingCheckTime, s.quietStart, s.quietEnd, s.coffeeLimit, s.weighTime, s.prepTime) +
            s.slotTimesA.values + s.slotTimesB.values + s.slotTimes.orEmpty().values
        check(minutes.all { it in 0 until 24 * 60 }, "время в настройках")
        check(s.buyThresholdPct in 1..100 && s.urgentThresholdPct in 0..s.buyThresholdPct, "пороги закупки")

        val productIds = d.products.map { it.id }.toSet()
        check(productIds.size == d.products.size, "повтор id продукта")
        d.products.forEach {
            check(it.name.isNotBlank() && it.name.length <= 200, "название продукта")
            check(finite(it.kcalPer100) && it.kcalPer100 in 0.0..1000.0, "ккал продукта «${it.name}»")
            check(finite(it.proteinPer100) && it.proteinPer100 in 0.0..100.0, "белок продукта «${it.name}»")
            check(it.gramsPerPiece == null || it.gramsPerPiece in 0.1..10_000.0, "вес штуки «${it.name}»")
            check(it.parLevel == null || it.parLevel in 0.0..1_000_000.0, "нормальный запас «${it.name}»")
            check(it.aliases.size <= 50 && it.aliases.all { a -> a.length <= 200 }, "псевдонимы «${it.name}»")
        }
        d.stock.forEach { check(it.productId in productIds && finite(it.qty) && it.qty in 0.0..1_000_000.0, "партия ${it.id}") }
        val purchaseIds = d.purchases.map { it.id }.toSet()
        d.purchaseLines.forEach { check(it.purchaseId in purchaseIds && it.productId in productIds && it.qty in 0.0..1_000_000.0, "строка покупки ${it.id}") }
        val blockIds = d.blocks.map { it.id }.toSet()
        check(blockIds.size == d.blocks.size && d.blocks.map { it.code }.toSet().size == d.blocks.size, "повтор блока")
        d.blocks.forEach { check(it.kcal in 0.0..5000.0 && it.protein in 0.0..500.0, "блок ${it.code}") }
        d.blockIngredients.forEach {
            check(it.blockId in blockIds, "ингредиент ${it.id}")
            check(it.productId == null || it.productId in productIds, "продукт ингредиента ${it.id}")
            check(it.altProductIds.all { a -> a in productIds }, "альтернатива ингредиента ${it.id}")
            check(it.qty == null || it.qty in 0.0..100_000.0, "количество ингредиента ${it.id}")
        }
        val templateIds = d.prepTemplates.map { it.id }.toSet()
        d.prepTemplates.forEach { t -> check(t.inputs.all { it.productId in productIds } && t.shelfDays in 0..365, "шаблон ${t.name}") }
        d.preps.forEach { check(it.templateId in templateIds && it.portionsLeft in 0.0..10_000.0 && it.portionsLeft <= it.portionsTotal + 1e-6, "заготовка ${it.id}") }
        d.mealLogs.forEach {
            check(it.kcal in 0.0..20_000.0 && it.protein in 0.0..2_000.0 && it.multiplier in 0.0..10.0, "приём пищи ${it.id}")
            check(it.blockId == null || it.blockId in blockIds, "блок приёма ${it.id}")
        }
        val foodIds = d.customFoods.map { it.id }.toSet()
        d.customFoods.forEach { check(it.kcalPer100 in 0.0..1000.0 && it.proteinPer100 in 0.0..100.0, "свой продукт ${it.name}") }
        d.substitutions.forEach { check(it.blockId in blockIds && it.customFoodId in foodIds && it.qtyGrams in 0.0..10_000.0, "замена ${it.id}") }
        d.plannedSlots.forEach { check(it.minuteOfDay in 0 until 24 * 60 && (it.blockId == null || it.blockId in blockIds), "слот ${it.id}") }
        d.quickLogs.forEach { check(it.amount in 0.0..100_000.0, "быстрая запись ${it.id}") }
        d.weights.forEach { check(it.kg in 20.0..400.0, "вес ${it.id}") }
        d.products.forEach { check(it.edibleFraction in 0.05..1.0 && it.tags.size <= 20, "поля продукта «${it.name}»") }
        d.mealLogs.forEach { l -> check(l.items.size <= 50 && l.items.all { it.qty in 0.0..100_000.0 && it.kcal in 0.0..20_000.0 }, "состав приёма ${l.id}") }
        d.recipes.forEach { r -> check(r.title.isNotBlank() && r.steps.size <= 20 && r.ingredients.size <= 30, "рецепт ${r.id}") }
        check(d.slotStates.map { it.day to it.slot }.toSet().size == d.slotStates.size, "повтор статуса слота")
        val dishIds = d.dishes.map { it.id }.toSet()
        check(dishIds.size == d.dishes.size && d.dishes.map { it.legacyBlockId to it.componentIndex }.toSet().size == d.dishes.size, "повтор блюда")
        d.dishes.forEach {
            check(it.name.isNotBlank() && it.name.length <= 200 && it.qty in 0.0..100_000.0, "блюдо ${it.id}")
            check(finite(it.kcal) && it.kcal in 0.0..20_000.0 && it.protein in 0.0..2_000.0, "блюдо ${it.id}")
            check(it.productId == null || it.productId in productIds, "продукт блюда ${it.id}")
        }
        d.mealLogs.forEach { l -> check(l.autoSkipped.size <= 6, "автопропуск приёма ${l.id}") }
        d.bp.forEach { check(it.systolic in 40..300 && it.diastolic in 20..200 && (it.pulse == null || it.pulse in 20..250), "давление ${it.id}") }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String?): ByteArray = try {
        Base64.getDecoder().decode(s ?: throw BackupException("Повреждённый файл"))
    } catch (e: IllegalArgumentException) {
        throw BackupException("Повреждённый файл")
    }
}
