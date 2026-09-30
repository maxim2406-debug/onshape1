package com.ration.app.data.seed

import com.ration.app.data.db.entity.Block
import com.ration.app.data.db.entity.BlockIngredient
import com.ration.app.data.db.entity.PrepInput
import com.ration.app.data.db.entity.PrepOutput
import com.ration.app.data.db.entity.PrepTemplate
import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.RecipeStep
import com.ration.app.domain.cook.Categories
import com.ration.app.domain.library.FoodSearch
import com.ration.app.domain.model.CookState
import com.ration.app.domain.model.FoodRole
import com.ration.app.domain.model.MealKind
import com.ration.app.domain.model.ProductSource
import com.ration.app.domain.model.MealKind.BREAKFAST
import com.ration.app.domain.model.MealKind.DINNER
import com.ration.app.domain.model.MealKind.EVENING
import com.ration.app.domain.model.MealKind.LUNCH_CARRY
import com.ration.app.domain.model.MealKind.LUNCH_STREET
import com.ration.app.domain.model.MealKind.SNACK
import com.ration.app.domain.model.MeasureUnit
import com.ration.app.domain.model.MeasureUnit.G
import com.ration.app.domain.model.MeasureUnit.ML
import com.ration.app.domain.model.MeasureUnit.PCS
import com.ration.app.domain.model.Storage
import com.ration.app.domain.model.Storage.DRY
import com.ration.app.domain.model.Storage.FREEZER
import com.ration.app.domain.model.Storage.FRIDGE

/** Ключи выходов заготовок, на которые ссылаются блоки («(з)»). */
object PrepKeys {
    const val CHICKEN = "chicken_sv"
    const val POTATO = "potato_baked"
    const val BATAT = "batat_baked"
    const val EGGS = "egg_boiled"
    const val VEG = "veg_cut"
}

/** Готовый набор данных засева с проставленными id (1..n) — вставляется в пустую базу как есть. */
data class SeedBundle(
    val products: List<Product>,
    val blocks: List<Block>,
    val ingredients: List<BlockIngredient>,
    val templates: List<PrepTemplate>,
)

/**
 * Засев по разделам 7–9 ТЗ. Ккал и белок блоков — ровно из таблиц раздела 7,
 * количества ингредиентов для списания — сырые веса из раздела 8.
 */
object SeedData {
    const val SLICE_G = 30.0
    const val TBSP_OIL_ML = 13.0
    const val TSP_OIL_ML = 5.0

    private data class P(
        val key: String, val name: String, val unit: MeasureUnit, val kcal: Double, val protein: Double,
        val aliases: List<String> = emptyList(), val storage: Storage = FRIDGE, val category: String = "",
        val gpp: Double? = null, val untracked: Boolean = false, val note: String = "",
        val tags: List<String> = emptyList(), val label: Boolean = false, val role: FoodRole? = null,
        val cooked: CookState? = null,
    )

