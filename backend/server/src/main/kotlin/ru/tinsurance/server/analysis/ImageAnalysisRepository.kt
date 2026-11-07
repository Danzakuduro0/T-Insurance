package ru.tinsurance.server.analysis

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.operator.ImageAnalysisDto
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.ImageAnalyses
import java.time.OffsetDateTime

/** Результаты анализа фото. Вызывается внутри транзакции. */
object ImageAnalysisRepository {

    /** Последний анализ каждого файла. */
    fun latestByFile(fileIds: Collection<Long>): Map<Long, ResultRow> =
        if (fileIds.isEmpty()) emptyMap()
        else ImageAnalyses.selectAll()
            .where { ImageAnalyses.fileId inList fileIds }
            .orderBy(ImageAnalyses.id to SortOrder.ASC)
            .associateBy { it[ImageAnalyses.fileId] }

    fun insert(fileId: Long, result: AnalysisResult, at: OffsetDateTime) {
        ImageAnalyses.insert {
            it[ImageAnalyses.fileId] = fileId
            it[modelVersion] = result.modelVersion
            it[qualityOk] = result.qualityAcceptable
            it[qualityScore] = result.qualityScore
            it[qualityIssues] = result.qualityIssues
            it[damageType] = result.damageType
            it[damageConfidence] = result.damageConfidence
            it[damageDetected] = when {
                "NO_DAMAGE_VISIBLE" in result.qualityIssues -> false
                result.damageType != null -> true
                else -> null
            }
            it[carPart] = result.carPart
            it[damageArea] = result.damageArea
            it[rawResult] = result.raw
            it[createdAt] = at
        }
    }
}

fun ResultRow.toImageAnalysisDto() = ImageAnalysisDto(
    modelVersion = this[ImageAnalyses.modelVersion],
    qualityOk = this[ImageAnalyses.qualityOk],
    qualityScore = this[ImageAnalyses.qualityScore],
    qualityIssues = this[ImageAnalyses.qualityIssues],
    damageDetected = this[ImageAnalyses.damageDetected],
    damageType = this[ImageAnalyses.damageType],
    damageConfidence = this[ImageAnalyses.damageConfidence],
    carPart = this[ImageAnalyses.carPart],
    damageArea = this[ImageAnalyses.damageArea],
    createdAt = this[ImageAnalyses.createdAt].iso(),
)
