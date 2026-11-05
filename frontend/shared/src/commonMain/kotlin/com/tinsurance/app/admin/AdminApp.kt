package com.tinsurance.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.tinsurance.app.PlatformBackHandler
import com.tinsurance.app.operator.OperatorBackground
import com.tinsurance.app.ui.*

@Composable
fun AdminApp(initialScreen: AdminScreen = AdminScreen.Overview, onClient: () -> Unit = {}, onDemo: () -> Unit = {}) {
    val state = remember { AdminState() }
    var screenName by rememberSaveable { mutableStateOf(initialScreen.name) }
    var selected by rememberSaveable { mutableStateOf(state.unassigned.map { it.number }) }
    var search by rememberSaveable { mutableStateOf("") }
    var queueStatus by rememberSaveable { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    val screen = AdminScreen.valueOf(screenName)

    fun open(next: AdminScreen) {
        if (next == AdminScreen.Team) selected = state.unassigned.map { it.number }
        if (next == AdminScreen.Queue) queueStatus = ""
        screenName = next.name
    }

    fun distribute(numbers: List<String>) {
        selected = numbers
        screenName = AdminScreen.Team.name
    }

    if (!LocalInspectionMode.current) PlatformBackHandler(screen != AdminScreen.Overview) { open(AdminScreen.Overview) }
    InsuranceTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(OperatorBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val width = maxWidth.coerceAtLeast(1280.dp)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Row(Modifier.width(width).fillMaxHeight()) {
                    AdminSidebar(screen, ::open, onProfile = { info = "profile" })
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        AdminHeader(search, {
                            search = it
                            open(AdminScreen.Queue)
                        }, onNotifications = { info = "notifications" }, onProfile = { info = "profile" })
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            key(screen) {
                                when (screen) {
                                    AdminScreen.Overview -> AdminOverviewScreen(state,
                                        onDistribute = { distribute(state.unassigned.map { it.number }) },
                                        onOpen = { distribute(listOf(it)) }, onTasks = {
                                            open(AdminScreen.Queue)
                                            queueStatus = "Проверка материалов"
                                        })
                                    AdminScreen.Team -> AdminTeamScreen(state, selected,
                                        onAssigned = { info = "Обращения переназначены. Изменения записаны в журнал." })
                                    AdminScreen.Rules -> AdminRulesScreen(state.rules, state::saveRules,
                                        onSaved = { info = "Правила сохранены для новых обращений." })
                                    AdminScreen.Queue -> AdminQueueScreen(state.claims, search, { search = it }, queueStatus,
                                        onAssign = { distribute(listOf(it)) })
                                    AdminScreen.Journal -> AdminJournalScreen(state.events)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (info != null) AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(when (info) { "profile" -> "Профиль администратора"; "notifications" -> "Уведомления"; else -> "Готово" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (info) {
                        "profile" -> {
                            Label(AdminName)
                            Caption("Администратор")
                            TextLink("Выбор роли", onDemo)
                            TextLink("К клиентскому приложению", onClient)
                        }
                        "notifications" -> {
                            if (state.events.isEmpty()) Caption("Новых уведомлений пока нет")
                            else state.events.take(5).forEach { Caption("${it.action} · ${it.subject}", color = InsuranceColors.Ink) }
                            TextLink("Открыть журнал", { info = null; open(AdminScreen.Journal) })
                        }
                        else -> Text(info.orEmpty())
                    }
                }
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Закрыть") } },
        )
    }
}
