package ru.tinsurance.server.openapi

import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.*
import ru.tinsurance.api.operator.*
import kotlinx.serialization.json.*

internal fun ApiDocument.operatorOperations() {
    operation("get", OperatorApi.ME, "Профиль", schema<UserDto>())
    operation("get", OperatorApi.OPERATORS, "Список операторов (ADMIN)", schema<List<OperatorDto>>())
    operation("get", OperatorApi.DIRECTIONS, "Направления", schema<List<DirectionDto>>())
    operation("get", OperatorApi.CLAIM, "Карточка обращения кроме DRAFT", schema<ClaimCard>())
    operation("put", OperatorApi.ME_AVAILABILITY, "Изменить свою смену (OPERATOR)", schema<UserDto>(), schema<AvailabilityRequest>())
    operation("post", OperatorApi.CLAIM_TAKE, "Взять обращение (OPERATOR)", schema<ClaimCard>())
    operation("post", OperatorApi.CLAIM_START_REVIEW, "Начать рассмотрение своего обращения", schema<ClaimCard>())
    operation("post", OperatorApi.CLAIM_RELEASE, "Вернуть в очередь", schema<ClaimCard>(), schema<ReleaseClaimRequest>())
    operation("post", OperatorApi.CLAIM_ASSIGN, "Назначить или переназначить (ADMIN)", schema<ClaimCard>(), schema<AssignClaimRequest>())
    operation("post", OperatorApi.CLAIM_DECISION_CONFIRM, "Подтвердить DAMAGE, ROUTING или PRIORITY", schema<ClaimCard>(), schema<ConfirmDecisionRequest>())
    operation("post", OperatorApi.CLAIM_DECISION_OVERRIDE, "Исправить DAMAGE, ROUTING или PRIORITY с причиной; история сохраняется", schema<ClaimCard>(), schema<OverrideDecisionRequest>())
    operation("post", OperatorApi.CLAIM_REQUEST_MATERIALS, "Запросить материалы из IN_REVIEW; ровно documentType или fieldName в каждом item", schema<ClaimCard>(), schema<RequestMaterialsRequest>())
    operation("put", OperatorApi.CLAIM_REVIEW_RESULT, "Сохранить итог рассмотрения своего обращения", schema<ClaimCard>(), schema<ReviewResultRequest>())
    operation("post", OperatorApi.CLAIM_COMPLETE, "Завершить после итога текущего рассмотрения", schema<ClaimCard>())
    operation("post", OperatorApi.CLAIM_CANCEL, "Отменить с внутренней причиной и комментарием клиенту", schema<ClaimCard>(), schema<CancelClaimRequest>())
    operation("get", OperatorApi.DASHBOARD, "Нагрузка и показатели (только ADMIN); секунды, доли 0–1, все время", schema<DashboardDto>())
    operation("get", OperatorApi.QUEUE, "Очередь обращений", schema<Page<QueueItem>>(), query = listOf(
        parameter("status", description = "Статусы через запятую; допускается повтор параметра"), parameter("direction"), parameter("minPriority", "integer", "1–3"),
        parameter("assigned", description = "me или none"), parameter("assignedTo", "integer"), parameter("eventType"),
        parameter("createdFrom", description = "YYYY-MM-DD включительно"), parameter("createdTo", description = "YYYY-MM-DD включительно"),
        parameter("tz", description = "IANA, например Asia/Krasnoyarsk"), parameter("overdue", "boolean"), parameter("manualReview", "boolean"),
        parameter("search"), parameter("limit", "integer", "1–200, по умолчанию 50"), parameter("offset", "integer", "По умолчанию 0")))
    operation("get", OperatorApi.CLAIM_FILE_CONTENT, "Содержимое файла обращения (JPEG/PNG/PDF)", obj("type" to str("string"), "format" to str("binary")), binary = true)
}
