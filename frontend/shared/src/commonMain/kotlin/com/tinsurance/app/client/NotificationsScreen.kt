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
import tinsurance.shared.generated.resources.*

@Composable
fun NotificationsScreen(read: Boolean, onReadAll: () -> Unit, onOpen: (ClientScreen) -> Unit) {
    ScreenBody {
        PageTitle("Уведомления")
        TextLink(if (read) "Все уведомления прочитаны" else "Прочитать все", onReadAll)
        Label("Сегодня")
        NotificationCard(
            "Обработка завершена",
            "ТС-10428 · Открыть сводку обращения",
            "15:20",
            Res.drawable.check_notification,
            if (read) Color.White else InsuranceColors.GreenSoft,
        ) {
            onOpen(ClientScreen.Completed)
        }
        NotificationCard(
            "Дополнение получено",
            "ТС-10428 · Проверяем новые материалы",
            "12:34",
            Res.drawable.file_notification,
            if (read) Color.White else InsuranceColors.BlueSoft,
        ) {
            onOpen(ClientScreen.Progress)
        }
        NotificationCard(
            "Нужны дополнительные материалы",
            "ТС-10428 · Добавьте фото и извещение",
            "12:10",
            Res.drawable.alert_notification,
            if (read) Color.White else InsuranceColors.AmberSoft,
        ) {
            onOpen(ClientScreen.Request)
        }
        Label("Ранее сегодня")
        InsuranceCard(onClick = { onOpen(ClientScreen.Progress) }) {
            InfoRow(Res.drawable.file, "Обращение отправлено", "ТС-10428 зарегистрировано · 09:41")
        }
    }
}

@Composable
private fun NotificationCard(
    title: String,
    subtitle: String,
    time: String,
    icon: DrawableResource,
    color: Color,
    onClick: () -> Unit,
) {
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
            Label(title, Modifier.widthIn(max = 240.dp))
        }
        Caption(subtitle)
        Text(time, style = MaterialTheme.typography.labelSmall, color = InsuranceColors.Muted)
    }
}
