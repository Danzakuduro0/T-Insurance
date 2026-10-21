package ru.tinsurance.server.analysis

import ru.tinsurance.server.common.stringOrNull
import io.ktor.server.config.*
import kotlinx.serialization.json.*
import java.time.Duration

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

data class AnalysisConfig(
    /** адрес ML-сервиса; null — локальная проверка качества без классификации повреждений */
    val mlUrl: String?,
    /** раздел 6.5: ML-сервис не ответил за 10 с — задача повторяется */
    val timeout: Duration,
    val modelVersion: String,
    /** раздел 6.5: паузы между повторами 30 / 90 / 270 с, потом — «анализ недоступен» */
    val retryDelays: List<Duration>,
    /** сколько обращение ждёт анализ фото, прежде чем уйти оператору без него */
    val maxWait: Duration,
    /** как часто задача ROUTE_CLAIM перепроверяет, закончен ли анализ (завершение анализа будит её раньше) */
    val routeRecheck: Duration,
) {
    fun retryDelay(attempt: Int): Duration = retryDelays.getOrElse(attempt - 1) { retryDelays.last() }

    companion object {
        fun from(config: ApplicationConfig) = AnalysisConfig(
            mlUrl = config.stringOrNull("analysis.mlUrl")?.trim(),
            timeout = Duration.ofSeconds(config.propertyOrNull("analysis.timeoutSeconds")?.getString()?.toLong() ?: 10),
            modelVersion = config.stringOrNull("analysis.modelVersion") ?: "latest",
            retryDelays = (config.stringOrNull("analysis.retryDelaysSeconds") ?: "30,90,270")
                .split(',').map { Duration.ofSeconds(it.trim().toLong()) },
            maxWait = Duration.ofMinutes(config.propertyOrNull("analysis.maxWaitMinutes")?.getString()?.toLong() ?: 15),
            routeRecheck = Duration.ofSeconds(config.propertyOrNull("analysis.routeRecheckSeconds")?.getString()?.toLong() ?: 30),
        )
    }
}

/** Разбор ответа ML-сервиса в формате раздела 6.2. */
object MlResponse {

    fun parse(body: JsonObject): AnalysisResult {
        val quality = body["quality"] as? JsonObject
            ?: throw AnalyzerRejectedException("В ответе ML-сервиса нет блока quality")
        val damage = body["damage"] as? JsonObject
        return AnalysisResult(
            modelVersion = body.string("model_version") ?: "unknown",
            qualityScore = quality.float("score"),
            qualityAcceptable = quality["acceptable"]?.jsonPrimitive?.booleanOrNull
                ?: throw AnalyzerRejectedException("В ответе ML-сервиса нет quality.acceptable"),
            qualityIssues = (quality["issues"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            damageType = damage?.string("type"),
            damageConfidence = damage?.float("confidence"),
            carPart = damage?.string("car_part"),
            damageArea = damage?.get("area")?.takeIf { it !is JsonNull },
            raw = body,
        )
    }

    fun hasQuality(body: JsonObject) = body["quality"] is JsonObject

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.float(key: String) = (get(key) as? JsonPrimitive)?.floatOrNull?.coerceIn(0f, 1f)
}
