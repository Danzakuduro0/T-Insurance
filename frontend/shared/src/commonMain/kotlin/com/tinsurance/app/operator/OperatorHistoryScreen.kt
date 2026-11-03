package com.tinsurance.app.operator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*

@Composable
fun OperatorHistoryScreen(claim: OperatorClaim, events: List<OperatorEvent>) {
    var tab by rememberSaveable(claim.number) { mutableStateOf(0) }
    var expanded by remember(claim.number, events) { mutableStateOf(events.firstOrNull { it.correction != null }) }
    val shown = events.filter { when (tab) { 1 -> !it.notification; 2 -> it.notification; else -> true } }
    OperatorBody {
        OperatorHeading("История обращения", "№ ${claim.number} · ДТП · ${claim.car.substringBefore(" 2019")} · ${claim.policy}")
        SegmentedTabs(listOf("Все события", "Изменения", "Уведомления"), tab, { tab = it })
        InsuranceCard(padding = 16.dp, spacing = 8.dp) {
            HistoryRow(listOf("Время", "Действие", "Было → Стало", "Автор", "Комментарий"), header = true)
            if (shown.isEmpty()) Caption("В этой категории пока нет событий", Modifier.padding(vertical = 24.dp))
            shown.forEachIndexed { index, event ->
                val click = if (event.correction != null) Modifier.clickable(role = Role.Button,
                    onClickLabel = "Подробности исправления") { expanded = if (expanded == event) null else event } else Modifier
                HistoryRow(listOf(event.time, event.action, "${event.before} → ${event.after}", event.author, event.comment), modifier = click)
                if (expanded == event && event.correction != null) {
                    InsuranceCard(background = InsuranceColors.Field, border = null, padding = 16.dp, spacing = 8.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            OperatorValue("Исходный результат", event.originalResult, Modifier.weight(1f))
                            OperatorValue("Новый результат", event.correction.result, Modifier.weight(1f))
                        }
                        Caption("Автор: ${event.author} · ${if (event.time == "Сейчас") "Сейчас" else "17.10.2026, ${event.time}"}", color = InsuranceColors.Ink)
                        Caption("Причина: ${event.correction.reason}", color = InsuranceColors.Ink)
                        Caption("Первоначальная запись сохранена в истории.")
                    }
                }
                if (index != shown.lastIndex) OperatorRule()
            }
        }
        OperatorNotice("Служебный журнал виден только сотрудникам. Клиент получает только публичные события.")
    }
}

@Composable
private fun HistoryRow(values: List<String>, header: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = if (header) 32.dp else 50.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        values.zip(listOf(64f, 196f, 300f, 200f, 344f)).forEach { (value, weight) ->
            Caption(value, Modifier.weight(weight), if (header) InsuranceColors.Muted else InsuranceColors.Ink)
        }
    }
}
