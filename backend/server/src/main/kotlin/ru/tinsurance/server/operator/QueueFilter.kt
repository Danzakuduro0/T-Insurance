package ru.tinsurance.server.operator

import io.ktor.http.*
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.common.parseDate
import ru.tinsurance.server.common.parseZone
import java.time.LocalDate
import java.time.ZoneId

enum class AssignedFilter { ME, NONE }

data class QueueFilter(
    val statuses: List<ClaimStatus> = emptyList(),
    val direction: String? = null,
    val minPriority: Int? = null,
    val assigned: AssignedFilter? = null,
    val assignedTo: Long? = null,
    val eventType: String? = null,
    /** Дата создания, включительно, в поясе [zone]. */
    val createdFrom: LocalDate? = null,
    val createdTo: LocalDate? = null,
    /** null — пояс по умолчанию (app.timeZone). */
    val zone: ZoneId? = null,
    val overdueOnly: Boolean = false,
    val manualReviewOnly: Boolean = false,
    /** Номер обращения, номер полиса или имя клиента. */
    val search: String? = null,
    val limit: Int = 50,
    val offset: Long = 0,
) {
    companion object {
        /**
         * ?status=QUEUED,IN_REVIEW&direction=STANDARD&minPriority=2&eventType=CAR_ACCIDENT&assigned=me|none
         * &assignedTo=5&createdFrom=2026-10-01&createdTo=2026-10-14&tz=Asia/Novosibirsk&overdue=true
         * &manualReview=true&search=TS-00&limit=50&offset=0
         */
        fun from(p: Parameters) = QueueFilter(
            statuses = p.getAll("status").orEmpty().flatMap { it.split(',') }
                .map { it.trim().uppercase() }.filter { it.isNotEmpty() }.map(::parseStatus),
            direction = p["direction"],
            minPriority = p["minPriority"]?.toIntOrNull(),
            assigned = p["assigned"]?.let(::parseAssigned),
            assignedTo = p["assignedTo"]?.let { it.toLongOrNull() ?: throw ValidationException("assignedTo: ожидается id сотрудника") },
            eventType = p["eventType"],
            createdFrom = p["createdFrom"]?.let { parseDate(it, "createdFrom") },
            createdTo = p["createdTo"]?.let { parseDate(it, "createdTo") },
            zone = p["tz"]?.let { parseZone(it, "tz") },
            overdueOnly = p["overdue"] == "true",
            manualReviewOnly = p["manualReview"] == "true",
            search = p["search"]?.trim()?.takeIf { it.isNotEmpty() },
            limit = (p["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200),
            offset = (p["offset"]?.toLongOrNull() ?: 0).coerceAtLeast(0),
        )

        private fun parseAssigned(value: String) = when (value) {
            "me" -> AssignedFilter.ME
            "none" -> AssignedFilter.NONE
            else -> throw ValidationException("assigned: допустимо me или none")
        }

        private fun parseStatus(value: String) =
            ClaimStatus.entries.firstOrNull { it.name == value }
                ?: throw ValidationException("status: допустимо ${ClaimStatus.entries.joinToString()}")
    }
}
