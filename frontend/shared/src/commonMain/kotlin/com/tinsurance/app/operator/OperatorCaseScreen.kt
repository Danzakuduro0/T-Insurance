package com.tinsurance.app.operator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun OperatorCaseScreen(
    claim: OperatorClaim,
    onMaterials: (Int) -> Unit,
    onHistory: () -> Unit,
    onCorrect: () -> Unit,
    onRequest: () -> Unit,
    onComplete: () -> Unit,
    onTake: () -> Unit,
    onDownload: () -> Unit,
) {
    OperatorBody {
        OperatorHeading("ДТП · ${claim.car.substringBefore(" 2019")}",
            "№ ${claim.number} · ${claim.status} · ${claim.priority} приоритет") {
            Box(Modifier.width(216.dp)) {
                InsuranceButton("Запросить материалы", onRequest, secondary = true, enabled = claim.editable)
            }
            Box(Modifier.width(230.dp)) {
                InsuranceButton("Завершить обработку", onComplete, enabled = claim.editable)
            }
        }
        if (!claim.editable) {
            when {
                claim.colleague -> OperatorNotice("Обращение ведёт ${claim.operator}. Доступен только просмотр.")
                claim.completed -> OperatorNotice("Обработка завершена. Материалы и история доступны для просмотра.")
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { OperatorNotice("Чтобы изменить обращение, возьмите его в работу.") }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(180.dp)) { InsuranceButton("Взять в работу", onTake) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            InsuranceCard(Modifier.weight(1f), padding = 16.dp, spacing = 8.dp) {
                OperatorTitle("Событие")
                Label("16 октября 2026, 18:35")
                Caption("Томск, проспект Ленина")
                Text("2 автомобиля · без пострадавших", style = MaterialTheme.typography.bodyMedium)
                Text("Другой автомобиль задел левую сторону. Повреждена передняя левая дверь.", style = MaterialTheme.typography.bodyMedium)
            }
            InsuranceCard(Modifier.weight(1f), padding = 16.dp, spacing = 8.dp) {
                OperatorTitle("Полис")
                Label("КАСКО ${claim.policy}")
                Caption("Действует до 31.12.2026")
                OperatorValue("Автомобиль", claim.car.replace(" 2019", ", 2019 год"))
                OperatorValue("Страхователь", claim.client)
            }
            InsuranceCard(Modifier.weight(1f), padding = 16.dp, spacing = 8.dp) {
                OperatorTitle("Клиент")
                Label(claim.client)
                Caption("Тестовый клиент")
                OperatorValue("Контакт для уведомлений", "anna@example.test")
                OperatorValue("Предпочтительный канал", "Внутри приложения")
            }
        }
        SegmentedTabs(listOf("Обзор", "Материалы 5", "Анализ фото", "История"), 0) {
            when (it) {
                1, 2 -> onMaterials(0)
                3 -> onHistory()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            InsuranceCard(Modifier.weight(760f), padding = 16.dp, spacing = 10.dp) {
                OperatorTitle("Проверка материалов")
                listOf("Фото общего вида · 1 файл", "Фото повреждения · 3 файла", "Извещение о ДТП · 1 файл").forEach { title ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsuranceIcon(Res.drawable.operator_check_green, 22.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Label(title, color = InsuranceColors.Green)
                            Caption("Принято")
                        }
                    }
                }
                OperatorNotice("Модель не уверена. Требуется ручная проверка.", warning = true)
            }
            InsuranceCard(Modifier.weight(408f), padding = 16.dp, spacing = 8.dp) {
                OperatorTitle("Рабочий процесс")
                OperatorValue("Направление", claim.direction)
                OperatorValue("Приоритет", claim.priority)
                OperatorValue("Исполнитель", claim.operator ?: "Не назначен")
                if (claim.editable) TextLink("Изменить с причиной", onCorrect, Res.drawable.operator_edit)
            }
        }
        InsuranceCard(padding = 12.dp, spacing = 8.dp) {
            Label("Материалы дела · 5 файлов")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { index ->
                    OperatorPhoto(Modifier.weight(188f).height(76.dp)
                        .clickable(role = Role.Button, onClickLabel = "Открыть фото ${index + 1}") { onMaterials(index) },
                        image = if (index == 2) Res.drawable.operator_photo_dark else Res.drawable.operator_photo_preview)
                }
                InsuranceCard(Modifier.weight(344f), background = InsuranceColors.Field, border = null, padding = 12.dp,
                    onClick = onDownload) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsuranceIcon(Res.drawable.operator_file, 22.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Label("Извещение.pdf")
                            Caption("PDF · 3 страницы")
                        }
                        OperatorIconButton(Res.drawable.operator_download_muted, "Скачать извещение", onDownload, 18.dp)
                    }
                }
            }
        }
    }
}
