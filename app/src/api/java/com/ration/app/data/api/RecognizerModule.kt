package com.ration.app.data.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.ration.app.data.api.sdk.ClaudeCallException
import com.ration.app.data.api.sdk.ClaudeVisionClient
import com.ration.app.data.settings.SecureKeyStore
import com.ration.app.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Сборка api: запрос только по нажатию кнопки и только при включённом режиме с ключом. */
@Singleton
class ClaudeRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keys: SecureKeyStore,
    private val settings: SettingsRepository,
) : Recognizer {
    override val available = true

    override suspend fun recognize(photo: Uri?, text: String?, prompt: String): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (!s.claudeApiEnabled) throw RecognizerException("Режим Claude API выключен в настройках")
        val key = keys.apiKey() ?: throw RecognizerException("Не задан API-ключ")
        val jpeg = photo?.let { loadJpeg(it) }
        try {
            ClaudeVisionClient(key, s.claudeModel).recognize(jpeg, text, prompt)
        } catch (e: ClaudeCallException) {
            throw RecognizerException(e.message ?: "Ошибка запроса")
        }
    }

    /** Уменьшение до 1568 px по длинной стороне и JPEG ~85% (укладывается в лимит изображения API). */
    private fun loadJpeg(uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) throw RecognizerException("Не удалось прочитать изображение")
        var sample = 1
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw RecognizerException("Не удалось прочитать изображение")
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private companion object { const val MAX_SIDE = 1568 }
}

@Module
@InstallIn(SingletonComponent::class)
object RecognizerModule {
    @Provides @Singleton
    fun recognizer(impl: ClaudeRecognizer): Recognizer = impl
}
