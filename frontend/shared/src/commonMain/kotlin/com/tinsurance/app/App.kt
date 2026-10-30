package com.tinsurance.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.tinsurance.app.client.*
import com.tinsurance.app.ui.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import tinsurance.shared.generated.resources.*

@OptIn(ExperimentalResourceApi::class)
@Composable
fun App(showStatusBar: Boolean = false, initialScreen: ClientScreen = ClientScreen.Claims) {
    InsuranceTheme {
        val draft = rememberSaveable(saver = ClaimDraft.Saver) { ClaimDraft() }
        var screenName by rememberSaveable { mutableStateOf(initialScreen.name) }
        var history by rememberSaveable { mutableStateOf(emptyList<String>()) }
        val screen = ClientScreen.valueOf(screenName)
        var claimNumber by rememberSaveable { mutableStateOf("ТС-10428") }
        var claimsTab by rememberSaveable { mutableStateOf(0) }
        var materialTab by rememberSaveable { mutableStateOf(0) }
        var readNotifications by rememberSaveable { mutableStateOf(false) }
        var submitted by rememberSaveable { mutableStateOf(false) }
        var repliedClaim by rememberSaveable { mutableStateOf("") }
        val demoDraft = remember { ClaimDraft() }
        val shownDraft = if (claimNumber == "ТС-10429") draft else demoDraft
        val inspection = LocalInspectionMode.current
        var errors by remember { mutableStateOf(emptyMap<String, String>()) }
        var attachments by remember { mutableStateOf(emptyList<Attachment>()) }
        var photoReplaced by remember { mutableStateOf(false) }
        var formatError by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf<String?>(null) }
        var dialog by remember { mutableStateOf<String?>(null) }
        var editingReview by remember { mutableStateOf(false) }
        var loadingFiles by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun open(next: ClientScreen) {
            if (next == screen) return
            history = history + screenName
            screenName = next.name
        }

        fun home() {
            history = emptyList()
            screenName = ClientScreen.Claims.name
            editingReview = false
        }

        fun back() {
            if (history.isEmpty()) home()
            else {
                screenName = history.last()
                history = history.dropLast(1)
            }
        }

        fun edit(next: ClientScreen) {
            editingReview = true
            open(next)
        }

        fun continueForm(next: ClientScreen) {
            if (editingReview) {
                editingReview = false
                open(ClientScreen.Review)
            } else open(next)
        }

        // Keep the picker at the root: on iOS it must outlive the screen that opens it.
        fun addFiles(files: List<PlatformFile>?) {
            if (files.isNullOrEmpty()) return
            scope.launch {
                loadingFiles = true
                try {
                    for (file in files) {
                        val totalSize = attachments.sumOf { it.bytes.size.toLong() } + 2_500_000L
                        val error =
                            fileError(file.name, file.size(), attachments.size + 2, totalSize)
                        if (error != null) {
                            message = "${file.name}: $error"
                            continue
                        }
                        val bytes = file.readBytes()
                        if (attachments.any { it.bytes.contentEquals(bytes) }) {
                            message = "Этот файл уже добавлен"
                            continue
                        }
                        val isPhoto = !file.name.endsWith(".pdf", ignoreCase = true)
                        val preview =
                            if (isPhoto) runCatching { bytes.decodeToImageBitmap() }.getOrNull()
                            else null
                        if (isPhoto && preview == null) {
                            message = "Не удалось прочитать ${file.name}. Выберите JPEG или PNG."
                            continue
                        }
                        attachments = attachments + Attachment(file.name, bytes, preview)
                        if (isPhoto) photoReplaced = true
                        formatError = false
                    }
                    if (screen == ClientScreen.PhotoQuality && photoReplaced) back()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = "Не удалось прочитать файл. Попробуйте выбрать его ещё раз."
                } finally {
                    loadingFiles = false
                }
            }
        }

        val pickFiles: () -> Unit =
            if (inspection) ({})
            else {
                val picker =
                    rememberFilePickerLauncher(
                        type = FileKitType.File(extensions = listOf("jpg", "jpeg", "png", "pdf")),
                        mode = FileKitMode.Multiple(maxItems = 10),
                        onError = { message = "Не удалось открыть выбор файлов" },
                        onResult = ::addFiles,
                    )
                val launchPicker: () -> Unit = { picker.launch() }
                launchPicker
            }
        val takePhoto: () -> Unit =
            if (inspection) ({})
            else
                rememberPhotoCapture(
                    onResult = { file -> if (file != null) addFiles(listOf(file)) },
                    onError = { message = it },
                )
        val download: (String, ByteArray) -> Unit =
            if (inspection) ({ _, _ -> }) else rememberPdfDownload { message = it }

        fun saveDocument(summary: Boolean) {
            scope.launch {
                try {
                    val file = if (summary) "claim_summary.pdf" else "accident_notice.pdf"
                    val name = if (summary) "Сводка обращения.pdf" else "Извещение_ДТП.pdf"
                    download(name, Res.readBytes("files/$file"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = "Не удалось открыть документ"
                }
            }
        }

        if (!inspection) PlatformBackHandler(history.isNotEmpty(), ::back)
        Box(
            Modifier.fillMaxSize()
                .background(if (showStatusBar) InsuranceColors.Field else Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val frameSize =
                if (showStatusBar) {
                    Modifier.widthIn(max = 390.dp).heightIn(max = 844.dp)
                } else {
                    Modifier.widthIn(max = 480.dp)
                }
            Column(
                frameSize
                    .fillMaxSize()
                    .clip(RoundedCornerShape(if (showStatusBar) 24.dp else 0.dp))
                    .background(Color.White)
            ) {
                if (showStatusBar) PreviewStatusBar()
                when (screen) {
                    ClientScreen.Claims,
                    ClientScreen.Notifications ->
                        BrandHeader({ open(ClientScreen.Notifications) }, { dialog = "profile" })
                    ClientScreen.Contract -> BackHeader("", ::back, step = 1)
                    ClientScreen.Event -> BackHeader("", ::back, step = 2)
                    ClientScreen.Materials -> BackHeader("", ::back, step = 3)
                    ClientScreen.Review -> BackHeader("", ::back, step = 4)
                    ClientScreen.PhotoQuality -> BackHeader("Проверка качества фото", ::back)
                    ClientScreen.Sent -> BackHeader("Отправка обращения", ::home)
                    ClientScreen.Reply -> BackHeader("Дополните обращение", ::back)
                    else -> BackHeader("Обращение № $claimNumber", ::back)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(screen) {
                        when (screen) {
                            ClientScreen.Claims ->
                                ClaimsScreen(
                                    tab = claimsTab,
                                    onTab = { claimsTab = it },
                                    onNew = { open(ClientScreen.Contract) },
                                    onProgress = { number ->
                                        claimNumber = number
                                        open(ClientScreen.Progress)
                                    },
                                    onRequest = {
                                        claimNumber = "ТС-10410"
                                        open(ClientScreen.Request)
                                    },
                                    onCompleted = {
                                        claimNumber = "ТС-10386"
                                        open(ClientScreen.Completed)
                                    },
                                    onPolicies = { dialog = "policies" },
                                    replySent = repliedClaim == "ТС-10410",
                                    submitted = submitted,
                                )
                            ClientScreen.Contract -> ContractScreen(draft) { dialog = "policy" }
                            ClientScreen.Event ->
                                EventScreen(draft, errors) { dialog = "participants" }
                            ClientScreen.Materials ->
                                MaterialsScreen(
                                    tab = materialTab,
                                    onTab = { materialTab = it },
                                    attachments = attachments,
                                    photoReplaced = photoReplaced,
                                    formatError = formatError,
                                    onUpload = pickFiles,
                                    onPhoto = { dark ->
                                        if (dark) open(ClientScreen.PhotoQuality)
                                        else dialog = "photo"
                                    },
                                    onRemove = { file ->
                                        attachments = attachments.filterNot { it === file }
                                        photoReplaced = attachments.any { it.isPhoto }
                                    },
                                    onDocument = { saveDocument(false) },
                                )
                            ClientScreen.PhotoQuality -> PhotoQualityScreen()
                            ClientScreen.Review ->
                                ReviewScreen(
                                    draft,
                                    if (photoReplaced) 1 + attachments.count { it.isPhoto } else 4,
                                    1 + attachments.count { !it.isPhoto },
                                    ::edit,
                                )
                            ClientScreen.Sent -> SentScreen(claimNumber)
                            ClientScreen.Progress ->
                                ProgressScreen(
                                    shownDraft,
                                    repliedClaim == claimNumber,
                                    onMaterials = { dialog = "materials" },
                                    onDetails = { dialog = "details" },
                                    onReply = { open(ClientScreen.Reply) },
                                )
                            ClientScreen.Request -> RequestScreen()
                            ClientScreen.Reply ->
                                ReplyScreen(
                                    draft,
                                    claimNumber,
                                    attachments.lastOrNull { it.isPhoto },
                                    onRequest = { open(ClientScreen.Request) },
                                    onUpload = pickFiles,
                                    onDocument = { saveDocument(false) },
                                )
                            ClientScreen.Completed -> CompletedScreen { saveDocument(true) }
                            ClientScreen.Notifications ->
                                NotificationsScreen(
                                    readNotifications,
                                    { readNotifications = true },
                                ) { next ->
                                    claimNumber = "ТС-10428"
                                    open(next)
                                }
                        }
                    }
                }
                when (screen) {
                    ClientScreen.Claims,
                    ClientScreen.Progress,
                    ClientScreen.Notifications ->
                        ClientNavigation(
                            notificationsSelected = screen == ClientScreen.Notifications,
                            onClaims = ::home,
                            onPolicies = { dialog = "policies" },
                            onNotifications = { open(ClientScreen.Notifications) },
                            onProfile = { dialog = "profile" },
                        )
                    ClientScreen.Contract ->
                        ActionFooter("Продолжить", { continueForm(ClientScreen.Event) })
                    ClientScreen.Event ->
                        ActionFooter(
                            "К материалам",
                            {
                                errors = validateEvent(draft)
                                if (errors.isEmpty()) continueForm(ClientScreen.Materials)
                            },
                        )
                    ClientScreen.Materials ->
                        ActionFooter(
                            if (loadingFiles) "Добавляем файлы…" else "Проверить материалы",
                            {
                                if (photoReplaced) continueForm(ClientScreen.Review)
                                else open(ClientScreen.PhotoQuality)
                            },
                            enabled = !loadingFiles,
                        )
                    ClientScreen.PhotoQuality ->
                        ActionFooter(
                            "Сделать новое фото",
                            takePhoto,
                            enabled = !loadingFiles,
                            link = "Выбрать из файлов",
                            onLink = pickFiles,
                        )
                    ClientScreen.Review ->
                        ActionFooter(
                            "Отправить обращение",
                            {
                                submitted = true
                                claimNumber = "ТС-10429"
                                open(ClientScreen.Sent)
                            },
                            enabled = draft.confirmed,
                        )
                    ClientScreen.Sent ->
                        ActionFooter(
                            "Следить за обращением",
                            { open(ClientScreen.Progress) },
                            link = "К списку обращений",
                            onLink = ::home,
                        )
                    ClientScreen.Request ->
                        ActionFooter(
                            "Добавить материалы",
                            { open(ClientScreen.Reply) },
                            link = "Открыть исходное обращение",
                            onLink = { open(ClientScreen.Progress) },
                        )
                    ClientScreen.Reply ->
                        ActionFooter(
                            "Отправить дополнение",
                            {
                                repliedClaim = claimNumber
                                open(ClientScreen.Progress)
                            },
                            enabled = !loadingFiles,
                        )
                    ClientScreen.Completed -> ActionFooter("К обращениям", ::home, secondary = true)
                }
            }
        }
        if (dialog != null)
            ClientDialog(dialog!!, if (dialog == "details") shownDraft else draft) { dialog = null }
        if (message != null) {
            AlertDialog(
                onDismissRequest = { message = null },
                title = { Text("Материалы обращения") },
                text = { Text(message!!) },
                confirmButton = { TextButton(onClick = { message = null }) { Text("Понятно") } },
            )
        }
    }
}
