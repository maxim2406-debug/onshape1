package com.ration.app

import com.ration.app.data.db.entity.BpLog
import com.ration.app.data.db.entity.MealLog
import com.ration.app.data.db.entity.StockItem
import com.ration.app.data.db.entity.WeightLog
import com.ration.app.domain.backup.BackupCodec
import com.ration.app.domain.backup.BackupData
import com.ration.app.domain.backup.BackupException
import com.ration.app.domain.model.AppSettings
import com.ration.app.domain.model.MealSource
import com.ration.app.domain.model.SlotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val data = BackupData(
        exportedAtMillis = 1_780_000_000_000,
        settings = AppSettings(kcalTarget = 1950, seeded = true),
        products = TestData.seed.products,
        blocks = TestData.seed.blocks,
        blockIngredients = TestData.seed.ingredients,
        prepTemplates = TestData.seed.templates,
        stock = listOf(StockItem(1, TestData.product("egg").id, 12.0, 20_000, 20_020, "лоток")),
        mealLogs = listOf(MealLog(1, 20_001, 1_780_000_000_000, SlotType.LUNCH, TestData.block("С1").id, "С1", null, "С1", 1.0, 620.0, 43.0, MealSource.PLAN)),
        weights = listOf(WeightLog(1, 20_000, 94.6)),
        bp = listOf(BpLog(1, 1_780_000_000_000, 128, 82, 70)),
    )

    @Test fun roundTripPlain() {
        val text = BackupCodec.encode(data, null)
        assertFalse(BackupCodec.isEncrypted(text))
        assertEquals(data, BackupCodec.decode(text, null))
    }

    @Test fun roundTripEncrypted() {
        val text = BackupCodec.encode(data, "секрет-123".toCharArray())
        assertTrue(BackupCodec.isEncrypted(text))
        assertFalse(text.contains("94.6"))
        assertEquals(data, BackupCodec.decode(text, "секрет-123".toCharArray()))
    }

    @Test(expected = BackupException::class)
    fun wrongPassword() {
        BackupCodec.decode(BackupCodec.encode(data, "a".toCharArray()), "b".toCharArray())
    }

    @Test(expected = BackupException::class)
    fun rejectsForeignJson() {
        BackupCodec.decode("""{"hello":"world"}""", null)
    }

    @Test fun rejectsOutOfRange() {
        val bad = data.copy(weights = listOf(WeightLog(1, 20_000, 1000.0)))
        try {
            BackupCodec.decode(BackupCodec.encode(bad, null), null); throw AssertionError("должно упасть")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("вес"))
        }
    }

    @Test(expected = BackupException::class)
    fun rejectsDanglingReference() {
        BackupCodec.decode(BackupCodec.encode(data.copy(stock = listOf(StockItem(1, 9999, 1.0, 1))), null), null)
    }

    @Test(expected = BackupException::class)
    fun rejectsNewerSchema() {
        val text = BackupCodec.encode(data, null).replace("\"schemaVersion\":2", "\"schemaVersion\":99")
        BackupCodec.decode(text, null)
    }

    @Test fun apiKeyIsNotPartOfSchema() {
        val text = BackupCodec.encode(data, null).lowercase()
        assertFalse(text.contains("apikey") || text.contains("api_key"))
    }
}
