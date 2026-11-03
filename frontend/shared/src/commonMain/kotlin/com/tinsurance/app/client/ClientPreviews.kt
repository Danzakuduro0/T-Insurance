package com.tinsurance.app.client

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.tinsurance.app.App
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.mobile.ClaimListItem
import ru.tinsurance.api.mobile.NotificationDto

@Preview(name = "01 · Мои обращения", widthDp = 390, heightDp = 844)
@Composable
fun ClaimsPreview() = App(true, ClientScreen.Claims)

@Preview(name = "02 · Договор и тип события", widthDp = 390, heightDp = 844)
@Composable
fun ContractPreview() = App(true, ClientScreen.Contract)

@Preview(name = "03 · Обстоятельства и контакты", widthDp = 390, heightDp = 844)
@Composable
fun EventPreview() = App(true, ClientScreen.Event)

@Preview(name = "04 · Фото и документы", widthDp = 390, heightDp = 844)
@Composable
fun MaterialsPreview() = App(true, ClientScreen.Materials)

@Preview(name = "05 · Проверка качества фото", widthDp = 390, heightDp = 844)
@Composable
fun PhotoQualityPreview() = App(true, ClientScreen.PhotoQuality)

@Preview(name = "06 · Проверка перед отправкой", widthDp = 390, heightDp = 844)
@Composable
fun ReviewPreview() = App(true, ClientScreen.Review)

@Preview(name = "07 · Обращение отправлено", widthDp = 390, heightDp = 844)
@Composable
fun SentPreview() = App(true, ClientScreen.Sent)

@Preview(name = "08 · Ход обработки", widthDp = 390, heightDp = 844)
@Composable
fun ProgressPreview() = App(true, ClientScreen.Progress)

@Preview(name = "09 · Запрос материалов", widthDp = 390, heightDp = 844)
@Composable
fun RequestPreview() = App(true, ClientScreen.Request)

@Preview(name = "10 · Ответ на запрос", widthDp = 390, heightDp = 844)
@Composable
fun ReplyPreview() = App(true, ClientScreen.Reply)

@Preview(name = "11 · Обработка завершена", widthDp = 390, heightDp = 844)
@Composable
fun CompletedPreview() = App(true, ClientScreen.Completed)

@Preview(name = "12 · Центр уведомлений", widthDp = 390, heightDp = 844)
@Composable
fun NotificationsPreview() = App(true, ClientScreen.Notifications)

private fun previewClaim(
    id: Long,
    number: String,
    status: ClaimStatus,
    title: String,
    day: String,
    actionMessage: String? = null,
) =
    ClaimListItem(
        id = id,
        claimNumber = number,
        status = status,
        statusTitle = title,
        isFinal = status.isFinal,
        eventTypeName = "Повреждение автомобиля",
        eventAt = "2026-10-${day}T18:35:00+07:00",
        createdAt = "2026-10-${day}T19:00:00+07:00",
        updatedAt = "2026-10-${day}T19:00:00+07:00",
        actionRequired = status == ClaimStatus.NEEDS_INFO,
        actionMessage = actionMessage,
    )

/** Список обращений для @Preview: в превью нет backend и входа. */
val PreviewClaims =
    ClaimsState(
        policies =
            listOf(
                PolicyDto(
                    id = 1,
                    policyNumber = "TEST-0421",
                    product = "KASKO",
                    insuredObject =
                        buildJsonObject {
                            put("make", "Kia")
                            put("model", "Rio")
                        },
                    validFrom = "2026-01-01",
                    validTo = "2026-12-31",
                )
            ),
        claims =
            listOf(
                previewClaim(1, "ТС-10428", ClaimStatus.IN_REVIEW, "На рассмотрении", "17"),
                previewClaim(
                    2,
                    "ТС-10410",
                    ClaimStatus.NEEDS_INFO,
                    "Нужны документы",
                    "12",
                    "Добавить материалы",
                ),
                previewClaim(3, "ТС-10430", ClaimStatus.DRAFT, "Черновик", "18"),
                previewClaim(4, "ТС-10386", ClaimStatus.COMPLETED, "Обработка завершена", "10"),
            ),
    )

/** Уведомления для @Preview. */
val PreviewNotifications =
    NotificationsState(
        unreadCount = 2,
        items =
            listOf(
                NotificationDto(3, 2, "DOCUMENTS_REQUIRED", "Нужны документы", "Добавьте извещение о ДТП", "2026-10-17T12:10:00+07:00", null),
                NotificationDto(2, 1, "STATUS_CHANGED", "На рассмотрении", "На рассмотрении", "2026-10-17T10:15:00+07:00", null),
                NotificationDto(1, 1, "STATUS_CHANGED", "Обращение отправлено", "Обращение отправлено", "2026-10-16T09:41:00+07:00", "2026-10-16T10:00:00+07:00"),
            ),
    )
