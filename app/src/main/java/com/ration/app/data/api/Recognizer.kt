package com.ration.app.data.api

import android.net.Uri

/**
 * Режим «Claude API» (раздел 5.6). В сборке offline — заглушка без сети,
 * в сборке api — запрос по ключу пользователя. Отправляются только фото/текст и промпт.
 */
interface Recognizer {
    val available: Boolean
    suspend fun recognize(photo: Uri?, text: String?, prompt: String): String
}

class RecognizerException(message: String) : Exception(message)
