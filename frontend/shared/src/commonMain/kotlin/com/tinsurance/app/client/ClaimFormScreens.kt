package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun ContractScreen(draft: ClaimDraft, onManualPolicy: () -> Unit) {
    ScreenBody {
        PageTitle("Что случилось?")
        InsuranceCard(border = InsuranceColors.Yellow, padding = 16.dp) {
            InfoRow(Res.drawable.car_small, "ДТП", "Повреждение автомобиля")
        }
        Notice("В демоверсии доступно ДТП")
        Text("Выберите договор", style = MaterialTheme.typography.titleMedium)
        listOf("TEST-0421" to "Kia Rio 2019", "TEST-0783" to "Hyundai Solaris 2020").forEach {
            (number, car) ->
            val selected = draft.policy == number
            InsuranceCard(
                border = if (selected) InsuranceColors.Yellow else InsuranceColors.Line,
                padding = 16.dp,
                onClick = { draft.policy = number },
            ) {
                InfoRow(
                    Res.drawable.shield_small,
                    car,
                    "КАСКО · $number",
                    trailing =
                        if (selected) Res.drawable.check_muted else Res.drawable.chevron_small,
                )
            }
        }
        TextLink("Ввести номер вручную", onManualPolicy, Res.drawable.edit_blue)
        Caption("Черновик сохранён")
    }
}

@Composable
fun EventScreen(draft: ClaimDraft, errors: Map<String, String>, onParticipants: () -> Unit) {
    ScreenBody(spacing = 10.dp) {
        PageTitle("Расскажите о событии")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InsuranceField(
                "Дата события",
                draft.date,
                { draft.date = it },
                Modifier.weight(1f),
                error = errors["date"],
            )
            InsuranceField(
                "Время",
                draft.time,
                { draft.time = it },
                Modifier.weight(1f),
                keyboard = KeyboardType.Ascii,
                error = errors["time"],
            )
        }
        InsuranceField(
            "Место события",
            draft.location,
            { draft.location = it },
            error = errors["location"],
        )
        InsuranceField(
            "Краткое описание",
            draft.description,
            { draft.description = it },
            multiline = true,
            error = errors["description"],
        )
        InsuranceCard(onClick = onParticipants) {
            Label("Участники")
            InfoRow(
                Res.drawable.team_small,
                "${draft.participants} автомобиля · ${if (draft.injured) "есть пострадавшие" else "без пострадавших"}",
                "Уточнить участников",
                trailing = Res.drawable.chevron_small,
            )
        }
        InsuranceField(
            "Электронная почта",
            draft.email,
            { draft.email = it },
            keyboard = KeyboardType.Email,
            error = errors["email"],
        )
        InsuranceCheckbox(
            "Уведомления в приложении",
            draft.notifications,
            { draft.notifications = it },
        )
        Caption("Данные сохраняются в черновике")
    }
}

@Composable
fun ReviewScreen(
    draft: ClaimDraft,
    photoCount: Int,
    documentCount: Int,
    onEdit: (ClientScreen) -> Unit,
) {
    ScreenBody {
        PageTitle("Всё верно?")
        InsuranceCard(onClick = { onEdit(ClientScreen.Event) }) {
            InfoRow(
                Res.drawable.calendar,
                "ДТП · ${draft.eventDate}, ${draft.time}",
                draft.location,
                trailing = Res.drawable.edit_muted,
            )
        }
        InsuranceCard(onClick = { onEdit(ClientScreen.Contract) }) {
            InfoRow(
                Res.drawable.car,
                "${draft.car} · КАСКО ${draft.policy}",
                "${draft.year} год",
                trailing = Res.drawable.edit_muted,
            )
        }
        InsuranceCard(onClick = { onEdit(ClientScreen.Event) }) {
            InfoRow(
                Res.drawable.team,
                "${draft.participants} автомобиля",
                if (draft.injured) "Есть пострадавшие" else "Пострадавших нет",
                trailing = Res.drawable.edit_muted,
            )
        }
        InsuranceCard(onClick = { onEdit(ClientScreen.Materials) }) {
            InfoRow(
                Res.drawable.file,
                "$photoCount фото и $documentCount PDF",
                "Обязательные материалы добавлены",
                trailing = Res.drawable.edit_muted,
            )
        }
        InsuranceCard(onClick = { onEdit(ClientScreen.Event) }) {
            InfoRow(
                Res.drawable.mail,
                "Анна Смирнова",
                draft.email,
                trailing = Res.drawable.edit_muted,
            )
        }
        InsuranceCheckbox(
            "Подтверждаю сведения в тестовом обращении",
            draft.confirmed,
            { draft.confirmed = it },
        )
    }
}
