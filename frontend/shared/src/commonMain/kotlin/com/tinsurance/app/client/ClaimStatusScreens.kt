package com.tinsurance.app.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

private val sentEvent = TimelineItem("Обращение отправлено", "17 октября, 09:41")
private val checkedEvent = TimelineItem("Материалы проверены", "17 октября, 09:42")
private val requestEvent =
    TimelineItem("Запрошены дополнительные материалы", "17 октября, 12:10", "request")

@Composable
fun SentScreen(claimNumber: String) {
    ScreenBody {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(96.dp).background(InsuranceColors.Yellow, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                InsuranceIcon(Res.drawable.check_success, 56.dp)
            }
        }
        PageTitle("Обращение отправлено", centered = true)
        Text(
            "№ $claimNumber",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            "Проверим материалы и сообщим, если понадобится уточнение.",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = InsuranceColors.Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Timeline(
            listOf(
                sentEvent,
                TimelineItem("Проверяем материалы", status = "current"),
                TimelineItem("Передадим специалисту", status = "waiting"),
            ),
            large = true,
        )
        Notice("История и документы всегда под рукой", Res.drawable.file_notice)
    }
}

@Composable
fun ProgressScreen(
    draft: ClaimDraft,
    replySent: Boolean,
    onMaterials: () -> Unit,
    onDetails: () -> Unit,
    onReply: () -> Unit,
) {
    ScreenBody {
        InsuranceCard(background = InsuranceColors.BlueSoft, border = null, padding = 16.dp) {
            Text(
                if (replySent) "Дополнение получено" else "На рассмотрении",
                style = MaterialTheme.typography.titleMedium,
                color = InsuranceColors.Blue,
            )
            Caption(if (replySent) "Проверяем новые материалы" else "Специалист изучает материалы")
        }
        InsuranceCard {
            InfoRow(
                Res.drawable.car,
                "${draft.car} · ДТП · ${draft.eventDate}",
                "КАСКО ${draft.policy} · ${draft.year} год",
            )
        }
        InsuranceCard(spacing = 12.dp) {
            Label("Ход обработки")
            Timeline(
                buildList {
                    add(sentEvent)
                    add(checkedEvent)
                    add(TimelineItem("Передано в очередь", "17 октября, 09:43"))
                    add(
                        TimelineItem(
                            "Специалист начал рассмотрение",
                            "17 октября, 10:15",
                            "current",
                        )
                    )
                    if (replySent)
                        add(TimelineItem("Дополнительные материалы получены", "17 октября, 12:34"))
                    add(TimelineItem("Обработка завершена", status = "waiting"))
                }
            )
        }
        InfoRow(
            Res.drawable.file,
            "Материалы · 5 файлов",
            trailing = Res.drawable.chevron_small,
            onClick = onMaterials,
        )
        InfoRow(
            Res.drawable.info,
            "Сведения об обращении",
            trailing = Res.drawable.chevron_small,
            onClick = onDetails,
        )
        InsuranceButton("Добавить уточнение", onReply, secondary = true)
    }
}

@Composable
fun RequestScreen() {
    ScreenBody {
        StatusBadge("Нужно уточнение", InsuranceColors.AmberSoft, InsuranceColors.Amber)
        InsuranceCard(padding = 16.dp, spacing = 12.dp) {
            Text("Добавьте два материала", style = MaterialTheme.typography.titleMedium)
            Caption("Запрос специалиста · сегодня, 12:10")
            Text(
                "Для продолжения обработки понадобятся дополнительные материалы.",
                style = MaterialTheme.typography.bodyMedium,
                color = InsuranceColors.Muted,
            )
            InfoRow(
                Res.drawable.camera_amber,
                "Фото передней левой двери",
                "При дневном свете, крупным планом",
                InsuranceColors.Amber,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(InsuranceColors.Line))
            InfoRow(
                Res.drawable.file_amber,
                "Извещение о ДТП",
                "Все страницы, текст читается",
                InsuranceColors.Amber,
            )
        }
        InsuranceCard(spacing = 10.dp) {
            Label("История обращения")
            Timeline(listOf(sentEvent, checkedEvent, requestEvent))
        }
    }
}

@Composable
fun CompletedScreen(onDocument: () -> Unit) {
    ScreenBody(spacing = 10.dp) {
        StatusBadge(
            "Обработка завершена",
            InsuranceColors.GreenSoft,
            InsuranceColors.Green,
            fullWidth = true,
        )
        InsuranceCard(spacing = 8.dp) {
            Text("Результат обработки", style = MaterialTheme.typography.titleMedium)
            InfoRow(
                Res.drawable.check_green,
                "Фото и документы проверены",
                color = InsuranceColors.Green,
            )
            InfoRow(Res.drawable.check_green, "Уточнения учтены", color = InsuranceColors.Green)
            Caption("Завершено 17 октября, 15:20")
        }
        InsuranceCard(background = InsuranceColors.Field, border = null, onClick = onDocument) {
            InfoRow(
                Res.drawable.file,
                "Сводка обращения.pdf",
                "3 страницы · 1,1 МБ",
                trailing = Res.drawable.download,
            )
        }
        InsuranceCard(spacing = 8.dp) {
            Label("История обращения")
            Timeline(
                listOf(
                    sentEvent,
                    checkedEvent,
                    requestEvent,
                    TimelineItem("Дополнительные материалы получены", "17 октября, 12:34"),
                    TimelineItem("Обработка завершена", "17 октября, 15:20"),
                )
            )
        }
        Text(
            "Решение о выплате не входит в демосценарий",
            style = MaterialTheme.typography.labelSmall,
            color = InsuranceColors.Muted,
        )
    }
}