    private val productDefs = listOf(
        P("cottage", "Коттедж 5%", G, 95.0, 11.0, listOf("קוטג'"), category = Categories.DAIRY, note = "этикетка", label = true),
        P("protein_yogurt", "Протеиновый йогурт", G, 56.0, 10.0, category = Categories.DAIRY, note = "этикетка: 200 г = 112 ккал, 20 г белка", label = true),
        P("greek_yogurt", "Греческий йогурт", G, 73.0, 10.0, listOf("יוגורט יווני"), category = Categories.DAIRY),
        P("white_cheese", "Белый сыр", G, 100.0, 9.0, listOf("גבינה לבנה"), category = Categories.DAIRY),
        P("egg", "Яйцо", PCS, 155.0, 13.0, listOf("ביצה", "ביצים"), category = Categories.DAIRY, gpp = 60.0, tags = listOf("egg")),
        P("turkey", "Индейка, нарезка", G, 105.0, 18.0, category = Categories.POULTRY, cooked = CookState.COOKED),
        P("tuna", "Тунец в воде, консервы", PCS, 116.0, 26.0, listOf("טונה"), DRY, Categories.CANNED, tags = listOf("fish", "salty")),
        P("chicken_thigh", "Куриное бёдро без кожи, сырое", G, 121.0, 20.0, category = Categories.POULTRY),
        P("salmon", "Лосось, сырой", G, 208.0, 20.0, listOf("סלמון"), FREEZER, Categories.FISH, tags = listOf("fish", "fatty_fish")),
        P("mackerel", "Скумбрия, сырая", G, 205.0, 19.0, listOf("מקרל"), FREEZER, Categories.FISH, tags = listOf("fish", "fatty_fish")),
        P("dorado", "Дорада, сырая", G, 100.0, 19.0, listOf("דניס"), FREEZER, Categories.FISH, tags = listOf("fish")),
        P("seabass", "Сибас, сырой", G, 97.0, 18.0, listOf("לברק"), FREEZER, Categories.FISH, tags = listOf("fish")),
        P("cod", "Треска или хек, сырые", G, 85.0, 18.0, storage = FREEZER, category = Categories.FISH, tags = listOf("fish")),
        P("shrimp", "Креветки, сырые очищенные", G, 85.0, 18.0, listOf("שרימפס"), FREEZER, Categories.FISH, tags = listOf("seafood")),
        P("beef", "Говяжья вырезка, сырая", G, 145.0, 21.0, aliases = listOf("פילה בקר"), storage = FREEZER, category = Categories.BEEF, tags = listOf("red_meat")),
        P("pork", "Свиная вырезка, сырая", G, 120.0, 21.0, storage = FREEZER, category = Categories.PORK_LAMB, tags = listOf("red_meat")),
        P("oats", "Овсяные хлопья", G, 370.0, 13.0, listOf("שיבולת שועל"), DRY, Categories.GRAINS),
        P("potato", "Картофель, сырой", G, 77.0, 2.0, listOf("תפוח אדמה", "תפוחי אדמה"), DRY, Categories.VEG),
        P("batat", "Батат, сырой", G, 86.0, 2.0, listOf("בטטה"), DRY, Categories.VEG),
        P("baked_potato", "Картофель и батат из заготовки, запечённые", G, 95.0, 2.0, category = Categories.PREPS, role = FoodRole.CARB, cooked = CookState.COOKED),
        P("chicken_sv", "Курица су-вид из заготовки", G, 175.0, 26.0, category = Categories.PREPS, role = FoodRole.PROTEIN, cooked = CookState.COOKED),
        P("rice", "Бурый рис из пакета, готовый", G, 115.0, 3.0, listOf("אורז מלא"), DRY, Categories.GRAINS, cooked = CookState.COOKED),
        P("pasta", "Цельнозерновая паста, сухая", G, 350.0, 13.0, listOf("פסטה מלאה"), DRY, Categories.GRAINS),
        P("bread", "Цельный хлеб", G, 250.0, 10.0, listOf("לחם מלא"), DRY, Categories.BREAD, note = "ломтик = 30 г"),
        P("rice_cakes", "Рисовые хлебцы", PCS, 380.0, 8.0, storage = DRY, category = Categories.BREAD, gpp = 10.0),
        P("hummus", "Хумус", G, 230.0, 7.0, listOf("חומוס"), category = Categories.SAUCES_OTHER, role = FoodRole.SAUCE),
        P("tahini", "Тхина", G, 600.0, 17.0, listOf("טחינה"), DRY, Categories.SAUCES_OTHER, role = FoodRole.SAUCE),
        P("olive_oil", "Оливковое масло", ML, 800.0, 0.0, listOf("שמן זית"), DRY, Categories.NUTS_OILS),
        P("almonds", "Миндаль", G, 575.0, 21.0, listOf("שקדים"), DRY, Categories.NUTS_OILS),
        P("seeds", "Семечки в скорлупе", G, 317.0, 12.0, listOf("גרעינים"), DRY, Categories.NUTS_OILS, note = "с учётом скорлупы: 60 г = 190 ккал / 7 г", role = FoodRole.SNACK),
        P("popcorn", "Попкорн, зёрна", G, 375.0, 12.0, listOf("פופקורן"), DRY, Categories.SNACKS, role = FoodRole.SNACK),
        P("dark_choc", "Тёмный шоколад 70%", G, 575.0, 8.0, listOf("שוקולד מריד", "שוקולד מריר"), DRY, Categories.SWEETS),
        P("avocado", "Авокадо", PCS, 160.0, 2.0, listOf("אבוקדו"), category = Categories.VEG, gpp = 200.0, role = FoodRole.FAT),
        P("cucumber", "Огурец", PCS, 15.0, 0.7, listOf("מלפפון", "מלפפונים"), category = Categories.VEG, gpp = 120.0),
        P("tomato", "Помидор", PCS, 18.0, 0.9, listOf("עגבנייה", "עגבניות"), category = Categories.VEG, gpp = 120.0),
        P("broccoli", "Брокколи", G, 34.0, 2.8, listOf("ברוקולי"), FREEZER, Categories.VEG),
        P("zucchini", "Кабачок", G, 17.0, 1.2, listOf("קישוא", "קישואים"), category = Categories.VEG),
        P("mushrooms", "Грибы шампиньоны", G, 22.0, 3.0, listOf("פטריות"), category = Categories.VEG),
        P("greens", "Салатная зелень", G, 15.0, 1.4, category = Categories.VEG),
        P("onion", "Лук", PCS, 40.0, 1.1, storage = DRY, category = Categories.VEG, gpp = 110.0),
        P("radish", "Редис", G, 16.0, 0.7, category = Categories.VEG),
        P("crushed_tomatoes", "Протёртые помидоры из банки", G, 30.0, 1.5, storage = DRY, category = Categories.CANNED, role = FoodRole.SAUCE),
        P("berries", "Ягоды (смесь, мороженые)", G, 45.0, 1.0, storage = FREEZER, category = Categories.FRUIT),
        P("apple", "Яблоко", PCS, 52.0, 0.3, listOf("תפוח", "תפוחים"), category = Categories.FRUIT, gpp = 180.0),
        P("pear", "Груша", PCS, 57.0, 0.4, category = Categories.FRUIT, gpp = 180.0),
        P("banana", "Банан", PCS, 89.0, 1.1, listOf("בננה", "בננות"), category = Categories.FRUIT, gpp = 120.0),
        // Лимоны отслеживаются (раздел 4), вес штуки — ориентир.
        P("lemon", "Лимон", PCS, 29.0, 1.1, listOf("לימון"), category = Categories.FRUIT, gpp = 100.0, role = FoodRole.FRUIT),
        // Приправы и мелочи — не отслеживаются (раздел 9.1).
        P("paprika", "Паприка", G, 282.0, 14.0, storage = DRY, category = Categories.SPICES, untracked = true),
        P("garlic_powder", "Чесночный порошок", G, 331.0, 17.0, storage = DRY, category = Categories.SPICES, untracked = true),
        P("black_pepper", "Чёрный перец", G, 251.0, 10.0, storage = DRY, category = Categories.SPICES, untracked = true),
        P("garlic", "Чеснок", G, 149.0, 6.4, aliases = listOf("שום"), storage = DRY, category = Categories.SPICES, untracked = true),
        P("herbs", "Зелень пряная", G, 36.0, 3.0, category = Categories.SPICES, untracked = true),
        P("cinnamon", "Корица", G, 247.0, 4.0, storage = DRY, category = Categories.SPICES, untracked = true),
        P("cumin", "Зира", G, 375.0, 18.0, storage = DRY, category = Categories.SPICES, untracked = true),
        P("oregano", "Орегано", G, 265.0, 9.0, storage = DRY, category = Categories.SPICES, untracked = true),
        P("chili", "Чили", G, 282.0, 13.0, storage = DRY, category = Categories.SPICES, untracked = true),
    )

