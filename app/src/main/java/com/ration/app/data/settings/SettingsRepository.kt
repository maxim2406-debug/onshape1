package com.ration.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ration.app.domain.inventory.AlertState
import com.ration.app.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Настройки хранятся одной JSON-строкой: так проще версия схемы и резервная копия. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settingsKey = stringPreferencesKey("app_settings")
    private val alertKey = stringPreferencesKey("alert_state")
    private val warnedKey = stringPreferencesKey("warned_today")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[settingsKey]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val cur = prefs[settingsKey]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
            prefs[settingsKey] = json.encodeToString(AppSettings.serializer(), transform(cur))
        }
    }

    suspend fun replace(s: AppSettings) = update { s }

    suspend fun alertState(): AlertState = context.dataStore.data.first()[alertKey]
        ?.let { runCatching { json.decodeFromString(AlertState.serializer(), it) }.getOrNull() } ?: AlertState()

    suspend fun setAlertState(s: AlertState) {
        context.dataStore.edit { it[alertKey] = json.encodeToString(AlertState.serializer(), s) }
    }

    /** Какие предупреждения дня уже отправлены уведомлением: "day:id". */
    suspend fun warnedToday(): Set<String> = context.dataStore.data.first()[warnedKey]
        ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it).toSet() }.getOrNull() } ?: emptySet()

    suspend fun setWarned(keys: Set<String>) {
        context.dataStore.edit { it[warnedKey] = json.encodeToString(ListSerializer(String.serializer()), keys.toList().takeLast(200)) }
    }

    /** Флаги «уведомление отправлено сегодня» для ежедневных проверок. */
    suspend fun flag(name: String): String? = context.dataStore.data.first()[stringPreferencesKey("flag_$name")]
    suspend fun setFlag(name: String, value: String) {
        context.dataStore.edit { it[stringPreferencesKey("flag_$name")] = value }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
