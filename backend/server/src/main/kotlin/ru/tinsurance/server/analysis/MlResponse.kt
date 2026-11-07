package ru.tinsurance.server.analysis

import kotlinx.serialization.json.*

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
