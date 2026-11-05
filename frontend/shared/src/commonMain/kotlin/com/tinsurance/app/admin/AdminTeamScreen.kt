package com.tinsurance.app.admin

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tinsurance.app.operator.OperatorRule
import com.tinsurance.app.operator.OperatorSelect
import com.tinsurance.app.operator.OperatorTitle
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun AdminTeamScreen(state: AdminState, initialSelection: List<String>, onAssigned: () -> Unit) {
    var selected by rememberSaveable(initialSelection) { mutableStateOf(initialSelection) }
    var operatorName by rememberSaveable { mutableStateOf("Мария Орлова") }
    var priority by rememberSaveable { mutableStateOf(KeepPriority) }
    var reason by rememberSaveable { mutableStateOf("Перераспределение очереди при высокой нагрузке.") }
    var error by remember { mutableStateOf<String?>(null) }
    val candidates = state.claims.filter { claim -> !claim.completed && (claim.operator == null || claim.number in selected) }
    val target = state.operators.find { it.name == operatorName }
    val widths = listOf(180f, 100f, 158f, 80f, 52f)

    AdminBody {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PageTitle("Команда и распределение")
            Caption("Сотрудники, загрузка и распределение обращений")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InsuranceCard(Modifier.weight(650f), padding = 16.dp, spacing = 12.dp) {
                OperatorTitle("Список операторов")
                AdminTableRow(listOf("Оператор", "Статус", "Специализация", "В работе", "Очередь"), widths, header = true)
                state.operators.forEach { operator ->
                    AdminTableRow(listOf(operator.name, operator.status, operator.specialization,
                        "${operator.active} / ${operator.capacity}", if (operator.queued == 0) "—" else "${operator.queued}"), widths)
                    OperatorRule()
                }
            }
            Column(Modifier.weight(518f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InsuranceCard(padding = 16.dp, spacing = 12.dp) {
                    OperatorTitle(if (candidates.any { it.operator != null }) "Выбранные обращения" else "Нераспределённые обращения")
                    if (candidates.isEmpty()) Caption("Все обращения уже распределены")
                    candidates.forEach { claim ->
                        InsuranceCheckbox("${claim.number} · ${claim.client}", claim.number in selected, Res.drawable.operator_check) { checked ->
                            selected = if (checked) (selected + claim.number).distinct() else selected - claim.number
                            error = null
                        }
                    }
                    Caption("Выбрано ${selected.size} ${claimWord(selected.size)} · ${if (priority == KeepPriority) "приоритеты сохранятся" else "приоритет: $priority"}")
                }
                InsuranceCard(padding = 16.dp, spacing = 16.dp) {
                    OperatorTitle("Переназначить ${selected.size} ${claimWord(selected.size)}")
                    OperatorSelect(target?.label.orEmpty(), "Выберите исполнителя", state.operators.map { it.label },
                        { label -> operatorName = state.operators.first { it.label == label }.name; error = null },
                        allowAll = false, label = "Новый исполнитель")
                    OperatorSelect(priority, KeepPriority, listOf(KeepPriority, "Обычный", "Повышенный", "Высокий"),
                        { priority = it; error = null }, allowAll = false, label = "Приоритет обращений")
                    InsuranceField("Причина переназначения *", reason, { reason = it; error = null },
                        multiline = true, inputMinHeight = 90.dp)
                    AdminNotice("Автор и причина попадут в журнал.")
                    if (error != null) Caption(error!!, color = InsuranceColors.Red)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(152f)) {
                            InsuranceButton("Отмена", {
                                selected = emptyList()
                                operatorName = "Мария Орлова"
                                priority = KeepPriority
                                reason = ""
                                error = null
                            }, secondary = true)
                        }
                        Box(Modifier.weight(322f)) {
                            InsuranceButton("Переназначить", {
                                error = state.reassign(selected, operatorName, priority, reason)
                                if (error == null) {
                                    selected = emptyList()
                                    onAssigned()
                                }
                            }, enabled = selected.isNotEmpty())
                        }
                    }
                }
            }
        }
    }
}

private fun claimWord(count: Int) = when {
    count % 100 in 11..14 -> "обращений"
    count % 10 == 1 -> "обращение"
    count % 10 in 2..4 -> "обращения"
    else -> "обращений"
}
