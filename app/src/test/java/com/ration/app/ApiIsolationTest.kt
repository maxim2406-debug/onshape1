package com.ration.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 20.7: сборка api не отправляет данные о здоровье — код сетевого клиента не видит модули здоровья.
 * Проверка по исходникам flavor api: ни импорта, ни упоминания классов здоровья.
 */
class ApiIsolationTest {
    private val forbidden = listOf(
        "HealthRepository", "HealthInsights", "DocumentStore", "domain.health", "WeightLog", "BpLog", "Workout", "LabResult",
        "HealthDocument", "FormDaily", "health()", "health2()",
    )

    private fun root(): File = listOf(File("src/api/java"), File("app/src/api/java")).first { it.isDirectory }

    @Test fun apiCodeHasNoHealthAccess() {
        val files = root().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("нет исходников api", files.isNotEmpty())
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> forbidden.firstOrNull { line.contains(it) }?.let { "${f.name}:${i + 1} $it" } } }
        assertTrue("api-код обращается к данным здоровья: $hits", hits.isEmpty())
    }
}