    // ---- описание ингредиентов блока ----
    private sealed interface I { val label: String }
    private data class Raw(override val label: String, val key: String, val qty: Double, val unit: MeasureUnit, val alts: List<String> = emptyList()) : I
    private data class Pr(override val label: String, val prepKey: String, val qty: Double, val unit: MeasureUnit) : I
    private data class Taste(override val label: String, val key: String? = null) : I

    private data class B(
        val code: String, val kind: MealKind, val name: String, val kcal: Double, val protein: Double,
        val tags: List<String>, val composition: String, val ingredients: List<I> = emptyList(),
        val minutes: Int = 0, val deduct: Boolean = true, val steps: List<RecipeStep> = emptyList(),
    )

    private fun s(text: String, min: Int? = null) = RecipeStep(text, min?.let { it * 60 })

    private val slice1 = Raw("цельный хлеб 1 ломтик", "bread", SLICE_G, G)
    private val slice2 = Raw("цельный хлеб 2 ломтика", "bread", 2 * SLICE_G, G)
    private val tspOil = Raw("оливковое масло 1 ч. л.", "olive_oil", TSP_OIL_ML, ML)
    private val tbspOil = Raw("оливковое масло 1 ст. л.", "olive_oil", TBSP_OIL_ML, ML)
    private val eggs2 = Pr("яйцо вкрутую (з) 2 шт", PrepKeys.EGGS, 2.0, PCS)

