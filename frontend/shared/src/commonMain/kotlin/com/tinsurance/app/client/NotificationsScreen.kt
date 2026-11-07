package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import org.jetbrains.compose.resources.DrawableResource
import ru.tinsurance.api.mobile.NotificationDto
import tinsurance.shared.generated.resources.*

/** Тип уведомления с backend: запрос материалов — отдельный цвет и иконка. */
private const val DocumentsRequired = "DOCUMENTS_REQUIRED"

@Composable
fun NotificationsScreen(
    state: NotificationsState,
    claimNumbers: Map<Long, String>,
    onReadAll: () -> Unit,
    onOpen: (NotificationDto) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
) {
    ScreenBody {
        PageTitle("Уведомления")
        when {
            state.items.isEmpty() && state.error != null -> {
                Notice(state.error)
                TextLink("Повторить", onRetry)
                return@ScreenBody
            }
            state.items.isEmpty() && state.loading -> {
                Caption("Загружаем уведомления…")
                return@ScreenBody
            }
            state.items.isEmpty() -> {
                Caption("Уведомлений пока нет. Здесь появятся новости по вашим обращениям.")
                return@ScreenBody
            }
        }
        if (state.items.any { it.readAt == null }) TextLink("Прочитать все", onReadAll)
        else Caption("Все уведомления прочитаны")
        // Сервер отдаёт страницы по id, а не по дате: внутри загруженного сортируем по времени.
        // Группы по дням в поясе, который прислал сервер: «2 ноября», «31 октября»…
        val sorted = state.items.sortedByDescending { it.createdAt }
        sorted.groupBy { formatDate(it.createdAt) }.forEach { (day, items) ->
            Label(day)
            for (item in items) {
                NotificationCard(item, item.claimId?.let(claimNumbers::get)) { onOpen(item) }
            }
        }
        if (state.error != null) Notice(state.error)
        when {
            state.loadingMore -> Caption("Загружаем ещё…")
            state.nextBeforeId != null -> TextLink("Показать ещё", onMore, centered = true)
        }
    }
}

@Composable
private fun NotificationCard(item: NotificationDto, claimNumber: String?, onClick: () -> Unit) {
    val request = item.type == DocumentsRequired
    val icon: DrawableResource =
        if (request) Res.drawable.alert_notification else Res.drawable.file_notification
    val unreadColor = if (request) InsuranceColors.AmberSoft else InsuranceColors.BlueSoft
    val color = if (item.readAt == null) unreadColor else Color.White
    InsuranceCard(
        background = color,
        border = if (color == Color.White) InsuranceColors.Line else null,
        onClick = onClick,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InsuranceIcon(icon, 24.dp)
            Label(item.title, Modifier.weight(1f))
        }
        val text = item.body.takeIf { it != item.title }
        listOfNotNull(claimNumber, text).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Caption(it)
        }
        Text(
            formatDateTime(item.createdAt).substringAfter(", "),
            style = MaterialTheme.typography.labelSmall,
            color = InsuranceColors.Muted,
        )
    }
}
