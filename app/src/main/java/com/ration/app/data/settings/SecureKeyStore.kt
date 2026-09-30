package com.ration.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API-ключ Claude: EncryptedSharedPreferences (ключ шифрования в Android Keystore).
 * Не логируется, не входит в резервную копию и экспорт (allowBackup=false + исключения в правилах).
 */
@Singleton
class SecureKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun apiKey(): String? = runCatching { prefs.getString(KEY, null) }.getOrNull()?.takeIf { it.isNotBlank() }
    fun hasApiKey(): Boolean = apiKey() != null

    fun setApiKey(value: String?) {
        prefs.edit().apply { if (value.isNullOrBlank()) remove(KEY) else putString(KEY, value.trim()) }.apply()
    }

    fun clear() {
        runCatching { prefs.edit().clear().apply() }
        context.deleteSharedPreferences(FILE)
    }

    private companion object {
        const val FILE = "secure_prefs"
        const val KEY = "claude_api_key"
    }
}
