package ru.tinsurance.server.analysis

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Что отправляется на анализ. Данных клиента здесь нет. */
data class AnalysisRequest(
    val fileId: Long,
    val claimId: Long,
    /** путь файла в хранилище: claims/{claimId}/{uuid}.jpg */
    val storageKey: String,
    val documentType: String,
    val eventType: String?,
)

/** Результат анализа одного фото — поля раздела 6.2 требований. */
data class AnalysisResult(
    val modelVersion: String,
    val qualityScore: Float?,
    val qualityAcceptable: Boolean,
    /** BLURRY, LOW_LIGHT, OVEREXPOSED, LOW_RESOLUTION, NO_DAMAGE_VISIBLE */
    val qualityIssues: List<String>,
    /** SCRATCH, DENT, BROKEN_GLASS, BUMPER_DAMAGE, HEADLIGHT_DAMAGE; null — вид не определён */
    val damageType: String?,
    val damageConfidence: Float?,
    val carPart: String? = null,
    val damageArea: JsonElement? = null,
    /** ответ целиком — сохраняется в image_analyses.raw_result */
    val raw: JsonObject,
)

/** Сервис недоступен или не ответил вовремя — задачу стоит повторить. */
class AnalyzerUnavailableException(message: String) : Exception(message)

/** Сервис отказался анализировать файл — повтор не поможет. */
class AnalyzerRejectedException(message: String) : Exception(message)

fun interface ImageAnalyzer {
    suspend fun analyze(request: AnalysisRequest): AnalysisResult
}
