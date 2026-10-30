package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun ClientDialog(type: String, draft: ClaimDraft, onDismiss: () -> Unit) {
    var policy by remember { mutableStateOf(draft.policy) }
    var participants by remember { mutableStateOf(draft.participants) }
    var injured by remember { mutableStateOf(draft.injured) }
    var error by remember { mutableStateOf<String?>(null) }
    val title =
        when (type) {
            "policy" -> "Номер договора"
            "participants" -> "Участники события"
            "policies" -> "Мои полисы"
            "profile" -> "Профиль"
            "photo" -> "Фото повреждения"
            "materials" -> "Материалы обращения"
            else -> "Сведения об обращении"
        }
    fun save() {
        when (type) {
            "policy" -> {
                if (policy.isBlank()) {
                    error = "Введите номер договора"
                    return
                }
                draft.policy = policy.trim()
            }
            "participants" -> {
                if ((participants.toIntOrNull() ?: 0) !in 1..20) {
                    error = "Укажите от 1 до 20 автомобилей"
                    return
                }
                draft.participants = participants
                draft.injured = injured
            }
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (type) {
                    "policy" ->
                        InsuranceField(
                            "Номер договора",
                            policy,
                            {
                                policy = it
                                error = null
                            },
                            error = error,
                        )
                    "participants" -> {
                        InsuranceField(
                            "Количество автомобилей",
                            participants,
                            {
                                participants = it
                                error = null
                            },
                            keyboard = KeyboardType.Number,
                            error = error,
                        )
                        InsuranceCheckbox("Есть пострадавшие", injured, { injured = it })
                    }
                    "policies" -> {
                        InfoRow(
                            Res.drawable.shield_small,
                            "Kia Rio 2019",
                            "КАСКО · TEST-0421 · до 31.12.2026",
                        )
                        InfoRow(
                            Res.drawable.shield_small,
                            "Hyundai Solaris 2020",
                            "КАСКО · TEST-0783 · до 31.12.2026",
                        )
                    }
                    "profile" -> {
                        Label("Анна Смирнова")
                        Caption(draft.email)
                        InsuranceCheckbox(
                            "Уведомления в приложении",
                            draft.notifications,
                            { draft.notifications = it },
                        )
                    }
                    "photo" -> DamagePhoto(modifier = Modifier.fillMaxWidth().height(220.dp))
                    "materials" -> {
                        DamagePhoto(modifier = Modifier.fillMaxWidth().height(140.dp))
                        Label("4 фото и 1 PDF")
                        Caption("Извещение_ДТП.pdf")
                    }
                    else -> {
                        Label("${draft.car} · КАСКО ${draft.policy}")
                        Caption("${draft.date}, ${draft.time}")
                        Caption(draft.location)
                        Text(draft.description)
                        Caption(draft.email)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save) {
                Text(if (type in listOf("policy", "participants")) "Сохранить" else "Закрыть")
            }
        },
        dismissButton = {
            if (type in listOf("policy", "participants"))
                TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
