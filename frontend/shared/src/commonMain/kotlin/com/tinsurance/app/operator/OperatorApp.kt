package com.tinsurance.app.operator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tinsurance.app.PlatformBackHandler
import com.tinsurance.app.rememberPdfDownload
import com.tinsurance.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import tinsurance.shared.generated.resources.*

@OptIn(ExperimentalResourceApi::class)
@Composable
fun OperatorApp(initialScreen: OperatorScreen = OperatorScreen.Queue, onClient: () -> Unit = {}, onDemo: (() -> Unit)? = null) {
    val state = remember { OperatorState(historyPreview = initialScreen == OperatorScreen.History) }
    var screenName by rememberSaveable { mutableStateOf(when (initialScreen) {
        OperatorScreen.Correction -> OperatorScreen.Materials.name
        OperatorScreen.Request -> OperatorScreen.Case.name
        else -> initialScreen.name
    }) }
    var overlay by rememberSaveable { mutableStateOf(when (initialScreen) {
        OperatorScreen.Correction -> "correction"
        OperatorScreen.Request -> "request"
        else -> null
    }) }
    var number by rememberSaveable { mutableStateOf("ТС-10428") }
    var photo by rememberSaveable { mutableStateOf(0) }
    var mine by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var completing by remember { mutableStateOf(false) }
    var completionComment by rememberSaveable(number) { mutableStateOf("") }
    val screen = OperatorScreen.valueOf(screenName)
    val claim = state.claim(number)
    val scope = rememberCoroutineScope()
    val inspection = LocalInspectionMode.current
    val download: (String, ByteArray) -> Unit = if (inspection) ({ _, _ -> }) else rememberPdfDownload { info = it }

    fun open(next: OperatorScreen) {
        overlay = null
        screenName = next.name
    }
    fun back() {
        when {
            overlay != null -> overlay = null
            screen == OperatorScreen.Materials || screen == OperatorScreen.History -> open(OperatorScreen.Case)
            else -> open(OperatorScreen.Queue)
        }
    }
    fun take(claimNumber: String) {
        if (state.take(claimNumber)) {
            number = claimNumber
            photo = 0
            open(OperatorScreen.Case)
        }
    }
    fun saveFile(index: Int) {
        scope.launch {
            try {
                val resource = if (index == 4) "files/accident_notice.pdf" else "drawable/operator_photo_door.png"
                val filename = if (index == 4) "Извещение_ДТП.pdf" else "${claim.number}_Фото_${index + 1}.png"
                download(filename, Res.readBytes(resource))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { info = "Не удалось скачать файл. Попробуйте ещё раз." }
        }
    }

    if (!inspection) PlatformBackHandler(overlay != null || screen != OperatorScreen.Queue, ::back)
    InsuranceTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(OperatorBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            // На телефоне сохраняем читаемые колонки рабочего стола и даём горизонтальную прокрутку.
            val workspaceWidth = maxWidth.coerceAtLeast(1280.dp)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Row(Modifier.width(workspaceWidth).fillMaxHeight()) {
                    OperatorSidebar(mine, onQueue = { mine = false; open(OperatorScreen.Queue) },
                        onMine = { mine = true; open(OperatorScreen.Queue) },
                        onNotifications = { info = "notifications" }, onHelp = { info = "help" }, onProfile = { info = "profile" })
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        OperatorHeader(search, {
                            search = it
                            if (it.isNotBlank()) open(OperatorScreen.Queue)
                        }, onNotifications = { info = "notifications" }, onProfile = { info = "profile" })
                        Box(Modifier.weight(1f)) {
                            when (screen) {
                                OperatorScreen.Queue -> OperatorQueueScreen(state.claims, search, mine,
                                    onOpen = { number = it; photo = 0; open(OperatorScreen.Case) }, onTake = ::take)
                                OperatorScreen.Case -> OperatorCaseScreen(claim,
                                    onMaterials = { photo = it; open(OperatorScreen.Materials) },
                                    onHistory = { open(OperatorScreen.History) },
                                    onCorrect = { photo = 0; open(OperatorScreen.Materials); overlay = "correction" },
                                    onRequest = { overlay = "request" }, onComplete = { completing = true },
                                    onTake = { take(number) }, onDownload = { saveFile(4) })
                                OperatorScreen.Materials -> OperatorMaterialsScreen(claim, photo, state.correction(number, photo),
                                    onSelect = { photo = it }, onCorrect = { overlay = "correction" },
                                    onRequest = { overlay = "request" }, onDownload = ::saveFile)
                                OperatorScreen.History -> OperatorHistoryScreen(claim, state.history(number))
                                OperatorScreen.Correction, OperatorScreen.Request -> Unit
                            }
                        }
                    }
                }
            }
        }
        if (overlay == "correction") OperatorCorrectionDialog(claim, photo, state.correction(number, photo),
            onDismiss = { overlay = null }, onSave = {
                if (state.saveCorrection(number, photo, it)) overlay = null
            })
        if (overlay == "request") OperatorRequestDialog(claim, onDismiss = { overlay = null }, onSend = {
            if (state.requestMaterials(number, it)) overlay = null
        })
        if (completing) AlertDialog(
            onDismissRequest = { completing = false }, title = { Text("Завершить обработку") },
            text = { InsuranceField("Результат проверки *", completionComment, { completionComment = it }, multiline = true) },
            confirmButton = { TextButton(enabled = completionComment.isNotBlank() && claim.editable, onClick = {
                if (state.complete(number, completionComment)) { completing = false; open(OperatorScreen.History) }
            }) { Text("Завершить") } },
            dismissButton = { TextButton(onClick = { completing = false }) { Text("Отмена") } },
        )
        if (info != null) AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(when (info) { "profile" -> "Профиль оператора"; "notifications" -> "Уведомления"; "help" -> "Помощь"; else -> "Материалы" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (info) {
                        "profile" -> {
                            Label(OperatorName)
                            Caption("Оператор")
                            TextLink("К клиентскому приложению", onClient)
                            if (onDemo != null) TextLink("Выбор роли", onDemo)
                        }
                        "notifications" -> {
                            val notifications = state.events.values.flatten().filter { it.notification }
                            if (notifications.isEmpty()) Caption("Новых уведомлений пока нет")
                            else notifications.forEach { Caption(it.comment, color = InsuranceColors.Ink) }
                        }
                        "help" -> Text("Выберите обращение в очереди, проверьте материалы и результат анализа. Исправления и запросы сохраняются в истории обращения.")
                        else -> Text(info.orEmpty())
                    }
                }
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun OperatorSidebar(
    mine: Boolean,
    onQueue: () -> Unit,
    onMine: () -> Unit,
    onNotifications: () -> Unit,
    onHelp: () -> Unit,
    onProfile: () -> Unit,
) {
    Column(Modifier.width(208.dp).fillMaxHeight().background(Color.White).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).background(InsuranceColors.Yellow, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text("Т", style = MaterialTheme.typography.titleMedium)
            }
            Label("Страхование")
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.selectableGroup()) {
            val icons = listOf(Res.drawable.operator_queue, Res.drawable.operator_file, Res.drawable.operator_bell, Res.drawable.operator_help)
            val actions = listOf(onQueue, onMine, onNotifications, onHelp)
            listOf("Очередь", "Мои обращения", "Уведомления", "Помощь").forEachIndexed { index, label ->
                val selected = index == if (mine) 1 else 0
                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) OperatorSelected else Color.Transparent)
                    .selectable(selected, role = Role.Tab, onClick = actions[index]).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InsuranceIcon(icons[index], 20.dp)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClickLabel = "Профиль оператора", onClick = onProfile),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OperatorAvatar()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption(OperatorName, color = InsuranceColors.Ink)
                Text("Оператор", style = MaterialTheme.typography.labelSmall, color = InsuranceColors.Muted)
            }
        }
    }
}

@Composable
private fun OperatorHeader(search: String, onSearch: (String) -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(Color.White).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OperatorInput(search, onSearch, "Номер обращения или договора", Modifier.weight(650f), Res.drawable.operator_search, compact = true)
        Spacer(Modifier.weight(280f))
        OperatorIconButton(Res.drawable.operator_bell, "Уведомления", onNotifications, 22.dp)
        Box(Modifier.clickable(role = Role.Button, onClickLabel = "Профиль", onClick = onProfile)) { OperatorAvatar() }
        Caption(OperatorName, Modifier.width(150.dp), InsuranceColors.Ink)
    }
}
