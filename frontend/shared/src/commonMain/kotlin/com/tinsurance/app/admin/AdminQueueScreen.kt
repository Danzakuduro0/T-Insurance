package com.tinsurance.app.admin

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tinsurance.app.operator.OperatorClaim
import com.tinsurance.app.operator.OperatorInput
import com.tinsurance.app.operator.OperatorRule
import com.tinsurance.app.operator.OperatorSelect
import com.tinsurance.app.operator.statusTone
import com.tinsurance.app.operator.OperatorBadge
import com.tinsurance.app.ui.*

@Composable
fun AdminQueueScreen(
    claims: List<OperatorClaim>,
    query: String,
    onQuery: (String) -> Unit,
    initialStatus: String,
    onAssign: (String) -> Unit,
) {
    var status by rememberSaveable(initialStatus) { mutableStateOf(initialStatus) }
    var page by rememberSaveable { mutableStateOf(0) }
    val filtered = claims.filter {
        (query.isBlank() || it.number.contains(query.trim(), true) || it.policy.contains(query.trim(), true)
            || it.client.contains(query.trim(), true)) && (status.isBlank() || it.status == status)
    }
    LaunchedEffect(query, status) { page = 0 }
    val lastPage = (filtered.size - 1).coerceAtLeast(0) / 8
    val currentPage = page.coerceAtMost(lastPage)

    AdminBody {
        PageTitle("Очередь обращений")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OperatorInput(query, onQuery, "Номер, договор или клиент", Modifier.weight(2f))
            OperatorSelect(status, "Статус", claims.map { it.status }, { status = it }, Modifier.weight(1f))
        }
        InsuranceCard(padding = 16.dp, spacing = 8.dp) {
            AdminTableRow(listOf("Обращение", "Клиент", "Статус", "Приоритет", "Исполнитель", "Действие"),
                listOf(144f, 190f, 190f, 130f, 240f, 150f), header = true)
            if (filtered.isEmpty()) Caption("Обращения не найдены. Измените поиск или статус.")
            filtered.drop(currentPage * 8).take(8).forEach { claim ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Label(claim.number, Modifier.weight(144f))
                    Caption(claim.client, Modifier.weight(190f), InsuranceColors.Ink)
                    OperatorBadge(claim.status, Modifier.weight(190f), statusTone(claim.status))
                    Caption(claim.priority, Modifier.weight(130f), InsuranceColors.Ink)
                    Caption(claim.operator ?: "Не назначен", Modifier.weight(240f), InsuranceColors.Ink)
                    Box(Modifier.weight(150f)) {
                        if (!claim.completed) TextLink("Переназначить", { onAssign(claim.number) })
                        else Caption("Завершено")
                    }
                }
                OperatorRule()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Caption("Всего ${filtered.size} · страница ${currentPage + 1} из ${lastPage + 1}", Modifier.weight(1f))
                Box(Modifier.width(120.dp)) {
                    InsuranceButton("Назад", { page = currentPage - 1 }, secondary = true, enabled = currentPage > 0, compact = true)
                }
                Box(Modifier.width(120.dp)) {
                    InsuranceButton("Вперёд", { page = currentPage + 1 }, secondary = true, enabled = currentPage < lastPage, compact = true)
                }
            }
        }
    }
}
