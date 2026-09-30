package com.ration.app.domain.cook

import com.ration.app.data.db.entity.Product
import com.ration.app.data.db.entity.Recipe
import com.ration.app.data.db.entity.RecipeIngredient
import com.ration.app.domain.model.CookMethod
import com.ration.app.domain.model.FoodRole
import com.ration.app.domain.model.Tags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DefaultMethod(val method: CookMethod, val steps: List<String>)

@Serializable
data class CookbookFile(
    val version: Int,
    val defaults: Map<String, DefaultMethod> = emptyMap(),
    val recipes: List<Recipe> = emptyList(),
)

object Cookbook {
    private val json = Json { ignoreUnknownKeys = true }
    val ALLOWED_TAGS = setOf(Tags.FISH, Tags.FATTY_FISH, Tags.RED_MEAT, Tags.EGG, Tags.SEAFOOD, Tags.PROCESSED, Tags.SALTY, Tags.PREP, Tags.HOME)
    private val SLOTS = setOf("З", "С", "О", "У", "Е", "П")

    fun parse(text: String): CookbookFile = json.decodeFromString(CookbookFile.serializer(), text)

    /** Ссылка «роль:группа», «роль» или «product:ключ». */
    fun matches(ref: String, p: Product): Boolean {
        if (ref.startsWith("product:")) return p.key == ref.removePrefix("product:")
        val role = ref.substringBefore(':')
        val group = ref.substringAfter(':', "any")
        val r = FoodRules.role(p)
        if (!r.name.equals(role, ignoreCase = true)) {
            // яйца и мягкий молочный белок годятся как «protein» на завтрак только по явной группе
            if (!(role == "protein" && group == "egg" && "egg" in FoodRules.groups(p))) return false
        }
        return group == "any" || group in FoodRules.groups(p)
    }

    /** Валидация рецепта (18.8, тесты): роли существуют в справочнике, activeMin ≤ totalMin, 3–6 шагов, без salty. */
    fun validate(r: Recipe, catalog: List<Product>): List<String> {
        val errors = mutableListOf<String>()
        if (r.activeMin > r.totalMin) errors += "activeMin > totalMin"
        if (r.steps.size !in 3..6) errors += "шагов ${r.steps.size}"
        if (Tags.SALTY in r.tags) errors += "тег salty"
        if (r.tags.any { it !in ALLOWED_TAGS }) errors += "неизвестный тег"
        if (r.slots.isEmpty() || r.slots.any { it !in SLOTS }) errors += "слоты"
        if (r.ingredients.none { !it.optional }) errors += "нет обязательных ингредиентов"
        if (r.steps.any { it.contains("алког", true) || it.contains("вино", true) || it.contains("пиво", true) }) errors += "алкоголь"
        if (r.steps.any { Regex("""\d+\s*г\s+сол""").containsMatchIn(it) }) errors += "соль в граммах"
        for (ing in r.ingredients) {
            if (ing.grams == null && ing.pieces == null) errors += "${ing.ref}: нет количества"
            if (catalog.none { matches(ing.ref, it) && Tags.SALTY !in it.tags && !it.untracked }) errors += "${ing.ref}: нет продукта в справочнике"
            if (!ing.ref.startsWith("product:")) {
                val role = ing.ref.substringBefore(':')
                if (FoodRole.entries.none { it.name.equals(role, true) }) errors += "${ing.ref}: неизвестная роль"
            }
        }
        return errors
    }

    /**
     * Обновление встроенной базы (18.8): рецепты plan/generated заменяются новой версией,
     * пользовательские (source = user) не трогаются.
     */
    fun merge(existing: List<Recipe>, file: CookbookFile): Pair<List<String>, List<Recipe>> {
        val userIds = existing.filter { it.source == "user" }.map { it.id }.toSet()
        val toDelete = existing.filter { it.source != "user" }.map { it.id }
        val toInsert = file.recipes.filter { it.id !in userIds }.map { it.copy(version = file.version) }
        return toDelete to toInsert
    }

    fun userCopy(r: Recipe, newId: String, steps: List<String> = r.steps, title: String = r.title): Recipe =
        r.copy(id = newId, source = "user", steps = steps, title = title)
}
