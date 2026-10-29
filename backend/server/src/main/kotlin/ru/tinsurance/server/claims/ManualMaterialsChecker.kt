package ru.tinsurance.server.claims

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.ResultRow
import ru.tinsurance.api.*
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.db.*
import ru.tinsurance.server.files.FileRepository
import java.time.OffsetDateTime

object ManualMaterialsChecker {
    fun requireFulfilled(claimId: Long) {
        val missing = unfulfilled(claimId)
        if (missing.isNotEmpty()) throw ConflictException("Выполните запрос оператора: ${missing.joinToString("; ")}")
    }

    fun unfulfilled(claimId: Long): List<String> {
        val request = ManualMaterialsRepository.open(claimId) ?: return emptyList()
        val since = request[InfoRequests.createdAt]
        val items = ManualMaterialsRepository.items(request[InfoRequests.id])
        val files = FileRepository.rows(claimId)
        val replacements = items.mapNotNull { it[InfoRequestItems.fileId] }.associateWith { replacement(it, files) }
        val reserved = replacements.values.filterNotNull().toSet()
        val answered = answeredFields(claimId, since)
        return items.filterNot { item ->
            val field = item[InfoRequestItems.fieldName]
            val original = item[InfoRequestItems.fileId]
            when {
                field != null -> field in answered
                original != null -> replacements[original] != null
                else -> files.values.count {
                    it[ClaimFiles.status] == FileStatus.ACCEPTED && it[ClaimFiles.documentType] == item[InfoRequestItems.documentType] &&
                        it[ClaimFiles.uploadedAt] >= since && it[ClaimFiles.id] !in reserved
                } >= item[InfoRequestItems.minCount]
            }
        }.map { it[InfoRequestItems.comment] ?: "Дополните материалы" }
    }

    private fun replacement(original: Long, files: Map<Long, ResultRow>): Long? {
        var next = files[original]?.get(ClaimFiles.replacedByFileId)
        val seen = mutableSetOf<Long>()
        while (next != null && seen.add(next)) {
            val row = files[next] ?: return null
            if (row[ClaimFiles.status] == FileStatus.ACCEPTED) return next
            next = row[ClaimFiles.replacedByFileId]
        }
        return null
    }

    private fun answeredFields(claimId: Long, since: OffsetDateTime): Set<String> =
        ManualMaterialsRepository.latestFieldAnswers(claimId, since).filterValues { value ->
            value != JsonNull && !(value is JsonPrimitive && value.isString && value.content.isBlank()) &&
                !(value is JsonArray && value.isEmpty())
        }.keys
}
