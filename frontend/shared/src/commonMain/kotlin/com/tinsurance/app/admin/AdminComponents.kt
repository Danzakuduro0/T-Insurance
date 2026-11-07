package com.tinsurance.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tinsurance.app.operator.OperatorIconButton
import com.tinsurance.app.operator.OperatorInput
import com.tinsurance.app.operator.OperatorSelected
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
internal fun AdminBody(spacing: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

@Composable
internal fun AdminBrand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(32.dp).background(InsuranceColors.Yellow, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) { Text("Т", style = MaterialTheme.typography.titleMedium) }
        Label("Страхование")
    }
}

@Composable
private fun AdminAvatar() {
    Box(Modifier.size(32.dp).background(InsuranceColors.Field, CircleShape), contentAlignment = Alignment.Center) {
        Caption("СО", color = InsuranceColors.Ink)
    }
}

@Composable
internal fun AdminSidebar(screen: AdminScreen, onOpen: (AdminScreen) -> Unit, onProfile: () -> Unit) {
    Column(Modifier.width(208.dp).fillMaxHeight().background(Color.White).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AdminBrand()
        Spacer(Modifier.height(12.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val icons = listOf(Res.drawable.admin_chart, Res.drawable.operator_queue, Res.drawable.admin_team,
                Res.drawable.admin_settings, Res.drawable.operator_file)
            listOf("Обзор", "Очередь", "Команда", "Правила", "Журнал").forEachIndexed { index, label ->
                val next = AdminScreen.entries[index]
                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (screen == next) OperatorSelected else Color.Transparent)
                    .selectable(screen == next, role = Role.Tab, onClick = { onOpen(next) }).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InsuranceIcon(icons[index], 20.dp)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClickLabel = "Профиль администратора", onClick = onProfile),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminAvatar()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption(AdminName, color = InsuranceColors.Ink)
                Text("Администратор", style = MaterialTheme.typography.labelSmall, color = InsuranceColors.Muted)
            }
        }
    }
}

@Composable
internal fun AdminHeader(search: String, onSearch: (String) -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(Color.White).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OperatorInput(search, onSearch, "Номер обращения или договора", Modifier.weight(650f),
            Res.drawable.operator_search, compact = true)
        Spacer(Modifier.weight(280f))
        OperatorIconButton(Res.drawable.operator_bell, "Уведомления", onNotifications, 24.dp)
        Box(Modifier.clickable(role = Role.Button, onClickLabel = "Профиль", onClick = onProfile)) { AdminAvatar() }
        Caption(AdminName, Modifier.width(150.dp), InsuranceColors.Ink)
    }
}

@Composable
internal fun AdminTableRow(
    values: List<String>,
    widths: List<Float>,
    modifier: Modifier = Modifier,
    header: Boolean = false,
    height: Dp = if (header) 32.dp else 64.dp,
) {
    Row(modifier.fillMaxWidth().heightIn(min = height), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        values.zip(widths).forEach { (value, width) ->
            Caption(value, Modifier.weight(width), if (header) InsuranceColors.Muted else InsuranceColors.Ink)
        }
    }
}

@Composable
internal fun AdminNotice(text: String) {
    InsuranceCard(background = InsuranceColors.BlueSoft, border = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InsuranceIcon(Res.drawable.operator_info, 20.dp)
            Caption(text, Modifier.weight(1f), InsuranceColors.Blue)
        }
    }
}
