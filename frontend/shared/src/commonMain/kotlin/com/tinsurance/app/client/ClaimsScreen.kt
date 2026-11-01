package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.mobile.ClaimListItem
import tinsurance.shared.generated.resources.*

@Composable
fun ClaimsScreen(
    state: ClaimsState,
    tab: Int,
    onTab: (Int) -> Unit,
    onNew: () -> Unit,
    onOpen: (ClaimListItem) -> Unit,
    onPolicies: () -> Unit,
    onRetry: () -> Unit,
) {
    ScreenBody(spacing = 10.dp) {
        PageTitle("Обращения")
        InsuranceButton("Сообщить о случае", onNew)
        state.policies.firstOrNull()?.let { policy ->
            InsuranceCard(background = InsuranceColors.Field, border = null, onClick = onPolicies) {
                InfoRow(
                    Res.drawable.car_small,
                    "${policy.carName} · ${productName(policy.product)}",
                    "${policy.policyNumber} · до ${formatDate(policy.validTo, withYear = true)}",
                    trailing = Res.drawable.chevron_small,
                )
            }
        }
        SegmentedTabs(
            listOf(
                "Активные ${state.active.size}",
                "Черновики ${state.drafts.size}",
                "Завершённые",
            ),
            tab,
            onTab,
        )
        val shown =
            when (tab) {
                0 -> state.active
                1 -> state.drafts
                else -> state.completed
            }
        when {
            state.error != null -> {
                Notice(state.error)
                TextLink("Повторить", onRetry)
            }
            state.loading && state.claims.isEmpty() -> Caption("Загружаем обращения…")
            shown.isEmpty() ->
                Caption(
                    when (tab) {
                        0 -> "Активных обращений нет"
                        1 -> "Черновиков нет"
                        else -> "Завершённых обращений пока нет"
                    }
                )
        }
        for (claim in shown) {
            if (claim.status == ClaimStatus.DRAFT) {
                InsuranceCard(
                    background = InsuranceColors.Field,
                    border = null,
                    padding = 10.dp,
                    onClick = { onOpen(claim) },
                ) {
                    InfoRow(
                        Res.drawable.file_draft,
                        "Черновик № ${claim.claimNumber}",
                        "Продолжить заполнение",
                        trailing = Res.drawable.chevron_small,
                    )
                }
            } else ClaimCard(claim) { onOpen(claim) }
        }
    }
}

@Composable
private fun ClaimCard(claim: ClaimListItem, onClick: () -> Unit) {
    InsuranceCard(onClick = onClick) {
        Caption("№ ${claim.claimNumber} · ${formatDate(claim.eventAt ?: claim.createdAt)}")
        Label(claim.eventTypeName ?: "Тип события не указан")
        val (background, color) = statusColors(claim.status)
        StatusBadge(claim.statusTitle, background, color)
        when {
            claim.actionRequired ->
                TextLink(
                    claim.actionMessage ?: "Добавить материалы",
                    onClick,
                    Res.drawable.file_link,
                )
            claim.isFinal -> TextLink("Открыть сводку", onClick)
            else -> claim.actionMessage?.let { Caption(it) }
        }
    }
}

private fun statusColors(status: ClaimStatus): Pair<Color, Color> =
    when (status) {
        ClaimStatus.NEEDS_INFO -> InsuranceColors.AmberSoft to InsuranceColors.Amber
        ClaimStatus.COMPLETED -> InsuranceColors.GreenSoft to InsuranceColors.Green
        ClaimStatus.CANCELLED -> InsuranceColors.RedSoft to InsuranceColors.Red
        ClaimStatus.DRAFT -> InsuranceColors.Field to InsuranceColors.Muted
        else -> InsuranceColors.BlueSoft to InsuranceColors.Blue
    }

fun productName(code: String) =
    when (code) {
        "KASKO" -> "КАСКО"
        "OSAGO" -> "ОСАГО"
        else -> code
    }

val PolicyDto.carName: String
    get() {
        fun field(key: String) = (insuredObject[key] as? JsonPrimitive)?.contentOrNull
        return listOfNotNull(field("make"), field("model")).joinToString(" ").ifBlank { "Автомобиль" }
    }

private val monthsGenitive =
    listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

/** "2026-10-17T18:35:00+07:00" или "2026-12-31" → "17 октября" / "31 декабря 2026". */
fun formatDate(iso: String, withYear: Boolean = false): String {
    val parts = iso.take(10).split('-')
    val year = parts.getOrNull(0)
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    if (year == null || month !in 1..12 || day == null) return iso
    val text = "$day ${monthsGenitive[month!! - 1]}"
    return if (withYear) "$text $year" else text
}
