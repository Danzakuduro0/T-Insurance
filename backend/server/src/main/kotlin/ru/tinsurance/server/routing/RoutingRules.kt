package ru.tinsurance.server.routing

import ru.tinsurance.api.AnalysisStatus

/** Анализ одного фото на момент маршрутизации. */
data class PhotoAnalysis(
    val fileId: Long,
    val analysisStatus: AnalysisStatus,
    val damageType: String? = null,
    val confidence: Float? = null,
)

data class RoutingInput(
    val photos: List<PhotoAnalysis>,
    /** Номер обращения, в котором уже встречалось фото. */
    val duplicateOfClaimNumber: String?,
)

data class RoutingDecision(
    /** STANDARD или MANUAL_REVIEW (справочник directions). */
    val direction: String,
    /** 1 — обычный, 2 — повышенный. */
    val priority: Int,
    val manualReview: Boolean,
    /** Сработавшие правила — оператор видит, почему принято решение. */
    val reasons: List<String>,
    /** Наименьшая уверенность модели по фото. */
    val confidence: Float?,
    /** Вид повреждения с наибольшей уверенностью. */
    val damageType: String?,
)

/** Куда отправить обращение после анализа фото. Чистая функция: правила легко объяснить и проверить. */
object RoutingRules {
    const val VERSION = "routing-v1"
    const val STANDARD = "STANDARD"
    const val MANUAL_REVIEW = "MANUAL_REVIEW"

    fun decide(input: RoutingInput, config: RoutingConfig): RoutingDecision {
        val threshold = config.confidenceThreshold
        val reasons = mutableListOf<String>()

        val notAnalyzed = input.photos.filter { it.analysisStatus != AnalysisStatus.DONE }
        if (notAnalyzed.isNotEmpty()) reasons += "Автоматический анализ недоступен для ${notAnalyzed.size} фото"

        val analyzed = input.photos.filter { it.analysisStatus == AnalysisStatus.DONE }
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
            priority = if (manual) 2 else 1,
            manualReview = manual,
            reasons = reasons,
            confidence = minConfidence,
            damageType = classified.maxByOrNull { it.confidence ?: 0f }?.damageType,
        )
    }

    private fun percent(value: Float) = "${(value * 100).toInt()}%"
}
