package com.tinsurance.app.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tinsurance.app.operator.OperatorBadge
import com.tinsurance.app.operator.OperatorInput
import com.tinsurance.app.operator.OperatorSelect
import com.tinsurance.app.operator.OperatorTitle
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun AdminRulesScreen(saved: ProcessingRules, onSave: (ProcessingRules) -> String?, onSaved: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var draft by remember(saved) { mutableStateOf(saved) }
    var confidence by rememberSaveable(saved.confidence) { mutableStateOf("${saved.confidence}%") }
    var queueHours by rememberSaveable(saved.queueHours) { mutableStateOf(formatHours(saved.queueHours)) }
    var reviewHours by rememberSaveable(saved.reviewHours) { mutableStateOf(formatHours(saved.reviewHours)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reset() {
        draft = saved
        confidence = "${saved.confidence}%"
        queueHours = formatHours(saved.queueHours)
        reviewHours = formatHours(saved.reviewHours)
        error = null
    }

    AdminBody {
        PageTitle("Правила для ДТП")
        SegmentedTabs(listOf("Документы", "Анализ фото", "Маршруты и сроки"), tab, { tab = it })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(568f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (tab) {
                    0 -> {
                        InsuranceCard(padding = 16.dp, spacing = 8.dp) {
                            OperatorTitle("Обязательные материалы")
                            RequiredMaterials.forEach { material ->
                                val checked = material in draft.materials
                                Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { value ->
                                    draft = draft.copy(materials = if (value) draft.materials + material else draft.materials - material)
                                    error = null
                                }, verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(22.dp)) {
                                        if (checked) InsuranceIcon(Res.drawable.operator_check_green, 22.dp)
                                    }
                                    Label(material, color = if (checked) InsuranceColors.Green else InsuranceColors.Muted)
                                }
                            }
                            Caption("Для регистрации: договор, событие, контакт.\nДля передачи оператору: полный комплект.")
                        }
                        FileLimits()
                    }
                    1 -> AnalysisSettings(draft, confidence, { draft = it; error = null }, { confidence = it; error = null })
                    else -> DeadlineSettings(queueHours, reviewHours, { queueHours = it; error = null }, { reviewHours = it; error = null })
                }
            }
            Column(Modifier.weight(600f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (tab) {
                    0 -> { AnalysisSettings(draft, confidence, { draft = it; error = null }, { confidence = it; error = null }); DeadlineSettings(queueHours, reviewHours, { queueHours = it; error = null }, { reviewHours = it; error = null }) }
                    1 -> FileLimits()
                    else -> AnalysisSettings(draft, confidence, { draft = it; error = null }, { confidence = it; error = null })
                }
            }
        }
        InsuranceCard(padding = 12.dp, spacing = 8.dp) {
            OperatorTitle("Доступные статусы")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = listOf("Черновик", "Отправлено", "Проверка", "Уточнение", "В очереди",
                    "На рассмотрении", "Завершено", "Закрыто без обработки")
                val widths = listOf(112f, 124f, 112f, 112f, 112f, 168f, 124f, 220f)
                labels.zip(widths).forEachIndexed { index, (label, width) ->
                    if (index == 7) DangerBadge(label, Modifier.width(width.dp))
                    else OperatorBadge(label, Modifier.width(width.dp), when (index) {
                        0 -> "neutral"; 3 -> "warning"; 6 -> "success"; else -> "info"
                    })
                }
            }
            Caption("Уточнение — по запросу. После дополнения — повторная проверка.")
        }
        if (error != null) Caption(error!!, color = InsuranceColors.Red)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("Изменения применятся к новым обращениям", Modifier.weight(720f))
            Box(Modifier.weight(180f)) { InsuranceButton("Отмена", ::reset, secondary = true) }
            Box(Modifier.weight(260f)) {
                InsuranceButton("Сохранить правила", {
                    val threshold = confidence.trim().removeSuffix("%").trim().toIntOrNull()
                    val queue = parseHours(queueHours)
                    val review = parseHours(reviewHours)
                    error = when {
                        threshold == null -> "Введите порог уверенности от 0 до 100%"
                        queue == null || review == null -> "Введите срок в целых часах"
                        else -> onSave(draft.copy(confidence = threshold, queueHours = queue, reviewHours = review))
                    }
                    if (error == null) onSaved()
                })
            }
        }
    }
}

@Composable
private fun AnalysisSettings(
    draft: ProcessingRules,
    confidence: String,
    onDraft: (ProcessingRules) -> Unit,
    onConfidence: (String) -> Unit,
) {
    InsuranceCard(padding = 16.dp, spacing = 8.dp) {
        OperatorTitle("Анализ и маршрутизация")
        RuleRow("Порог уверенности") {
            OperatorInput(confidence, onConfidence, "75%", height = 32.dp)
        }
        RuleRow("Ниже порога") {
            OperatorSelect(draft.belowThreshold, "Маршрут", ReviewRoutes,
                { onDraft(draft.copy(belowThreshold = it)) }, allowAll = false, height = 32.dp)
        }
        RuleRow("Неполные материалы") {
            OperatorSelect(draft.incomplete, "Маршрут", IncompleteRoutes,
                { onDraft(draft.copy(incomplete = it)) }, allowAll = false, height = 32.dp)
        }
        RuleRow("После дополнения") {
            OperatorSelect(draft.afterReply, "Маршрут", ReplyRoutes,
                { onDraft(draft.copy(afterReply = it)) }, allowAll = false, height = 32.dp)
        }
        Caption("Оценка модели — подсказка для специалиста.")
    }
}

@Composable
private fun DeadlineSettings(
    queueHours: String,
    reviewHours: String,
    onQueue: (String) -> Unit,
    onReview: (String) -> Unit,
) {
    InsuranceCard(padding = 16.dp, spacing = 8.dp) {
        OperatorTitle("Учебные сроки")
        RuleRow("Очередь") {
            OperatorInput(queueHours, onQueue, "2 часа", height = 32.dp)
        }
        RuleRow("Рассмотрение") {
            OperatorInput(reviewHours, onReview, "24 часа", height = 32.dp)
        }
    }
}

@Composable
private fun RuleRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Caption(label, Modifier.weight(180f))
        Box(Modifier.weight(376f)) { content() }
    }
}

@Composable
private fun FileLimits() {
    InsuranceCard(padding = 16.dp, spacing = 10.dp) {
        OperatorTitle("Ограничения файлов")
        Label("JPEG · PNG · PDF")
        Text("20 МБ на файл · 10 файлов · 100 МБ на обращение", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DangerBadge(text: String, modifier: Modifier) {
    InsuranceCard(modifier, background = InsuranceColors.RedSoft, border = null, padding = 6.dp) {
        Caption(text, Modifier.padding(horizontal = 6.dp), InsuranceColors.Red)
    }
}

private fun formatHours(value: Int): String {
    val word = when {
        value % 100 in 11..14 -> "часов"
        value % 10 == 1 -> "час"
        value % 10 in 2..4 -> "часа"
        else -> "часов"
    }
    return "$value $word"
}
