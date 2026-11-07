package com.tinsurance.app.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinsurance.app.admin.AdminBrand
import com.tinsurance.app.operator.OperatorSelected
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

enum class DemoRole(val title: String, val description: String, val user: String) {
    Client("Клиент", "Подать обращение и следить за статусом", "Анна Смирнова · клиент"),
    Operator("Оператор", "Проверить материалы и запросить уточнение", "Елена Волкова · оператор"),
    Admin("Администратор", "Управлять очередью, командой и правилами", "Старший оператор · администратор"),
}

@Composable
fun DemoLoginScreen(onOpen: (DemoRole) -> Unit) {
    var roleName by rememberSaveable { mutableStateOf(DemoRole.Client.name) }
    val role = DemoRole.valueOf(roleName)
    InsuranceTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
            val width = maxWidth.coerceAtLeast(1280.dp)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(width).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) { AdminBrand() }
                    Spacer(Modifier.height(24.dp))
                    Caption("УЧЕБНЫЙ ПРОЕКТ · ТЕСТОВЫЕ ДАННЫЕ")
                    Text("Т-Страхование", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 32.sp, lineHeight = 40.sp))
                    Text("Сопровождение страхового случая", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.width(1128.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        val icons = listOf(Res.drawable.admin_demo_user, Res.drawable.admin_demo_headset, Res.drawable.admin_demo_settings)
                        DemoRole.entries.forEachIndexed { index, item ->
                            val selected = role == item
                            InsuranceCard(Modifier.weight(1f).height(200.dp)
                                .selectable(selected, role = Role.RadioButton, onClick = { roleName = item.name }),
                                background = if (selected) OperatorSelected else Color.White,
                                border = if (selected) InsuranceColors.Yellow else InsuranceColors.Line,
                                borderWidth = if (selected) 2.dp else 1.dp,
                                padding = 24.dp, spacing = 16.dp) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { InsuranceIcon(icons[index], 40.dp) }
                                Text(item.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                Text(item.description, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                                    color = InsuranceColors.Muted, textAlign = TextAlign.Center,
                                    softWrap = item == DemoRole.Operator, overflow = TextOverflow.Visible)
                            }
                        }
                    }
                    Box(Modifier.width(520.dp)) {
                        InsuranceField("Тестовый пользователь", role.user, {}, readOnly = true)
                    }
                    Box(Modifier.width(520.dp)) { InsuranceButton("Открыть кабинет", { onOpen(role) }) }
                    Caption("Используются только вымышленные обращения")
                }
            }
        }
    }
}
