package com.ration.app

import com.ration.app.domain.importing.ImportParser
import com.ration.app.domain.importing.MatchKind
import com.ration.app.domain.importing.ProductMatcher
import com.ration.app.domain.importing.Prompts
import com.ration.app.domain.model.MeasureUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {
    @Test fun separators() {
        val r = ImportParser.parsePurchases("Яйцо | 10 | шт | 12.90\nХумус; 400; г; 9\nМиндаль\t200\tг\t25")
        assertEquals(3, r.lines.size)
        assertEquals(10.0, r.lines[0].qty, 0.0)
        assertEquals(MeasureUnit.PCS, r.lines[0].unit)
        assertEquals(12.9, r.lines[0].price!!, 1e-9)
        assertEquals(400.0, r.lines[1].qty, 0.0)
        assertEquals(MeasureUnit.G, r.lines[2].unit)
        assertEquals(25.0, r.lines[2].price!!, 0.0)
    }

    @Test fun decimalCommaAndKg() {
        val l = ImportParser.parsePurchases("Картофель, сырой | 1,5 | кг | 7,90").lines.single()
        assertEquals("Картофель, сырой", l.name)
        assertEquals(1500.0, l.qty, 1e-9)
        assertEquals(MeasureUnit.G, l.unit)
        assertEquals(7.9, l.price!!, 1e-9)
    }

    @Test fun joinedNumberAndUnit() {
        val a = ImportParser.parsePurchases("Батат | 1.5кг | 12").lines.single()
        assertEquals(1500.0, a.qty, 1e-9)
        assertEquals(MeasureUnit.G, a.unit)
        assertEquals(12.0, a.price!!, 0.0)
        val b = ImportParser.parsePurchases("Картофель 1.5кг").lines.single()
        assertEquals("Картофель", b.name)
        assertEquals(1500.0, b.qty, 1e-9)
    }

    @Test fun litersToMl() {
        val l = ImportParser.parsePurchases("Оливковое масло | 0,75 | л").lines.single()
        assertEquals(750.0, l.qty, 1e-9)
        assertEquals(MeasureUnit.ML, l.unit)
        assertNull(l.price)
    }

    @Test fun commentsAndBlankLines() {
        val r = ImportParser.parsePurchases("# נייר טואלט ???\n\n   \nЯйцо | 12 | шт\n#שקית")
        assertEquals(1, r.lines.size)
        assertEquals(2, r.comments.size)
        assertTrue(r.errors.isEmpty())
    }

    @Test fun errors() {
        val r = ImportParser.parsePurchases("Яйцо | много | шт\nСыр | 200 | фунт\n | 1 | шт\nХлеб | -1 | шт")
        assertTrue(r.lines.isEmpty())
        assertEquals(4, r.errors.size)
    }

    @Test fun missingUnitMeansPieces() {
        val l = ImportParser.parsePurchases("Авокадо | 3").lines.single()
        assertEquals(MeasureUnit.PCS, l.unit)
        assertEquals(3.0, l.qty, 0.0)
    }

    @Test fun label() {
        val full = ImportParser.parseLabel("Батончик X | 400 | 30 | 50 | 200 | 15")!!
        assertEquals(400.0, full.kcalPer100, 0.0)
        assertEquals(30.0, full.proteinPer100, 0.0)
        assertEquals(50.0, full.portionGrams!!, 0.0)
        val portionOnly = ImportParser.parseLabel("Батончик Y |  |  | 50 | 200 | 17")!!
        assertEquals(400.0, portionOnly.kcalPer100, 0.0)
        assertEquals(34.0, portionOnly.proteinPer100, 0.0)
        val per100Only = ImportParser.parseLabel("Йогурт | 56.5 | 10")!!
        assertNull(per100Only.portionGrams)
        assertEquals(56.5, per100Only.kcalPer100, 0.0)
        assertNull(ImportParser.parseLabel("Без данных | | "))
        assertNull(ImportParser.parseLabel(""))
    }

    @Test fun matcher() {
        val catalog = TestData.seed.products
        assertEquals(MatchKind.EXACT, ProductMatcher.match("яйцо", catalog).kind)
        val alias = ProductMatcher.match("קוטג'", catalog)
        assertEquals(MatchKind.ALIAS, alias.kind)
        assertEquals("cottage", alias.product!!.key)
        val fuzzy = ProductMatcher.match("Греческий йогурт 0%", catalog)
        assertNotNull(fuzzy.product)
        assertEquals("greek_yogurt", fuzzy.product!!.key)
        assertEquals(MatchKind.NONE, ProductMatcher.match("Туалетная бумага", catalog).kind)
    }

    @Test fun receiptPromptListsCatalog() {
        val p = Prompts.receipt(TestData.seed.products)
        assertFalse(p.contains(Prompts.CATALOG_PLACEHOLDER))
        assertTrue(p.contains("\nКоттедж 5%\n"))
        assertTrue(p.contains("название | количество | единица | цена"))
        assertFalse(p.contains("Паприка"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sizeLimit() {
        ImportParser.parsePurchases("x".repeat(1_000_001))
    }
}

class CsvImportTest {
    @org.junit.Test fun csv() {
        val t = ImportParser.csvToPipes("\"Коттедж 5%\",250,г,6.9\nХумус,400,г,\n# коммент\nМасло | 1 | л")
        val r = ImportParser.parsePurchases(t)
        org.junit.Assert.assertEquals(3, r.lines.size)
        org.junit.Assert.assertEquals("Коттедж 5%", r.lines[0].name)
        org.junit.Assert.assertEquals(6.9, r.lines[0].price!!, 1e-9)
        org.junit.Assert.assertEquals(1000.0, r.lines[2].qty, 0.0)
    }
}