    private val blockDefs = listOf(
        // 7.1 Завтраки
        B("З1", BREAKFAST, "Сэндвич с тунцом + протеиновый йогурт + яблоко", 490.0, 40.0, listOf("shop", "fish"),
            "сэндвич с тунцом на цельном хлебе без майонеза 1 шт; протеиновый йогурт 200 г; яблоко 1 шт",
            listOf(Taste("сэндвич с тунцом на цельном хлебе без майонеза 1 шт"),
                Raw("протеиновый йогурт 200 г", "protein_yogurt", 200.0, G),
                Raw("яблоко 1 шт", "apple", 1.0, PCS))),
        B("З2", BREAKFAST, "Коттедж + яйца + овощ + хлеб", 510.0, 43.0, listOf("carry", "shop", "egg"),
            "коттедж 5% 250 г; яйцо вкрутую (з) 2 шт; огурец или помидор; цельный хлеб 1 ломтик",
            listOf(Raw("коттедж 5% 250 г", "cottage", 250.0, G), eggs2, Taste("огурец или помидор", "cucumber"), slice1),
            minutes = 3),
        B("З3", BREAKFAST, "Сэндвич с белым сыром и индейкой + греческий йогурт", 500.0, 43.0, listOf("shop"),
            "сэндвич с белым сыром и индейкой на цельном хлебе 1 шт; греческий йогурт 200 г",
            listOf(Taste("сэндвич с белым сыром и индейкой на цельном хлебе 1 шт"),
                Raw("греческий йогурт 200 г", "greek_yogurt", 200.0, G))),
        B("З4", BREAKFAST, "Омлет с овощами", 530.0, 41.0, listOf("home", "egg"),
            "яйцо 3 шт; овощи; индейка 50 г; белый сыр 100 г; цельный хлеб 1 ломтик",
            listOf(Raw("яйцо 3 шт", "egg", 3.0, PCS), Taste("овощи: помидор, шпинат или зелень", "tomato"),
                Raw("индейка 50 г", "turkey", 50.0, G), Raw("белый сыр 100 г", "white_cheese", 100.0, G), slice1, tspOil),
            minutes = 8,
            steps = listOf(
                s("3 яйца взбить с 1 ст. л. воды."),
                s("На сковороде 1 ч. л. масла, помидор кубиками и горсть шпината или зелени.", 2),
                s("Влить яйца, накрыть крышкой, слабый огонь.", 4),
                s("Сверху 50 г нарезки индейки. Подавать со 100 г белого сыра и ломтиком цельного хлеба."))),
        B("З5", BREAKFAST, "Овсянка на ночь", 550.0, 41.0, listOf("carry", "egg", "prep_night"),
            "овсяные хлопья 40 г; протеиновый йогурт 200 г; ягоды 100 г; миндаль 15 г; яйцо вкрутую (з) 2 шт",
            listOf(Raw("овсяные хлопья 40 г", "oats", 40.0, G), Raw("протеиновый йогурт 200 г", "protein_yogurt", 200.0, G),
                Raw("ягоды 100 г", "berries", 100.0, G), Raw("миндаль 15 г", "almonds", 15.0, G), eggs2,
                Taste("корица, щепотка", "cinnamon")),
            minutes = 5,
            steps = listOf(
                s("Вечером: в банку 40 г овсянки, 200 г протеинового йогурта, щепотка корицы, перемешать."),
                s("Сверху 100 г ягод, можно замороженных. Закрыть, убрать в холодильник на ночь."),
                s("С собой взять 15 г миндаля и 2 яйца вкрутую."))),

        // 7.2 Обеды с собой
        B("С1", LUNCH_CARRY, "Курица + картофель + овощи", 620.0, 43.0, listOf("carry", "prep"),
            "курица су-вид (з) 150 г; картофель (з) 250 г; огурцы и помидоры; оливковое масло 1 ст. л.",
            listOf(Pr("курица су-вид (з) 150 г", PrepKeys.CHICKEN, 150.0, G), Pr("картофель (з) 250 г", PrepKeys.POTATO, 250.0, G),
                Taste("огурцы и помидоры", "cucumber"), tbspOil, Raw("сок ¼ лимона", "lemon", 0.25, PCS)),
            minutes = 5,
            steps = listOf(
                s("Курица из заготовки 150 г, картофель 250 г в контейнер."),
                s("Огурец и помидор нарезать. Заправка: 1 ст. л. оливкового масла и сок ¼ лимона."),
                s("Разогреть на работе или есть холодным.", 2))),
        B("С2", LUNCH_CARRY, "Курица + рис + авокадо", 600.0, 44.0, listOf("carry", "prep"),
            "курица су-вид (з) 150 г; бурый рис из пакета 150 г; овощи; авокадо ½ шт",
            listOf(Pr("курица су-вид (з) 150 г", PrepKeys.CHICKEN, 150.0, G), Raw("бурый рис из пакета 150 г", "rice", 150.0, G),
                Taste("овощи"), Raw("авокадо ½ шт", "avocado", 0.5, PCS)),
            minutes = 5,
            steps = listOf(
                s("Курица из заготовки 150 г, рис из пакета 150 г, овощи и ½ авокадо в контейнер."),
                s("Разогреть на работе или есть холодным.", 2))),
        B("С3", LUNCH_CARRY, "Бокс без готовки", 620.0, 43.0, listOf("carry", "egg"),
            "индейка нарезка 100 г; яйцо вкрутую (з) 2 шт; хумус 50 г; овощи; цельный хлеб 2 ломтика",
            listOf(Raw("индейка нарезка 100 г", "turkey", 100.0, G), eggs2, Raw("хумус 50 г", "hummus", 50.0, G),
                Taste("огурец, помидор", "cucumber"), slice2),
            minutes = 5,
            steps = listOf(s("Нарезка индейки 100 г, 2 яйца вкрутую, хумус 50 г, огурец, помидор, 2 ломтика цельного хлеба. Ничего не греть."))),
        B("С4", LUNCH_CARRY, "Салат с тунцом", 650.0, 46.0, listOf("carry", "fish", "egg"),
            "тунец в воде 1 банка; яйцо вкрутую (з) 2 шт; овощи и зелень; оливковое масло 1 ст. л.; цельный хлеб 2 ломтика",
            listOf(Raw("тунец в воде 1 банка", "tuna", 1.0, PCS), eggs2, Taste("огурец, помидор, зелень", "cucumber"),
                tbspOil, Raw("сок ½ лимона", "lemon", 0.5, PCS), Taste("чеснок 1 зубчик", "garlic"), slice2),
            minutes = 5,
            steps = listOf(
                s("Огурец, помидор и зелень нарезать в контейнер."),
                s("Сверху банка тунца в воде (слить) и 2 яйца вкрутую дольками."),
                s("Заправка в баночке: 1 ст. л. оливкового масла, сок ½ лимона, зубчик чеснока. 2 ломтика хлеба отдельно."))),

        // 7.3 Обеды на улице — не списываются
        B("О1", LUNCH_STREET, "Большой салат с тунцом, соус отдельно, 2 ломтика цельного хлеба", 600.0, 42.0,
            listOf("street", "fish"), "заказ вне дома", deduct = false),
        B("О2", LUNCH_STREET, "Салат с курицей или мясом гриль + рис или картофель без масла", 580.0, 44.0,
            listOf("street", "ask_meat"), "заказ вне дома", deduct = false),
        B("О3", LUNCH_STREET, "Шаурма на тарелке из индейки или курицы, салат, хумус, ложка тхины, без картошки фри", 600.0, 45.0,
            listOf("street"), "заказ вне дома", deduct = false),
        B("О4", LUNCH_STREET, "Рыба на гриле (дорада, сибас, лосось) + картофель + салат", 600.0, 42.0,
            listOf("street", "fish"), "заказ вне дома", deduct = false),
        B("О5", LUNCH_STREET, "Лаваш или сэндвич с индейкой, курицей или говядиной и овощами, без соусов", 550.0, 35.0,
            listOf("street", "ask_meat"), "заказ вне дома", deduct = false),
        B("F", LUNCH_STREET, "Свободный обед: шаурма в лафе или бургер без картошки и без сладкой газировки", 750.0, 40.0,
            listOf("street", "free_lunch"), "заказ вне дома", deduct = false),

        // 7.4 Перекусы
        B("П1", SNACK, "Греческий йогурт + ягоды", 205.0, 19.0, listOf("carry"),
            "греческий йогурт 200 г; ягоды 100 г",
            listOf(Raw("греческий йогурт 200 г", "greek_yogurt", 200.0, G), Raw("ягоды 100 г", "berries", 100.0, G))),
        B("П2", SNACK, "Протеиновый йогурт + банан", 210.0, 21.0, listOf("carry"),
            "протеиновый йогурт 200 г; банан 1 шт",
            listOf(Raw("протеиновый йогурт 200 г", "protein_yogurt", 200.0, G), Raw("банан 1 шт", "banana", 1.0, PCS))),
        B("П3", SNACK, "Яблоко + миндаль", 200.0, 4.0, listOf("carry", "light"),
            "яблоко 1 шт; миндаль 20 г",
            listOf(Raw("яблоко 1 шт", "apple", 1.0, PCS), Raw("миндаль 20 г", "almonds", 20.0, G))),
        B("П4", SNACK, "Яйца вкрутую + огурец", 160.0, 12.0, listOf("carry", "egg"),
            "яйцо вкрутую (з) 2 шт; огурец", listOf(eggs2, Taste("огурец", "cucumber"))),
        B("П5", SNACK, "Коттедж + помидор", 160.0, 17.0, listOf("carry"),
            "коттедж 5% 150 г; помидор", listOf(Raw("коттедж 5% 150 г", "cottage", 150.0, G), Taste("помидор", "tomato"))),
        B("П6", SNACK, "Протеиновый батончик (от 15 г белка, до 220 ккал, сахара до 8 г)", 200.0, 17.0,
            listOf("bar", "protein_bar"), "вводится пользователем по формату 5.2", deduct = false),
        B("П7", SNACK, "Злаковый батончик из машины", 100.0, 2.0, listOf("bar", "cereal_bar", "light"),
            "вводится пользователем", deduct = false),

        // 7.5 Ужины (сырой вес для списания — раздел 8)
        B("У1", DINNER, "Лосось + батат + брокколи", 550.0, 36.0, listOf("home", "fish", "fatty_fish", "prep"),
            "лосось 130 г; батат (з) 200 г; брокколи",
            listOf(Raw("лосось 180 г сырого", "salmon", 180.0, G), Pr("батат (з) 200 г", PrepKeys.BATAT, 200.0, G),
                Raw("брокколи 100 г", "broccoli", 100.0, G), tspOil, Raw("сок ½ лимона", "lemon", 0.5, PCS),
                Taste("паприка, чесночный порошок", "paprika")),
            minutes = 20,
            steps = listOf(
                s("Филе 180 г сырого, 1 ч. л. масла, сок ½ лимона, паприка, чесночный порошок."),
                s("Air Fry 190 °C из морозилки 12–14 минут (охлаждённое 9–11) до 63 °C внутри.", 13),
                s("Брокколи 100 г с каплей масла положить рядом на последние 8 минут.", 8),
                s("Батат 200 г из заготовки разогреть.", 2))),
        B("У2", DINNER, "Курица + картофель + салат", 520.0, 43.0, listOf("home", "prep"),
            "курица су-вид (з) 150 г; картофель (з) 250 г; салат",
            listOf(Pr("курица су-вид (з) 150 г", PrepKeys.CHICKEN, 150.0, G), Pr("картофель (з) 250 г", PrepKeys.POTATO, 250.0, G),
                Taste("салат: огурец, помидор, зелень", "cucumber"), tspOil),
            minutes = 5,
            steps = listOf(
                s("Курицу 150 г и картофель 250 г из заготовки разогреть вместе: 2 минуты в микроволновке или 4 минуты в Ниндзя, 180 °C.", 4),
                s("Салат: огурец, помидор, зелень, 1 ч. л. масла, лимон."))),
        B("У3", DINNER, "Креветки + рис + кабачки + авокадо", 600.0, 42.0, listOf("home", "seafood"),
            "креветки 150 г; бурый рис из пакета 150 г; кабачки; авокадо ½ шт",
            listOf(Raw("креветки 200 г сырых", "shrimp", 200.0, G), Raw("бурый рис из пакета 150 г", "rice", 150.0, G),
                Raw("кабачок 100 г", "zucchini", 100.0, G), Raw("авокадо ½ шт", "avocado", 0.5, PCS), tspOil),
            minutes = 15,
            steps = listOf(
                s("Кабачок 100 г полукольцами, 1 ч. л. масла, чесночный порошок. Air Fry 200 °C.", 5),
                s("Добавить 200 г сырых очищенных креветок, паприка, сок лимона, ещё 7 минут, встряхнуть на середине.", 7),
                s("Рис из пакета 150 г разогреть 90 секунд, подать с ½ авокадо."))),
        B("У4", DINNER, "Говяжья вырезка + картофель + овощи", 540.0, 38.0, listOf("home", "red_meat", "prep"),
            "говяжья вырезка 120 г; картофель (з) 250 г; овощи",
            listOf(Raw("говяжья вырезка 160 г сырой", "beef", 160.0, G), Raw("грибы 100 г", "mushrooms", 100.0, G),
                Pr("картофель (з) 250 г", PrepKeys.POTATO, 250.0, G), tspOil),
            minutes = 20,
            steps = listOf(
                s("Стейк 160 г толщиной 3 см достать за 15 минут, обсушить, перец, 1 ч. л. масла.", 15),
                s("Air Fry 200 °C, 10–12 минут, перевернуть на середине. 57–60 °C внутри для средней прожарки.", 11),
                s("Грибы 100 г положить рядом на последние 6 минут."),
                s("Отдохнуть 5 минут под фольгой. Картофель 250 г из заготовки разогреть.", 5))),
        B("У5", DINNER, "Дорада или сибас + картофель + овощи", 500.0, 41.0, listOf("home", "fish", "prep"),
            "дорада или сибас 150 г; картофель (з) 250 г; овощи",
            listOf(Raw("дорада или сибас 200 г сырых", "dorado", 200.0, G, alts = listOf("seabass")),
                Pr("картофель (з) 250 г", PrepKeys.POTATO, 250.0, G), Taste("огурец, помидор, зелень", "cucumber"), tspOil),
            minutes = 15,
            steps = listOf(
                s("2 филе (200 г сырых), сок лимона, чесночный порошок, паприка, 1 ч. л. масла."),
                s("Air Fry 200 °C, кожей вверх, 8–10 минут (из морозилки 12–14) до 63 °C.", 9),
                s("Подать с картофелем 250 г из заготовки и салатом из огурца, помидора и зелени с лимоном."))),
        B("У6", DINNER, "Треска или хек + паста + томатный соус", 580.0, 46.0, listOf("home", "fish"),
            "треска или хек 150 г; цельнозерновая паста 150 г; томатный соус из банки",
            listOf(Raw("треска или хек 200 г сырых", "cod", 200.0, G), Raw("паста сухая 60 г", "pasta", 60.0, G),
                Raw("протёртые помидоры 150 г", "crushed_tomatoes", 150.0, G), tspOil,
                Taste("чеснок 2 зубчика", "garlic"), Taste("орегано", "oregano")),
            minutes = 20,
            steps = listOf(
                s("Филе 200 г, сок лимона, паприка, 1 ч. л. масла. Air Fry 200 °C, 10–12 минут (из морозилки 12–14) до 63 °C.", 11),
                s("Пока готовится рыба, сварить 60 г сухой цельнозерновой пасты.", 10),
                s("Соус: 150 г протёртых помидоров, 2 зубчика чеснока, орегано. Смешать с пастой, сверху рыба.", 5))),
        B("У7", DINNER, "Свиная вырезка + батат + салат", 470.0, 37.0, listOf("home", "red_meat"),
            "свиная вырезка 130 г; батат 200 г (запекается рядом в Ниндзя); салат",
            listOf(Raw("свиная вырезка 180 г сырой", "pork", 180.0, G), Raw("батат 200 г сырой", "batat", 200.0, G),
                tspOil, Taste("салат", "cucumber")),
            minutes = 25,
            steps = listOf(
                s("Батат 200 г кубиками 2 см, капля масла. Air Fry 200 °C.", 12),
                s("Вырезку 180 г нарезать медальонами 2 см, паприка, чесночный порошок, 1 ч. л. масла. Добавить к батату, ещё 10–12 минут до 63 °C, перемешать на середине.", 11),
                s("Отдохнуть 3 минуты, подать с салатом.", 3))),
        B("У9", DINNER, "Шакшука + сыр + хлеб", 620.0, 35.0, listOf("home", "egg"),
            "яйцо 3 шт; помидоры; лук; белый сыр 100 г; цельный хлеб 2 ломтика (сковорода, 15 минут)",
            listOf(Raw("яйцо 3 шт", "egg", 3.0, PCS), Raw("помидоры 300 г", "tomato", 300.0, G),
                Raw("лук ½ шт", "onion", 0.5, PCS), Taste("чеснок 2 зубчика", "garlic"),
                Raw("белый сыр 100 г", "white_cheese", 100.0, G), slice2, tspOil,
                Taste("паприка, зира, чили", "paprika")),
            minutes = 15,
            steps = listOf(
                s("На сковороде 1 ч. л. масла, ½ луковицы кубиками и 2 зубчика чеснока.", 3),
                s("Добавить 300 г помидоров кубиками (или полбанки протёртых), 1 ч. л. паприки, ½ ч. л. зиры, щепотку чили. Тушить.", 8),
                s("Сделать 3 углубления, разбить яйца, накрыть крышкой.", 6),
                s("Сверху зелень, отдельно 100 г белого сыра и 2 ломтика хлеба."))),
        B("У10", DINNER, "Скумбрия + картофель + салат", 560.0, 33.0, listOf("home", "fish", "fatty_fish", "prep"),
            "скумбрия 120 г; картофель (з) 250 г; салат",
            listOf(Raw("скумбрия 160 г сырой", "mackerel", 160.0, G), Pr("картофель (з) 250 г", PrepKeys.POTATO, 250.0, G),
                tspOil, Taste("салат", "cucumber")),
            minutes = 15,
            steps = listOf(
                s("2 филе (160 г сырых), сок лимона, чёрный перец, чесночный порошок, 1 ч. л. масла."),
                s("Air Fry 200 °C, кожей вверх, 8–10 минут (из морозилки 12–14) до 63 °C.", 9),
                s("Подать с картофелем 250 г из заготовки и салатом."))),

        // 7.6 Вечер
        B("Е1", EVENING, "Семечки в скорлупе без соли", 190.0, 7.0, listOf("home"),
            "семечки 60 г", listOf(Raw("семечки 60 г", "seeds", 60.0, G))),
        B("Е2", EVENING, "Попкорн + тёмный шоколад", 225.0, 4.0, listOf("home"),
            "попкорн, ядра 30 г, без масла; тёмный шоколад 70% 20 г",
            listOf(Raw("попкорн, зёрна 30 г", "popcorn", 30.0, G), Raw("тёмный шоколад 70% 20 г", "dark_choc", 20.0, G)),
            minutes = 5,
            steps = listOf(
                s("30 г зёрен в стеклянную миску, накрыть тарелкой."),
                s("Микроволновка на максимум, пока паузы между хлопками не станут длиннее 2 секунд.", 3),
                s("Приправить паприкой, чили и цедрой лимона. 20 г тёмного шоколада отдельно."))),
        B("Е3", EVENING, "Протеиновый пудинг", 170.0, 17.0, listOf("shop"),
            "протеиновый пудинг 1 шт", listOf(Taste("протеиновый пудинг 1 шт"))),
        B("Е4", EVENING, "Греческий йогурт + ягоды + шоколад", 250.0, 19.0, listOf("home"),
            "греческий йогурт 200 г; ягоды; тёмный шоколад 70% 10 г",
            listOf(Raw("греческий йогурт 200 г", "greek_yogurt", 200.0, G), Taste("ягоды", "berries"),
                Raw("тёмный шоколад 70% 10 г", "dark_choc", 10.0, G))),
        B("Е5", EVENING, "Овощи с хумусом и хлебцами", 220.0, 6.0, listOf("home"),
            "огурцы и редис; хумус 50 г; рисовые хлебцы 2 шт",
            listOf(Taste("огурцы и редис", "cucumber"), Raw("хумус 50 г", "hummus", 50.0, G),
                Raw("рисовые хлебцы 2 шт", "rice_cakes", 2.0, PCS))),
        B("Е6", EVENING, "Миндаль + фрукт", 230.0, 6.0, listOf("home"),
            "миндаль 25 г; яблоко или груша 1 шт",
            listOf(Raw("миндаль 25 г", "almonds", 25.0, G), Raw("яблоко или груша 1 шт", "apple", 1.0, PCS, alts = listOf("pear")))),
    )

