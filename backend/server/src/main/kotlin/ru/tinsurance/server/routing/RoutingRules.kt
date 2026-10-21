package ru.tinsurance.server.routing

import io.ktor.server.config.*

data class RoutingConfig(
    /** раздел 6.4: уверенность модели ниже порога — ручная проверка; значение обосновывает ML-инженер (вопрос 24) */
    val confidenceThreshold: Float,
) {
    companion object {
        fun from(config: ApplicationConfig) = RoutingConfig(
            confidenceThreshold = config.propertyOrNull("routing.confidenceThreshold")?.getString()?.toFloat() ?: 0.65f,
        )
    }
}

/** Анализ одного фото на момент маршрутизации. */
data class PhotoAnalysis(
    val fileId: Long,
    /** DONE, FAILED или PENDING (не дождались) */
    val analysisStatus: String,
    val damageType: String? = null,
    val confidence: Float? = null,
)

data class RoutingInput(
    val photos: List<PhotoAnalysis>,
    /** номер обращения, в котором уже встречалось фото (вопрос 17) */
    val duplicateOfClaimNumber: String?,
)

data class RoutingDecision(
    /** STANDARD или MANUAL_REVIEW (справочник directions) */
    val direction: String,
    /** 1 — обычный, 2 — повышенный */
    val priority: Int,
    val manualReview: Boolean,
    /** сработавшие правила — показываются оператору вместо «чёрного ящика» (вопрос 23) */
    val reasons: List<String>,
    /** наименьшая уверенность модели по фото */
    val confidence: Float?,
    /** вид повреждения с наибольшей уверенностью */
    val damageType: String?,
)

/**
 * Куда отправить обращение после анализа фото (раздел 6.4). Чистая функция: правила легко объяснить и проверить.
 * Нехватка материалов и непригодные фото обрабатываются раньше — запросом клиенту (NEEDS_INFO).
 * Подтверждение и исправление решения оператором — шаг 4.
 */
object RoutingRules {
    const val VERSION = "routing-v1"
    const val STANDARD = "STANDARD"
    const val MANUAL_REVIEW = "MANUAL_REVIEW"

    fun decide(input: RoutingInput, config: RoutingConfig): RoutingDecision {
        val threshold = config.confidenceThreshold
        val reasons = mutableListOf<String>()

        val notAnalyzed = input.photos.filter { it.analysisStatus != "DONE" }
        if (notAnalyzed.isNotEmpty()) reasons += "Автоматический анализ недоступен для ${notAnalyzed.size} фото"

        val analyzed = input.photos.filter { it.analysisStatus == "DONE" }
        val unclassified = analyzed.filter { it.damageType == null || it.confidence == null }
        if (unclassified.isNotEmpty()) reasons += "Вид повреждения не определён на ${unclassified.size} фото"

        val classified = analyzed - unclassified.toSet()
        val minConfidence = classified.mapNotNull { it.confidence }.minOrNull()
        if (minConfidence != null && minConfidence < threshold) {
            reasons += "Уверенность модели ${percent(minConfidence)} ниже порога ${percent(threshold)}"
        }

        input.duplicateOfClaimNumber?.let { reasons += "Фото уже встречалось в обращении $it" }

        val manual = reasons.isNotEmpty()
        if (!manual) {
            reasons += if (input.photos.isEmpty()) "Фото для анализа нет" else "Все фото проанализированы, уверенность не ниже порога ${percent(threshold)}"
        }
        return RoutingDecision(
            direction = if (manual) MANUAL_REVIEW else STANDARD,
            // раздел 6.4: ручная проверка — приоритет повышается
            priority = if (manual) 2 else 1,
            manualReview = manual,
            reasons = reasons,
            confidence = minConfidence,
            damageType = classified.maxByOrNull { it.confidence ?: 0f }?.damageType,
        )
    }

    private fun percent(value: Float) = "${(value * 100).toInt()}%"
}
