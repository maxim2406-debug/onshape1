package com.ration.app.data.api

import android.net.Uri
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Сборка offline: сети нет (в манифесте нет INTERNET), распознавание только через копирование промпта. */
object OfflineRecognizer : Recognizer {
    override val available = false
    override suspend fun recognize(photo: Uri?, text: String?, prompt: String): String =
        throw RecognizerException("В этой сборке режим Claude API недоступен")
}

@Module
@InstallIn(SingletonComponent::class)
object RecognizerModule {
    @Provides @Singleton
    fun recognizer(): Recognizer = OfflineRecognizer
}
