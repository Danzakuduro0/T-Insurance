package ru.tinsurance.server.files

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.FileStatus
import ru.tinsurance.server.db.ClaimFiles

data class FileMatch(val fileId: Long, val claimId: Long)

/**
 * Тот же снимок в другом обращении — признак для ручной проверки. Сначала точная копия (SHA-256),
 * иначе похожая по перцептивному хэшу (пересжатая, уменьшенная). Вызывается внутри транзакции.
 */
class DuplicateFinder(private val phashMaxDistance: Int) {

    fun findElsewhere(claimId: Long, sha256: String, phash: Long?): FileMatch? {
        val otherClaims = (ClaimFiles.claimId neq claimId) and (ClaimFiles.status eq FileStatus.ACCEPTED)
        return first((ClaimFiles.sha256 eq sha256) and otherClaims)
            ?: phash?.let { first(HammingDistanceAtMost(ClaimFiles.phash, it, phashMaxDistance) and otherClaims) }
    }

    private fun first(condition: Op<Boolean>): FileMatch? =
        ClaimFiles.selectAll().where { condition }.orderBy(ClaimFiles.id).firstOrNull()
            ?.let { FileMatch(it[ClaimFiles.id], it[ClaimFiles.claimId]) }
}

/** bit_count(a xor b) <= maxBits. */
private class HammingDistanceAtMost(val column: Column<Long?>, val value: Long, val maxBits: Int) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("bit_count((", column, " # (", value.toString(), ")::bigint)::bit(64)) <= ", maxBits.toString())
    }
}