    /** Старые названия категорий засева v1 — при обновлении меняются на новые (13.2.5). */
    val OLD_CATEGORIES = setOf("Молочное", "Яйца", "Мясо", "Рыба", "Крупы", "Овощи", "Заготовки", "Хлеб", "Прочее",
        "Масло", "Орехи", "Фрукты", "Консервы", "Приправы")

    fun keyFor(name: String): String = "ref:" + FoodSearch.normalize(name)

    private fun cookedOf(name: String): CookState? {
        val n = name.lowercase()
        return when {
            n.contains("сыр") && (n.contains("сырой") || n.contains("сырая") || n.contains("сырое") || n.contains("сырые")) -> CookState.RAW
            n.contains("сух") -> CookState.RAW
            n.contains("варён") || n.contains("на гриле") || n.contains("жарен") || n.contains("готов") || n.contains("запечён") -> CookState.COOKED
            else -> null
        }
    }

    /** Разбор таблицы SeedCatalog в продукты (без id). */
    fun catalogProducts(): List<Product> {
        var category = ""
        val out = mutableListOf<Product>()
        for (raw in SeedCatalog.TABLE.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("## ")) { category = line.removePrefix("## ").trim(); continue }
            val f = line.split("|").map { it.trim() }
            val name = f[0]
            val unit = when (f.getOrNull(3)) { "мл" -> ML; "шт" -> PCS; else -> G }
            val tags = f.getOrNull(5).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val role = f.getOrNull(7)?.takeIf { it.isNotEmpty() }?.let { r -> FoodRole.entries.first { it.name.equals(r, true) } }
            out += Product(
                key = keyFor(name), name = name, category = category, unit = unit,
                kcalPer100 = f[1].toDouble(), proteinPer100 = f[2].toDouble(),
                edibleFraction = f.getOrNull(4)?.toDoubleOrNull() ?: 1.0,
                tags = tags.filter { it != "untracked" }, untracked = "untracked" in tags,
                aliases = f.getOrNull(6).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                role = role, cooked = cookedOf(name), source = ProductSource.REFERENCE,
                storage = when (category) {
                    Categories.GRAINS, Categories.NUTS_OILS, Categories.CANNED, Categories.SWEETS, Categories.SNACKS, Categories.BREAD -> DRY
                    else -> FRIDGE
                },
            ).withSearchKey()
        }
        return out
    }

    fun Product.withSearchKey(): Product = copy(searchKey = FoodSearch.buildKey(name, aliases, category, tags))

    fun build(): SeedBundle {
        val base = productDefs.mapIndexed { i, p ->
            Product(
                id = i + 1L, key = p.key, name = p.name, category = p.category, unit = p.unit,
                kcalPer100 = p.kcal, proteinPer100 = p.protein, gramsPerPiece = p.gpp, storage = p.storage,
                aliases = p.aliases, untracked = p.untracked, note = p.note, tags = p.tags,
                source = if (p.label) ProductSource.LABEL else ProductSource.REFERENCE,
                role = p.role, cooked = p.cooked ?: cookedOf(p.name),
            ).withSearchKey()
        }
        val products = base + catalogProducts().mapIndexed { i, p -> p.copy(id = base.size + i + 1L) }
        val byKey = products.associateBy { it.key!! }
        fun pid(key: String) = byKey[key]?.id ?: error("seed: unknown product $key")

        val blocks = mutableListOf<Block>()
        val ingredients = mutableListOf<BlockIngredient>()
        var ingId = 1L
        blockDefs.forEachIndexed { i, b ->
            val blockId = i + 1L
            blocks += Block(
                id = blockId, code = b.code, kind = b.kind, name = b.name, kcal = b.kcal, protein = b.protein,
                prepMinutes = b.minutes, tags = b.tags, recipeSteps = b.steps, deductStock = b.deduct,
                composition = b.composition,
            )
            b.ingredients.forEach { ing ->
                ingredients += when (ing) {
                    is Raw -> BlockIngredient(ingId++, blockId, ing.label, pid(ing.key), ing.alts.map(::pid), null, ing.qty, ing.unit)
                    is Pr -> BlockIngredient(ingId++, blockId, ing.label, null, emptyList(), ing.prepKey, ing.qty, ing.unit)
                    is Taste -> BlockIngredient(ingId++, blockId, ing.label, ing.key?.let(::pid), toTaste = true)
                }
            }
        }

        val templates = listOf(
            PrepTemplate(
                id = 1, key = "chicken_sv", name = "Курица су-вид",
                inputs = listOf(PrepInput(pid("chicken_thigh"), 800.0)),
                outputs = listOf(PrepOutput(PrepKeys.CHICKEN, "Курица су-вид", pid("chicken_sv"), 150.0, 4.0)),
                shelfDays = 4, inSaturdayBatch = true,
                steps = listOf(
                    s("Бёдра без кожи и кости, 800 г сырых, обсушить бумажным полотенцем."),
                    s("Приправить: 2 ч. л. паприки, 2 ч. л. чесночного порошка, чёрный перец. Разложить по 2 пакета, выдавить воздух."),
                    s("Су-вид 74 °C, 2–3 часа.", 150),
                    s("Охладить пакеты в ледяной воде, убрать в холодильник. Хранить до 4 суток.", 15),
                    s("Перед едой разогреть 2 минуты в микроволновке или съесть холодной. Для корочки 3 минуты в Ниндзя, 200 °C."))),
            PrepTemplate(
                id = 2, key = "potato_batat", name = "Картофель и батат",
                inputs = listOf(PrepInput(pid("potato"), 1000.0), PrepInput(pid("batat"), 400.0), PrepInput(pid("olive_oil"), TBSP_OIL_ML)),
                outputs = listOf(
                    PrepOutput(PrepKeys.POTATO, "Картофель запечённый", pid("baked_potato"), 250.0, 3.0, userEntersYield = true),
                    PrepOutput(PrepKeys.BATAT, "Батат запечённый", pid("baked_potato"), 200.0, 1.5, userEntersYield = true),
                ),
                shelfDays = 4, inSaturdayBatch = true,
                steps = listOf(
                    s("Картофель 1 кг и батат 400 г вымыть, нарезать кубиками 2 см, обсушить."),
                    s("Смешать с 1 ст. л. оливкового масла, 1 ч. л. паприки и 1 ч. л. чесночного порошка."),
                    s("Ниндзя, Air Fry 200 °C, первая загрузка ≈700 г, перемешать на середине.", 22),
                    s("Вторая загрузка ≈700 г. Готово, когда вилка входит легко.", 22),
                    s("Остудить, разложить по контейнерам, хранить до 4 суток. Разогрев: 2 минуты в микроволновке или 4 минуты в Ниндзя."))),
            PrepTemplate(
                id = 3, key = "eggs_boiled", name = "Яйца вкрутую",
                inputs = listOf(PrepInput(pid("egg"), 10.0)),
                outputs = listOf(PrepOutput(PrepKeys.EGGS, "Яйца вкрутую (варёные)", pid("egg"), null, 10.0)),
                shelfDays = 5, inSaturdayBatch = true,
                steps = listOf(
                    s("Опустить яйца в кипящую воду.", 10),
                    s("Переложить в ледяную воду.", 5),
                    s("Хранить в скорлупе 5–6 суток, чистить перед едой."))),
            PrepTemplate(
                id = 4, key = "veg_cut", name = "Нарезка овощей",
                inputs = listOf(
                    PrepInput(pid("cucumber"), 2.0, variable = true),
                    PrepInput(pid("tomato"), 2.0, variable = true),
                    PrepInput(pid("greens"), 50.0, variable = true),
                ),
                outputs = listOf(PrepOutput(PrepKeys.VEG, "Нарезка овощей", null, 100.0, 5.0, userEntersYield = true)),
                shelfDays = 3,
                steps = listOf(s("Огурцы, помидоры и зелень вымыть, нарезать, разложить по контейнерам."))),
        )
        return SeedBundle(products, blocks, ingredients, templates)
    }
}
