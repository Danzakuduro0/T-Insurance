package com.tinsurance.app.admin

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tinsurance.app.operator.OperatorTitle
import com.tinsurance.app.ui.*

@Composable
fun AdminJournalScreen(events: List<AdminEvent>) {
    AdminBody {
        PageTitle("Журнал действий")
        if (events.isEmpty()) {
            InsuranceCard(padding = 16.dp, spacing = 8.dp) {
                OperatorTitle("Действий пока нет")
                Caption("Здесь появятся переназначения обращений и изменения правил с автором и причиной.")
            }
        }
        events.forEach { event ->
            InsuranceCard(padding = 16.dp, spacing = 12.dp) {
                OperatorTitle("${event.action} · ${event.subject}")
                Caption("Сейчас · ${event.author}")
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Caption("До")
                        Label(event.before)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Caption("После")
                        Label(event.after)
                    }
                }
                Caption(event.reason, color = InsuranceColors.Ink)
            }
        }
    }
}
