package com.tinsurance.app.operator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun OperatorQueueScreen(
    claims: List<OperatorClaim>,
    search: String,
    mine: Boolean,
    onOpen: (String) -> Unit,
    onTake: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf("") }
    var eventType by rememberSaveable { mutableStateOf("") }
    var operator by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable(mine) { mutableStateOf(if (mine) 1 else 0) }
    var page by rememberSaveable { mutableStateOf(0) }
    val filter = QueueFilter(query, status, priority, period, eventType, operator, tab)
    val filtered = filterClaims(filterClaims(claims, QueueFilter(query = search)), filter)
    LaunchedEffect(filter, search, claims) { page = 0 }
    val lastPage = ((filtered.size - 1).coerceAtLeast(0) / 5)
    val currentPage = page.coerceAtMost(lastPage)
    val visible = filtered.drop(currentPage * 5).take(5)
    val next = claims.firstOrNull { it.operator == null && !it.completed }

    OperatorBody {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(690f)) { PageTitle(if (mine) "Мои обращения" else "Очередь обращений") }
            Box(Modifier.weight(216f)) {
                InsuranceButton("Взять следующее", { next?.let { onTake(it.number) } }, enabled = next != null)
            }
            Spacer(Modifier.weight(254f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(
                "В очереди" to claims.count { !it.completed },
                "Требуют внимания" to claims.count { it.priority != "Обычный" && !it.completed },
                "Мои в работе" to claims.count { it.editable },
            ).forEach { (label, count) ->
                InsuranceCard(Modifier.weight(1f), padding = 16.dp, spacing = 8.dp) {
                    Caption(label)
                    Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OperatorInput(query, { query = it }, "Номер обращения", Modifier.weight(3f))
            OperatorSelect(status, "Статус", claims.map { it.status }, { status = it }, Modifier.weight(1.5f))
            OperatorSelect(priority, "Приоритет", listOf("Обычный", "Повышенный", "Высокий"),
                { priority = it }, Modifier.weight(1.4f))
            OperatorSelect(period, "Период", listOf("Сегодня", "Последние 7 дней", "Всё время"),
                { period = it }, Modifier.weight(1.4f))
            OperatorSelect(eventType, "Тип случая", listOf("ДТП"), { eventType = it }, Modifier.weight(1.6f))
            OperatorSelect(operator, "Сотрудник", claims.map { it.operator ?: "Не назначен" },
                { operator = it }, Modifier.weight(2.34f))
        }
        SegmentedTabs(
            listOf("Все ${claims.size}", "Мои ${claims.count { it.operator == OperatorName }}",
                "Без исполнителя ${claims.count { it.operator == null }}"),
            tab, { tab = it },
        )
        InsuranceCard(padding = 16.dp, spacing = 6.dp) {
            Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("Обращение", "Событие / договор", "Статус", "Приоритет", "Ожидание", "Исполнитель")
                    .zip(listOf(144f, 260f, 190f, 140f, 110f, 248f)).forEach { (label, weight) ->
                        Caption(label, Modifier.weight(weight))
                    }
            }
            if (visible.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Обращения не найдены")
                    Caption("Измените номер обращения или выбранные фильтры.")
                }
            }
            visible.forEachIndexed { index, claim ->
                QueueClaimRow(claim, onOpen, onTake)
                if (index != visible.lastIndex) OperatorRule()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Всего ${filtered.size} обращений", Modifier.width(250.dp))
                val pages = listOf(0, 1, 2, currentPage - 1, currentPage, currentPage + 1, lastPage)
                    .filter { it in 0..lastPage }.distinct().sorted()
                pages.forEachIndexed { index, number ->
                    if (index > 0 && number - pages[index - 1] > 1) Caption("…")
                    Box(Modifier.width(20.dp).height(16.dp)
                        .clickable(role = Role.Button, onClickLabel = "Страница ${number + 1}") { page = number },
                        contentAlignment = Alignment.Center) {
                        Caption("${number + 1}", color = if (number == currentPage) InsuranceColors.Ink else InsuranceColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueClaimRow(claim: OperatorClaim, onOpen: (String) -> Unit, onTake: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .background(if (claim.number == "ТС-10428") OperatorSelected else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Открыть ${claim.number}") { onOpen(claim.number) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(claim.number, Modifier.weight(144f), style = MaterialTheme.typography.labelLarge)
        Caption("ДТП · ${claim.car}\nКАСКО ${claim.policy}", Modifier.weight(260f), InsuranceColors.Ink)
        OperatorBadge(claim.status, Modifier.weight(190f), statusTone(claim.status))
        OperatorBadge(claim.priority, Modifier.weight(140f), if (claim.priority == "Обычный") "neutral" else "warning")
        Caption(claim.waiting, Modifier.weight(110f),
            if (claim.priority == "Высокий") InsuranceColors.Red else InsuranceColors.Muted)
        Box(Modifier.weight(248f)) {
            when {
                claim.colleague && claim.status == "В работе у коллеги" -> Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InsuranceIcon(Res.drawable.operator_lock, 16.dp)
                    Caption("Только просмотр")
                }
                claim.operator == null && claim.status == "В очереди" ->
                    TextLink("Взять в работу", { onTake(claim.number) })
                else -> Text(claim.operator ?: "Не назначен", style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
