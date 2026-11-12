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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinsurance.app.admin.AdminApp
import com.tinsurance.app.admin.AdminScreen
import com.tinsurance.app.client.*
import com.tinsurance.app.demo.DemoLoginScreen
import com.tinsurance.app.demo.DemoRole
import com.tinsurance.app.operator.OperatorApp
import com.tinsurance.app.operator.OperatorScreen
import com.tinsurance.app.ui.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.mobile.ClaimListItem
import tinsurance.shared.generated.resources.*

@OptIn(ExperimentalResourceApi::class)
@Composable
fun App(
    showStatusBar: Boolean = false,
    initialScreen: ClientScreen = ClientScreen.Claims,
    initialOperatorScreen: OperatorScreen? = null,
    initialAdminScreen: AdminScreen? = null,
    showDemo: Boolean = false,
) {
    var role by rememberSaveable { mutableStateOf(when {
        showDemo -> "Demo"
        initialAdminScreen != null -> DemoRole.Admin.name
        initialOperatorScreen != null -> DemoRole.Operator.name
        else -> DemoRole.Client.name
    }) }
    var demoClient by rememberSaveable { mutableStateOf(false) }
    fun client() { demoClient = false; role = DemoRole.Client.name }
    when (role) {
        "Demo" -> DemoLoginScreen { selected -> demoClient = selected == DemoRole.Client; role = selected.name }
        DemoRole.Admin.name -> AdminApp(initialAdminScreen ?: AdminScreen.Overview, onClient = ::client, onDemo = { role = "Demo" })
        DemoRole.Operator.name -> OperatorApp(initialOperatorScreen ?: OperatorScreen.Queue, onClient = ::client, onDemo = { role = "Demo" })
        else -> ClientApp(showStatusBar, initialScreen, demoClient,
            onOperator = { role = DemoRole.Operator.name }, onAdmin = { role = DemoRole.Admin.name }, onDemo = { role = "Demo" })
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ClientApp(
    showStatusBar: Boolean,
    initialScreen: ClientScreen,
    demo: Boolean,
    onOperator: () -> Unit,
    onAdmin: () -> Unit,
    onDemo: () -> Unit,
) {
    InsuranceTheme {
        val draft = rememberSaveable(saver = ClaimDraft.Saver) { ClaimDraft() }
        var screenName by rememberSaveable { mutableStateOf(initialScreen.name) }
        var history by rememberSaveable { mutableStateOf(emptyList<String>()) }
        val screen = ClientScreen.valueOf(screenName)
        var claimNumber by rememberSaveable { mutableStateOf("ТС-10428") }
        var claimsTab by rememberSaveable { mutableStateOf(0) }
        var materialTab by rememberSaveable { mutableStateOf(0) }
        var repliedClaim by rememberSaveable { mutableStateOf("") }
        val demoDraft = remember { ClaimDraft() }
        val shownDraft = if (claimNumber == "ТС-10429") draft else demoDraft
        val inspection = LocalInspectionMode.current
        val client = viewModel { ClientViewModel() }
        // В @Preview нет backend: показываем пример списка без входа.
        val claims = if (inspection || demo) PreviewClaims else client.claims
        var demoNotifications by remember { mutableStateOf(PreviewNotifications) }
        val notifications = if (inspection || demo) demoNotifications else client.notifications
        // Открыто настоящее обращение из списка; иначе экраны показывают сценарий макета.
        val realClaim = !inspection && !demo && client.claim.id != null
        // Вошли под настоящим пользователем: форма нового обращения работает с API.
        val realForm = !inspection && !demo && client.user != null
        val claimPolicy = claims.policies.find { it.id == client.claim.details?.policyId }
        // Опрос уведомлений раз в 5 секунд, пока пользователь вошёл и приложение на экране:
        // в свёрнутом приложении и фоновой вкладке не тратим сеть.
        val lifecycleOwner = LocalLifecycleOwner.current
        val currentScreen by rememberUpdatedState(screen)
        LaunchedEffect(client.user, lifecycleOwner) {
            if (inspection || demo || client.user == null) return@LaunchedEffect
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(NotificationPollMillis)
                    client.pollNotifications(
                        onNotifications = currentScreen == ClientScreen.Notifications,
                        onClaims = currentScreen == ClientScreen.Claims,
                    )
                }
            }
        }
        LaunchedEffect(screen, client.user) {
            if (inspection || demo || client.user == null) return@LaunchedEffect
            when (screen) {
                ClientScreen.Claims -> { client.loadClaims(); client.refreshUnreadCount() }
                ClientScreen.Notifications -> client.loadNotifications()
                // Шаг 4 формы: при каждом открытии — свежая проверка полноты.
                ClientScreen.Review -> if (client.claim.details?.status == ClaimStatus.DRAFT) client.loadReview()
                else -> Unit
            }
        }
        var errors by remember { mutableStateOf(emptyMap<String, String>()) }
        var attachments by remember { mutableStateOf(emptyList<Attachment>()) }
        var photoReplaced by remember { mutableStateOf(false) }
        var formatError by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf<String?>(null) }
        var dialog by remember { mutableStateOf<String?>(null) }
        var editingReview by remember { mutableStateOf(false) }
        // Куда загрузить файлы, выбранные в системном окне или снятые камерой.
        var uploadTarget by remember { mutableStateOf<UploadTarget?>(null) }
        // Ответы на запрошенные поля: fieldName → текст.
        var replyFields by remember { mutableStateOf(emptyMap<String, String>()) }
        // Флажок «Сведения верны» на шаге 4 настоящего черновика.
        var reviewConfirmed by remember { mutableStateOf(false) }
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

        // Экраны обращения — из API; черновик открывается в форме для продолжения.
        // В демо-режиме обращения из примера, поэтому экраны показывают сценарий макета.
        fun openListedClaim(claim: ClaimListItem) {
            claimNumber = claim.claimNumber
            when {
                demo -> client.openClaim(null)
                claim.status == ClaimStatus.DRAFT -> client.openDraft(claim.id)
                else -> client.openClaim(claim.id)
            }
            open(
                when {
                    claim.status == ClaimStatus.DRAFT -> ClientScreen.Contract
                    claim.actionRequired -> ClientScreen.Request
                    claim.isFinal -> ClientScreen.Completed
                    else -> ClientScreen.Progress
                }
            )
        }

        fun readDemoNotification(id: Long? = null) {
            val items = demoNotifications.items.map {
                if (id == null || it.id == id) it.copy(readAt = "2026-10-17T12:00:00+07:00") else it
            }
            demoNotifications = demoNotifications.copy(items = items, unreadCount = items.count { it.readAt == null })
        }

        fun continueForm(next: ClientScreen) {
            if (editingReview) {
                editingReview = false
                open(ClientScreen.Review)
            } else open(next)
        }

        // Черновик и ответ на запрос: файлы сразу уходят на сервер с выбранным типом документа.
        fun uploadToServer(files: List<PlatformFile>, target: UploadTarget) {
            scope.launch {
                try {
                    val read =
                        files.mapNotNull { file ->
                            val error = fileError(file.name, file.size(), 0, 0)
                            if (error != null) {
                                message = "${file.name}: $error"
                                null
                            } else file.name to file.readBytes()
                        }
                    client.uploadFiles(read, target.documentType, target.name, target.replacesFileId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    message = "Не удалось прочитать файл. Попробуйте выбрать его ещё раз."
                }
            }
        }

        // Keep the picker at the root: on iOS it must outlive the screen that opens it.
        // The launcher keeps the first addFiles, so read state here, not vals like screen/realClaim.
        fun addFiles(files: List<PlatformFile>?) {
            if (files.isNullOrEmpty()) return
            val target = uploadTarget
            val onServerScreen =
                screenName in listOf(ClientScreen.Reply, ClientScreen.Materials, ClientScreen.PhotoQuality).map { it.name }
            if (!inspection && !demo && client.claim.id != null && onServerScreen && target != null) {
                uploadToServer(files, target)
                return
            }
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
                    if (screenName == ClientScreen.PhotoQuality.name && photoReplaced) back()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
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
                } catch (_: Throwable) {
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
                if (!inspection && !demo && client.user == null) {
                    // Есть сохранённый вход: ждём ответа сервера, а не мигаем формой входа.
                    if (client.restoring) ScreenBody { Caption("Входим…") }
                    else LoginScreen(client.login, client::login, onOperator, onAdmin, onDemo)
                    return@Column
                }
                when (screen) {
                    ClientScreen.Claims,
                    ClientScreen.Notifications ->
                        BrandHeader({ open(ClientScreen.Notifications) }, { dialog = "profile" }, unread = notifications.unreadCount)
                    ClientScreen.Contract -> BackHeader("", ::back, step = 1)
                    ClientScreen.Event -> BackHeader("", ::back, step = 2)
                    ClientScreen.Materials -> BackHeader("", ::back, step = 3)
                    ClientScreen.Review -> BackHeader("", ::back, step = 4)
                    ClientScreen.PhotoQuality -> BackHeader("Проверка качества фото", ::back)
                    ClientScreen.Sent -> BackHeader("Отправка обращения", ::home)
                    ClientScreen.Reply -> BackHeader("Дополните обращение", ::back)
                    // У настоящего обращения номер с сервера: новое обращение в списке ещё не открывали.
                    else ->
                        BackHeader(
                            "Обращение № ${client.claim.details?.claimNumber?.takeIf { realClaim } ?: claimNumber}",
                            ::back,
                        )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(screen) {
                        when (screen) {
                            ClientScreen.Claims ->
                                ClaimsScreen(
                                    state = claims,
                                    tab = claimsTab,
                                    onTab = { claimsTab = it },
                                    onNew = {
                                        if (realForm) client.startNewClaim()
                                        open(ClientScreen.Contract)
                                    },
                                    onOpen = ::openListedClaim,
                                    onPolicies = { dialog = "policies" },
                                    onRetry = { if (!demo) client.loadClaims() },
                                )
                            ClientScreen.Contract ->
                                if (realForm)
                                    DraftContractScreen(
                                        client.draft,
                                        claims.policies,
                                        client.claim.details?.claimNumber,
                                        client::updateForm,
                                    )
                                else ContractScreen(draft) { dialog = "policy" }
                            ClientScreen.Event ->
                                if (realForm) DraftEventScreen(client.draft, client::updateForm)
                                else EventScreen(draft, errors) { dialog = "participants" }
                            ClientScreen.Materials -> {
                                val details = client.claim.details
                                if (realForm && details != null)
                                    DraftMaterialsScreen(
                                        details,
                                        client.draft.selectedType?.documents.orEmpty(),
                                        client.reply,
                                        onPickFiles = { doc ->
                                            uploadTarget = UploadTarget(doc.documentType, doc.name)
                                            pickFiles()
                                        },
                                        onTakePhoto = { doc ->
                                            uploadTarget = UploadTarget(doc.documentType, doc.name)
                                            takePhoto()
                                        },
                                        onDelete = { file -> client.deleteDraftFile(file.id) },
                                    )
                                else if (realForm)
                                    ScreenBody { LoadingOrError(client.claim, client::reloadClaim) }
                                else
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
                            }
                            ClientScreen.PhotoQuality -> {
                                val details = client.claim.details
                                if (realForm && details != null)
                                    DraftPhotoQualityScreen(
                                        details,
                                        client.draft.selectedType?.documents.orEmpty(),
                                        client.reply,
                                        onPickFiles = { doc ->
                                            uploadTarget = UploadTarget(doc.documentType, doc.name)
                                            pickFiles()
                                        },
                                        onTakePhoto = { doc ->
                                            uploadTarget = UploadTarget(doc.documentType, doc.name)
                                            takePhoto()
                                        },
                                        onDelete = { file -> client.deleteDraftFile(file.id) },
                                    )
                                else PhotoQualityScreen()
                            }
                            ClientScreen.Review -> {
                                val details = client.claim.details
                                if (realForm && details != null)
                                    DraftReviewScreen(
                                        details,
                                        client.draft.selectedType,
                                        claimPolicy,
                                        client.review,
                                        confirmed = reviewConfirmed,
                                        onConfirm = { reviewConfirmed = it },
                                        onEdit = ::edit,
                                    )
                                else
                                    ReviewScreen(
                                        draft,
                                        if (photoReplaced) 1 + attachments.count { it.isPhoto } else 4,
                                        1 + attachments.count { !it.isPhoto },
                                        ::edit,
                                    )
                            }
                            ClientScreen.Sent ->
                                if (realClaim) DraftSentScreen(client.claim, client::reloadClaim)
                                else SentScreen(claimNumber)
                            ClientScreen.Progress ->
                                if (realClaim)
                                    ClaimProgressScreen(
                                        client.claim,
                                        claimPolicy,
                                        onMaterials = { dialog = "materials" },
                                        onDetails = { dialog = "details" },
                                        onRetry = client::reloadClaim,
                                    )
                                else
                                    ProgressScreen(
                                        shownDraft,
                                        repliedClaim == claimNumber,
                                        onMaterials = { dialog = "materials" },
                                        onDetails = { dialog = "details" },
                                        onReply = { open(ClientScreen.Reply) },
                                    )
                            ClientScreen.Request ->
                                if (realClaim) ClaimRequestScreen(client.claim, client::reloadClaim)
                                else RequestScreen()
                            ClientScreen.Reply ->
                                if (realClaim)
                                    ClaimReplyScreen(
                                        client.claim,
                                        client.reply,
                                        replyFields,
                                        onField = { name, value ->
                                            replyFields = replyFields + (name to value)
                                        },
                                        onPickFiles = { item ->
                                            uploadTarget = UploadTarget.of(item)
                                            pickFiles()
                                        },
                                        onTakePhoto = { item ->
                                            uploadTarget = UploadTarget.of(item)
                                            takePhoto()
                                        },
                                        onRequest = ::back,
                                        onRetry = client::reloadClaim,
                                    )
                                else
                                    ReplyScreen(
                                        draft,
                                        claimNumber,
                                        attachments.lastOrNull { it.isPhoto },
                                        onRequest = { open(ClientScreen.Request) },
                                        onUpload = pickFiles,
                                        onDocument = { saveDocument(false) },
                                    )
                            ClientScreen.Completed ->
                                if (realClaim)
                                    ClaimCompletedScreen(client.claim, client::reloadClaim)
                                else CompletedScreen { saveDocument(true) }
                            ClientScreen.Notifications ->
                                NotificationsScreen(
                                    notifications,
                                    claims.claims.associate { it.id to it.claimNumber },
                                    onReadAll = { if (demo) readDemoNotification() else client.markAllRead() },
                                    onOpen = { item ->
                                        if (demo) readDemoNotification(item.id) else client.markRead(item.id)
                                        val claimId = item.claimId ?: return@NotificationsScreen
                                        val listed = claims.claims.find { it.id == claimId }
                                        if (listed != null) openListedClaim(listed)
                                        else if (!demo) { client.openClaim(claimId); open(ClientScreen.Progress) }
                                    },
                                    onMore = { if (!demo) client.loadMoreNotifications() },
                                    onRetry = { if (!demo) client.loadNotifications() },
                                )
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
                            unread = notifications.unreadCount,
                        )
                    ClientScreen.Contract ->
                        if (realForm)
                            ActionFooter(
                                if (client.draft.saving) "Сохраняем…" else "Продолжить",
                                { client.saveContract { continueForm(ClientScreen.Event) } },
                                enabled = !client.draft.saving && !client.draft.loading,
                            )
                        else ActionFooter("Продолжить", { continueForm(ClientScreen.Event) })
                    ClientScreen.Event ->
                        if (realForm)
                            ActionFooter(
                                if (client.draft.saving) "Сохраняем…" else "К материалам",
                                { client.saveEvent { continueForm(ClientScreen.Materials) } },
                                enabled = !client.draft.saving,
                            )
                        else
                            ActionFooter(
                                "К материалам",
                                {
                                    errors = validateEvent(draft)
                                    if (errors.isEmpty()) continueForm(ClientScreen.Materials)
                                },
                            )
                    ClientScreen.Materials ->
                        if (realForm)
                            ActionFooter(
                                if (client.reply.uploading) "Загружаем файлы…" else "Проверить материалы",
                                {
                                    // Есть отклонённые или ещё проверяемые фото — сначала экран 05.
                                    val docs = client.draft.selectedType?.documents.orEmpty()
                                    val toReview = client.claim.details?.photosToReview(docs).orEmpty()
                                    if (toReview.isNotEmpty()) open(ClientScreen.PhotoQuality)
                                    else continueForm(ClientScreen.Review)
                                },
                                enabled = !client.reply.uploading,
                            )
                        else
                            ActionFooter(
                                if (loadingFiles) "Добавляем файлы…" else "Проверить материалы",
                                {
                                    if (photoReplaced) continueForm(ClientScreen.Review)
                                    else open(ClientScreen.PhotoQuality)
                                },
                                enabled = !loadingFiles,
                            )
                    ClientScreen.PhotoQuality ->
                        if (realForm)
                            ActionFooter(
                                if (client.reply.uploading) "Загружаем файлы…" else "Продолжить",
                                { continueForm(ClientScreen.Review) },
                                enabled = !client.reply.uploading,
                            )
                        else
                            ActionFooter(
                                "Сделать новое фото",
                                takePhoto,
                                enabled = !loadingFiles,
                                link = "Выбрать из файлов",
                                onLink = pickFiles,
                            )
                    ClientScreen.Review ->
                        if (realForm) {
                            val review = client.review
                            ActionFooter(
                                if (review.sending) "Отправляем…" else "Отправить обращение",
                                {
                                    client.submitDraft {
                                        reviewConfirmed = false
                                        home()
                                        open(ClientScreen.Sent)
                                    }
                                },
                                enabled = reviewConfirmed && !review.blocked && !review.loading && !review.sending,
                            )
                        } else
                            ActionFooter(
                                "Отправить обращение",
                                {
                                    claimNumber = "ТС-10429"
                                    open(ClientScreen.Sent)
                                },
                                enabled = draft.confirmed,
                            )
                    ClientScreen.Sent ->
                        if (realClaim) {
                            val needsInfo = client.claim.details?.status == ClaimStatus.NEEDS_INFO
                            ActionFooter(
                                if (needsInfo) "Добавить материалы" else "Следить за обращением",
                                { open(if (needsInfo) ClientScreen.Request else ClientScreen.Progress) },
                                link = "К списку обращений",
                                onLink = ::home,
                            )
                        } else
                            ActionFooter(
                                "Следить за обращением",
                                {
                                    // Отправка пока локальная: показываем демо-ход обработки.
                                    client.openClaim(null)
                                    open(ClientScreen.Progress)
                                },
                                link = "К списку обращений",
                                onLink = ::home,
                            )
                    ClientScreen.Request ->
                        ActionFooter(
                            "Добавить материалы",
                            {
                                val details = client.claim.details
                                if (realClaim && details != null) {
                                    client.resetReply()
                                    // Подставляем текущие значения: сервер принимает и подтверждение прежнего.
                                    replyFields =
                                        details.openInfoRequest?.items.orEmpty()
                                            .mapNotNull { it.fieldName }
                                            .filter(::isTextField)
                                            .associateWith { currentFieldValue(details, it) }
                                }
                                open(ClientScreen.Reply)
                            },
                            enabled = !realClaim || client.claim.details?.openInfoRequest != null,
                            link = "Открыть исходное обращение",
                            onLink = { open(ClientScreen.Progress) },
                        )
                    ClientScreen.Reply ->
                        if (realClaim) {
                            val reply = client.reply
                            ActionFooter(
                                if (reply.sending) "Отправляем…" else "Отправить дополнение",
                                {
                                    val requested =
                                        client.claim.details?.openInfoRequest?.items.orEmpty()
                                            .filter { !it.isFulfilled }
                                            .mapNotNull { it.fieldName }
                                    client.sendReply(replyFields.filterKeys { it in requested }) {
                                        home()
                                        open(ClientScreen.Progress)
                                    }
                                },
                                enabled = !reply.uploading && !reply.sending,
                            )
                        } else
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
            ClientDialog(
                dialog!!,
                if (dialog == "details") shownDraft else draft,
                user = client.user,
                policies = claims.policies,
                claim = client.claim.details.takeIf { realClaim },
                onLogout = {
                    dialog = null
                    home()
                    if (demo) onDemo() else client.logout()
                },
                onDemo = onDemo,
            ) {
                dialog = null
            }
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
