package ru.tinsurance.server.openapi

import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.*
import ru.tinsurance.api.operator.*
import kotlinx.serialization.json.*

internal fun ApiDocument.mobileOperations() {
    operation("get", MobileApi.ME, "Профиль", schema<UserDto>())
    operation("get", MobileApi.POLICIES, "Договоры", schema<List<PolicyDto>>())
    operation("get", MobileApi.EVENT_TYPES, "Правила форм и документов", schema<List<EventTypeDto>>())
    operation("get", MobileApi.CLAIMS, "Мои обращения", schema<List<ClaimListItem>>())
    operation("post", MobileApi.CLAIMS, "Создать черновик; UUID clientRequestId обеспечивает идемпотентность", schema<ClaimDetails>(), schema<CreateClaimRequest>(), codes = listOf(200, 201))
    operation("get", MobileApi.CLAIM, "Карточка своего обращения", schema<ClaimDetails>())
    operation("patch", MobileApi.CLAIM, "Изменить черновик; при NEEDS_INFO только запрошенные поля; null не изменяет поле", schema<ClaimDetails>(), schema<UpdateClaimRequest>())
    operation("get", MobileApi.CLAIM_COMPLETENESS, "Проверка комплектности", schema<CompletenessDto>())
    operation("post", MobileApi.CLAIM_SUBMIT, "Отправить обращение; неполный комплект возвращает NEEDS_INFO", schema<ClaimDetails>())
    operation("get", MobileApi.CLAIM_TIMELINE, "Временная шкала", schema<List<TimelineEvent>>())
    operation("post", MobileApi.NOTIFICATION_READ, "Отметить своё уведомление прочитанным, идемпотентно", schema<NotificationDto>())
    operation("get", MobileApi.NOTIFICATIONS, "Центр уведомлений", schema<NotificationsPage>(), query = listOf(
        parameter("limit", "integer", "1–100, по умолчанию 20"), parameter("beforeId", "integer", "Курсор nextBeforeId предыдущей страницы"), parameter("unreadOnly", "boolean", "По умолчанию false")))
    operation("delete", MobileApi.CLAIM_FILE, "Удалить файл только из DRAFT", codes = listOf(204))
    val upload = obj("type" to str("object"), "required" to strings("documentType", "file"), "properties" to obj(
        "documentType" to obj("type" to str("string")), "file" to obj("type" to str("string"), "format" to str("binary")),
        "clientRequestId" to obj("type" to str("string"), "format" to str("uuid")), "replacesFileId" to obj("type" to str("integer"), "format" to str("int64"))))
    operation("post", MobileApi.CLAIM_FILES, "Загрузить JPEG/PNG/PDF: 201 принят, 200 повтор/дубль, 422 отклонён. Лимиты из event-types; замена через replacesFileId", schema<ClaimFileDto>(), upload,
        codes = listOf(200, 201, 422), multipart = true)
    operation("get", MobileApi.CLAIM_FILE_CONTENT, "Содержимое своего файла (JPEG/PNG/PDF)", obj("type" to str("string"), "format" to str("binary")), binary = true)
}
