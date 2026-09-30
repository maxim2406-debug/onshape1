package com.ration.app.data.api.sdk

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import java.time.Duration
import java.util.Base64

class ClaudeCallException(message: String) : Exception(message)

/**
 * Один запрос к Messages API через официальный SDK. Без Android-зависимостей.
 * В запрос уходят только изображение (JPEG), текст чека/этикетки и промпт.
 */
class ClaudeVisionClient(private val apiKey: String, private val model: String) {
    fun recognize(jpeg: ByteArray?, text: String?, prompt: String): String {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(120))
            .maxRetries(2)
            .build()
        try {
            val blocks = mutableListOf<ContentBlockParam>()
            if (jpeg != null) {
                blocks += ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(
                            Base64ImageSource.builder()
                                .data(Base64.getEncoder().encodeToString(jpeg))
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .build(),
                        )
                        .build(),
                )
            }
            val fullPrompt = if (text.isNullOrBlank()) prompt else "$prompt\n\n$text"
            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(fullPrompt).build())
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .addUserMessageOfBlockParams(blocks)
                .build()
            val response = client.messages().create(params)
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                throw ClaudeCallException("Модель отклонила запрос. Используйте копирование промпта.")
            }
            return response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
        } catch (e: UnauthorizedException) {
            throw ClaudeCallException("Неверный API-ключ")
        } catch (e: RateLimitException) {
            throw ClaudeCallException("Превышен лимит запросов, попробуйте позже")
        } catch (e: AnthropicServiceException) {
            throw ClaudeCallException("Ошибка API (${e.statusCode()})")
        } catch (e: AnthropicIoException) {
            throw ClaudeCallException("Нет сети. Используйте копирование промпта.")
        } finally {
            client.close()
        }
    }
}
