package com.tinsurance.app.admin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tinsurance.app.operator.OperatorTitle
import com.tinsurance.app.ui.*
import org.jetbrains.compose.resources.painterResource
import tinsurance.shared.generated.resources.*

@Composable
fun AdminOverviewScreen(state: AdminState, onDistribute: () -> Unit, onOpen: (String) -> Unit, onTasks: () -> Unit) {
    val queued = state.claims.count { !it.completed }
    AdminBody(spacing = 12.dp) {
        PageTitle("Обзор обработки · 17 октября")
        if (queued > 40) {
            InsuranceCard(background = InsuranceColors.AmberSoft, border = null, padding = 12.dp) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InsuranceIcon(Res.drawable.admin_alert, 24.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label("Очередь выше порога: $queued из 40", color = InsuranceColors.Amber)
                        Caption("Текущая нагрузка выше выбранного порога. Приоритеты не изменены.")
                    }
                    Box(Modifier.width(252.dp).requiredHeight(48.dp)) {
                        InsuranceButton("Распределить обращения", onDistribute)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Новые" to "86", "В очереди" to "$queued", "До начала" to "18 мин",
                "До завершения" to "2 ч 10 мин", "Нужны уточнения" to "12", "Просрочены" to "8")
                .forEach { (label, value) ->
                    InsuranceCard(Modifier.weight(1f), padding = 16.dp, spacing = 8.dp) {
                        Caption(label)
                        Text(value, style = MaterialTheme.typography.headlineSmall)
                    }
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            InsuranceCard(Modifier.weight(760f), padding = 16.dp, spacing = 8.dp) {
                OperatorTitle("Поступило и обработано")
                Image(painterResource(Res.drawable.admin_workload_chart),
                    "Поступило и обработано за 17 октября: поступления выше числа обработанных обращений",
                    Modifier.fillMaxWidth().aspectRatio(728f / 160f), contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth()) {
                    listOf("08:00", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00").forEach { time ->
                        Text(time, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = InsuranceColors.Muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(80.dp)) {
                    Caption("● Поступило")
                    Caption("● Обработано")
                }
            }
            InsuranceCard(Modifier.weight(408f), padding = 16.dp, spacing = 16.dp, onClick = onDistribute) {
                OperatorTitle("Загрузка операторов")
                state.operators.take(3).forEach { operator -> OperatorLoad(operator) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            InsuranceCard(Modifier.weight(760f), padding = 12.dp, spacing = 6.dp) {
                OperatorTitle("Требуют внимания")
                val widths = listOf(140f, 210f, 210f, 140f)
                AdminTableRow(listOf("Обращение", "Клиент", "Проблема", "Ожидание"), widths,
                    header = true, height = 24.dp)
                listOf("ТС-10403", "ТС-10411", "ТС-10418").forEach { number ->
                    state.claims.find { it.number == number }?.let { claim ->
                        Box {
                            InsuranceCard(border = null, padding = 0.dp, onClick = { onOpen(number) }) {
                                AdminTableRow(listOf(claim.number, claim.client,
                                    if (number == "ТС-10418") "Нужно уточнение" else "Просрочено", claim.waiting), widths, height = 24.dp)
                            }
                        }
                    }
                }
            }
            InsuranceCard(Modifier.weight(408f), background = InsuranceColors.BlueSoft, border = null,
                padding = 12.dp, spacing = 8.dp) {
                OperatorTitle("Анализ фото")
                Text("3 задачи ожидают повтора · файлы сохранены", style = MaterialTheme.typography.bodyMedium)
                Caption("Специалист может продолжить ручную проверку.")
                InsuranceButton("Перейти к задачам", onTasks, secondary = true, compact = true)
            }
        }
    }
}

@Composable
private fun OperatorLoad(operator: TeamOperator) {
    val color = when {
        operator.available == 0 -> InsuranceColors.Red
        operator.active * 2 >= operator.capacity -> InsuranceColors.Yellow
        else -> InsuranceColors.Green
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(operator.name, Modifier.weight(280f), style = MaterialTheme.typography.bodyMedium)
            Label("${operator.active} / ${operator.capacity}", Modifier.weight(84f))
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(InsuranceColors.Field)) {
            Box(Modifier.fillMaxWidth((operator.active.toFloat() / operator.capacity).coerceIn(0f, 1f))
                .fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
        }
        Text(if (operator.available == 0) "Нет свободных мест" else "${operator.available} ${places(operator.available)}",
            style = MaterialTheme.typography.labelSmall, color = InsuranceColors.Muted)
    }
}

private fun places(count: Int) = when {
    count % 100 in 11..14 -> "мест"
    count % 10 == 1 -> "место"
    count % 10 in 2..4 -> "места"
    else -> "мест"
}
