package ru.tinsurance.server.analysis

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Клиент ML Image Service: POST {mlUrl}/analyze (раздел 6.2 требований).
 * Сервис читает файл сам по storage_key (общая папка с файлами в Docker Compose, позже — S3).
 * Ошибки делятся на «повторить позже» (недоступен, перегружен, нет ответа за 10 с) и «повтор не поможет».
 */
class HttpImageAnalyzer(
    baseUrl: String,
    private val timeout: Duration,
    private val modelVersion: String,
) : ImageAnalyzer {
    private val endpoint = URI(baseUrl.trimEnd('/') + "/analyze")
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
        val body = buildJsonObject {
            put("document_id", request.fileId.toString())
            put("claim_id", request.claimId.toString())
            put("storage_key", request.storageKey)
            put("model_version", modelVersion)
            put("document_type", request.documentType)
            request.eventType?.let { put("event_type", it) }
        }
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = try {
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: HttpTimeoutException) {
            throw AnalyzerUnavailableException("ML-сервис не ответил за ${timeout.seconds} с")
        } catch (e: IOException) {
            throw AnalyzerUnavailableException("ML-сервис недоступен: ${e.message}")
        }

        val status = response.statusCode()
        val json = runCatching { Json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
        return when {
            status == 200 && json != null -> MlResponse.parse(json)
            // раздел 6.2: 422 с телом — фото непригодно, это тоже результат анализа
            status == 422 && json != null && MlResponse.hasQuality(json) -> MlResponse.parse(json)
            status == 429 || status == 503 || status >= 500 ->
                throw AnalyzerUnavailableException("ML-сервис ответил $status")
            else -> throw AnalyzerRejectedException("ML-сервис ответил $status: ${response.body().take(300)}")
        }
    }
}
