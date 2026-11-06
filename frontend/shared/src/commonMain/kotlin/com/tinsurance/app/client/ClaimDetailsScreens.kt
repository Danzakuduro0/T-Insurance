package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.TimelineEvent
import tinsurance.shared.generated.resources.*

// Экраны 08 и 11 на данных GET /claims/{id} и /timeline. Демо-версии — в ClaimStatusScreens.kt.

@Composable
fun ClaimProgressScreen(
    state: ClaimState,
    policy: PolicyDto?,
    onMaterials: () -> Unit,
    onDetails: () -> Unit,
    onRetry: () -> Unit,
) {
    ScreenBody {
        val claim = state.details
        if (claim == null) {
            LoadingOrError(state, onRetry)
            return@ScreenBody
        }
        val (background, color) = statusColors(claim.status)
        InsuranceCard(background = background, border = null, padding = 16.dp) {
            Text(claim.statusTitle, style = MaterialTheme.typography.titleMedium, color = color)
            Caption(state.timeline.lastOrNull()?.comment ?: statusHint(claim.status))
        }
        InsuranceCard {
            InfoRow(
                Res.drawable.car,
                listOfNotNull(
                        policy?.carName,
                        claim.eventTypeName,
                        claim.eventAt?.let { formatDate(it) },
                    )
                    .joinToString(" · "),
                listOfNotNull(policy?.let { productName(it.product) }, claim.policyNumber)
                    .joinToString(" "),
            )
        }
        InsuranceCard(spacing = 12.dp) {
            Label("Ход обработки")
            Timeline(timelineItems(claim, state.timeline))
        }
        val files = claim.visibleFiles
        InfoRow(
            Res.drawable.file,
            "Материалы · ${filesCount(files.size)}",
            trailing = Res.drawable.chevron_small,
            onClick = onMaterials,
        )
        InfoRow(
            Res.drawable.info,
            "Сведения об обращении",
            trailing = Res.drawable.chevron_small,
            onClick = onDetails,
        )
    }
}

@Composable
fun ClaimCompletedScreen(state: ClaimState, onRetry: () -> Unit) {
    ScreenBody(spacing = 10.dp) {
        val claim = state.details
        if (claim == null) {
            LoadingOrError(state, onRetry)
            return@ScreenBody
        }
        val (background, color) = statusColors(claim.status)
        StatusBadge(claim.statusTitle, background, color, fullWidth = true)
        val last = state.timeline.lastOrNull { it.status == claim.status }
        InsuranceCard(spacing = 8.dp) {
            Text("Результат обработки", style = MaterialTheme.typography.titleMedium)
            Text(
                last?.comment ?: statusHint(claim.status),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (last != null) {
                val verb = if (claim.status == ClaimStatus.CANCELLED) "Закрыто" else "Завершено"
                Caption("$verb ${formatDateTime(last.at)}")
            }
        }
        InsuranceCard(spacing = 8.dp) {
            Label("История обращения")
            Timeline(timelineItems(claim, state.timeline))
        }
        Text(
            "Решение о выплате сообщается отдельно",
            style = MaterialTheme.typography.labelSmall,
            color = InsuranceColors.Muted,
        )
    }
}

@Composable
internal fun LoadingOrError(state: ClaimState, onRetry: () -> Unit) {
    if (state.error != null) {
        Notice(state.error)
        TextLink("Повторить", onRetry)
    } else Caption("Загружаем обращение…")
}

/** Файлы, которые клиент видит в обращении: без удалённых и заменённых. */
val ClaimDetails.visibleFiles
    get() = files.filter { it.status != FileStatus.DELETED && it.status != FileStatus.REPLACED }

fun timelineItems(claim: ClaimDetails, allEvents: List<TimelineEvent>): List<TimelineItem> = buildList {
    // Создание черновика клиенту не интересно: история начинается с отправки.
    val events = allEvents.filter { it.status != ClaimStatus.DRAFT }
    events.forEachIndexed { index, event ->
        val status =
            when {
                event.status == ClaimStatus.NEEDS_INFO -> "request"
                index == events.lastIndex && !claim.isFinal -> "current"
                else -> "done"
            }
        add(TimelineItem(event.title, formatDateTime(event.at), status))
    }
    if (!claim.isFinal) add(TimelineItem("Обработка завершена", status = "waiting"))
}

internal fun statusHint(status: ClaimStatus) =
    when (status) {
        ClaimStatus.SUBMITTED,
        ClaimStatus.IN_ANALYSIS -> "Проверяем материалы"
        ClaimStatus.QUEUED -> "Скоро передадим специалисту"
        ClaimStatus.ASSIGNED,
        ClaimStatus.IN_REVIEW -> "Специалист изучает материалы"
        ClaimStatus.NEEDS_INFO -> "Нужно добавить материалы"
        ClaimStatus.COMPLETED -> "Рассмотрение материалов завершено"
        ClaimStatus.CANCELLED -> "Обращение закрыто"
        ClaimStatus.DRAFT -> "Обращение ещё не отправлено"
    }

fun filesCount(count: Int): String {
    val word =
        when {
            count % 100 in 11..14 -> "файлов"
            count % 10 == 1 -> "файл"
            count % 10 in 2..4 -> "файла"
            else -> "файлов"
        }
    return "$count $word"
}
