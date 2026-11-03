package com.tinsurance.app.operator

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun OperatorCorrectionDialog(
    claim: OperatorClaim,
    photo: Int,
    current: PhotoCorrection?,
    onDismiss: () -> Unit,
    onSave: (PhotoCorrection) -> Unit,
) {
    var result by rememberSaveable(claim.number, photo) { mutableStateOf(current?.result ?: "Вмятина") }
    var direction by rememberSaveable(claim.number, photo) { mutableStateOf(current?.direction ?: claim.direction) }
    var priority by rememberSaveable(claim.number, photo) { mutableStateOf(current?.priority ?: claim.priority) }
    var reason by rememberSaveable(claim.number, photo) { mutableStateOf(current?.reason ?: DefaultCorrectionReason) }
    OperatorModal(onDismiss) {
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }, contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight().background(Color.White)
                    .pointerInput(Unit) { detectTapGestures {} }.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Исправить результат", style = MaterialTheme.typography.headlineSmall)
                    Caption("${claim.number} · Фото ${photo + 1}")
                    InsuranceCard(background = InsuranceColors.Field, border = null, padding = 16.dp, spacing = 8.dp) {
                        Caption("Автоматический результат")
                        Label(if (photo == 2) "Анализ пока недоступен" else "Царапина · уверенность 64%")
                        Caption(if (photo == 2) "Исходный файл сохранён без результата анализа" else "Определено моделью 17.10.2026 в 10:14")
                    }
                    OperatorSelect(result, "Результат специалиста", listOf("Царапина", "Вмятина", "Трещина", "Скол", "Нет повреждения"),
                        { result = it }, Modifier.fillMaxWidth(), allowAll = false, label = "Результат специалиста *")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OperatorSelect(direction, "Направление", listOf("Ручная проверка", "Ремонт", "Оценка ущерба"),
                            { direction = it }, Modifier.weight(1f), allowAll = false, label = "Направление")
                        OperatorSelect(priority, "Приоритет", listOf("Обычный", "Повышенный", "Высокий"),
                            { priority = it }, Modifier.weight(1f), allowAll = false, label = "Приоритет")
                    }
                    InsuranceField("Причина изменения *", reason, { reason = it }, multiline = true,
                        inputMinHeight = 104.dp, error = if (reason.isBlank()) "Укажите причину изменения" else null)
                    Caption("Сравнение результатов")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OperatorBadge(current?.result ?: if (photo == 2) "Не определено" else "Царапина", Modifier.weight(185f))
                        InsuranceIcon(Res.drawable.operator_arrow, 24.dp)
                        OperatorBadge(result, Modifier.weight(199f), "warning")
                    }
                    OperatorNotice("Исходный результат сохранится в истории вместе с автором и причиной.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(144f)) { InsuranceButton("Отмена", onDismiss, secondary = true) }
                    Box(Modifier.weight(276f)) {
                        InsuranceButton("Сохранить исправление", { onSave(PhotoCorrection(result, direction, priority, reason)) },
                            enabled = claim.editable && reason.isNotBlank() && result.isNotBlank())
                    }
                }
            }
        }
    }
}

@Composable
fun OperatorRequestDialog(claim: OperatorClaim, onDismiss: () -> Unit, onSend: (MaterialRequest) -> Unit) {
    var photo by rememberSaveable(claim.number) { mutableStateOf(true) }
    var notice by rememberSaveable(claim.number) { mutableStateOf(true) }
    var message by rememberSaveable(claim.number) {
        mutableStateOf("Здравствуйте, Анна! Пришлите, пожалуйста, фото двери при дневном свете и все страницы извещения о ДТП.")
    }
    var reason by rememberSaveable(claim.number) { mutableStateOf("Недостаточная освещённость фото, отсутствует извещение") }
    val request = MaterialRequest(photo, notice, message, reason)
    OperatorModal(onDismiss) {
        Box(Modifier.fillMaxSize().padding(16.dp).pointerInput(onDismiss) { detectTapGestures { onDismiss() } }, contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 700.dp).fillMaxHeight()
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .pointerInput(Unit) { detectTapGestures {} }.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Запросить материалы", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    OperatorIconButton(Res.drawable.operator_close, "Закрыть запрос", onDismiss)
                }
                Row(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(500f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Label("Какие материалы запросить")
                        InsuranceCheckbox("Новое фото передней левой двери", photo, Res.drawable.operator_check) { photo = it }
                        InsuranceCheckbox("Извещение о ДТП", notice, Res.drawable.operator_check) { notice = it }
                        Caption("Снимите дверь крупно при дневном свете. Загрузите все страницы извещения.")
                        InsuranceField("Сообщение клиенту", message, { message = it }, multiline = true,
                            error = if (message.isBlank()) "Добавьте сообщение клиенту" else null)
                        InsuranceField("Причина запроса *", reason, { reason = it },
                            error = if (reason.isBlank()) "Укажите причину запроса" else null)
                        Caption("Канал: уведомление внутри приложения")
                        if (!photo && !notice) Caption("Выберите хотя бы один материал", color = InsuranceColors.Red)
                    }
                    InsuranceCard(Modifier.weight(328f), background = InsuranceColors.Field, border = null, padding = 16.dp, spacing = 16.dp) {
                        Label("Клиент увидит")
                        OperatorBadge("Нужно уточнение", Modifier.fillMaxWidth(), "warning")
                        OperatorTitle(when { photo && notice -> "Добавьте два материала"; photo || notice -> "Добавьте материал"; else -> "Выберите материалы" })
                        if (photo) InfoRow(Res.drawable.operator_camera, "Новое фото двери", "При дневном свете, крупным планом")
                        if (notice) InfoRow(Res.drawable.operator_file, "Извещение о ДТП", "Все страницы, текст читается")
                        OperatorNotice("Вы получите уведомление, когда клиент отправит материалы.")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(180.dp)) { InsuranceButton("Отмена", onDismiss, secondary = true) }
                    Box(Modifier.width(230.dp)) { InsuranceButton("Отправить запрос", { onSend(request) }, enabled = claim.editable && request.valid) }
                }
                Caption("Статус изменится на «Нужно уточнение»")
            }
        }
    }
}

@Composable
private fun OperatorModal(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)).semantics { dialog() }) {
            content()
        }
    }
}
