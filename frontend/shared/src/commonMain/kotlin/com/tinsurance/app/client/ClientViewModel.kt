package com.tinsurance.app.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.MobileApiClient
import com.tinsurance.app.data.normalizePhone
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.InfoRequestItemDto
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.UserDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.ClaimListItem
import ru.tinsurance.api.mobile.CreateClaimRequest
import ru.tinsurance.api.mobile.EventTypeDto
import ru.tinsurance.api.mobile.NotificationDto
import ru.tinsurance.api.mobile.TimelineEvent
import ru.tinsurance.api.mobile.UpdateClaimRequest

data class LoginState(val loading: Boolean = false, val error: String? = null)

data class ClaimsState(
    val loading: Boolean = false,
    val error: String? = null,
    val policies: List<PolicyDto> = emptyList(),
    val claims: List<ClaimListItem> = emptyList(),
) {
    val active get() = claims.filter { !it.isFinal && it.status != ClaimStatus.DRAFT }
    val drafts get() = claims.filter { it.status == ClaimStatus.DRAFT }
    val completed get() = claims.filter { it.isFinal }
}

/** Открытое обращение: экраны «Ход обработки» и «Обработка завершена». */
data class ClaimState(
    val id: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val details: ClaimDetails? = null,
    val timeline: List<TimelineEvent> = emptyList(),
)

data class NotificationsState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val items: List<NotificationDto> = emptyList(),
    /** null — загружены все. */
    val nextBeforeId: Long? = null,
    val unreadCount: Int = 0,
)

/** Новое обращение: справочник типов, форма, ошибки полей и общий статус сохранения. */
data class DraftState(
    val eventTypes: List<EventTypeDto> = emptyList(),
    val form: ClaimForm = ClaimForm(),
    val errors: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val selectedType get() = eventTypes.find { it.code == form.eventType }
}

// ReplyState, UploadedFile, isTextField и fieldsUpdate — в ReplyState.kt.

class ClientViewModel(private val api: MobileApiClient = MobileApiClient()) : ViewModel() {
    var user by mutableStateOf<UserDto?>(null)
        private set

    var login by mutableStateOf(LoginState())
        private set

    var claims by mutableStateOf(ClaimsState())
        private set

    var claim by mutableStateOf(ClaimState())
        private set

    var notifications by mutableStateOf(NotificationsState())
        private set

    var reply by mutableStateOf(ReplyState())
        private set

    /** Загрузка по пункту запроса: тип документа и, если просили, файл на замену. */
    fun uploadReplyFiles(files: List<Pair<String, ByteArray>>, item: InfoRequestItemDto) {
        val documentType = item.documentType ?: return
        uploadFiles(files, documentType, item.documentTypeName ?: documentType, item.fileId)
    }

    /**
     * Загружает файлы в открытое обращение (черновик или ответ на запрос). После загрузки
     * обновляем обращение: сервер сам отмечает выполненные пункты и проверяет качество фото.
     */
    fun uploadFiles(
        files: List<Pair<String, ByteArray>>,
        documentType: String,
        documentTypeName: String,
        replacesFileId: Long? = null,
    ) {
        val claimId = claim.id ?: return
        if (reply.uploading || files.isEmpty()) return
        reply = reply.copy(uploading = true, error = null)
        viewModelScope.launch {
            var uploads = reply.uploads
            var error: String? = null
            for ((name, bytes) in files) {
                try {
                    val result = api.uploadFile(claimId, documentType, name, bytes, replacesFileId)
                    uploads = uploads + UploadedFile(name, documentTypeName, result)
                } catch (e: ApiException) {
                    checkSession(e)
                    error = "$name: ${e.message}"
                }
            }
            reply = reply.copy(uploading = false, uploads = uploads, error = error)
            refreshClaimDetails()
            // Качество фото сервер проверяет в фоне за несколько секунд: подтягиваем итог.
            for (pause in listOf(2_000L, 4_000L)) {
                delay(pause)
                refreshClaimDetails()
            }
        }
    }

    /** Сохраняет ответы на запрошенные поля и отправляет обращение повторно. */
    fun sendReply(fields: Map<String, String>, onSent: () -> Unit) {
        val claimId = claim.id ?: return
        if (reply.sending || reply.uploading) return
        reply = reply.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                if (fields.isNotEmpty()) api.updateClaim(claimId, fieldsUpdate(fields))
                api.submit(claimId)
                reply = ReplyState()
                openClaim(claimId)
                onSent()
            } catch (e: ApiException) {
                checkSession(e)
                reply = reply.copy(sending = false, error = e.message)
                refreshClaimDetails()
            }
        }
    }

    fun resetReply() {
        reply = ReplyState()
    }

    // ---- Новое обращение: экраны 02–04 ----

    var draft by mutableStateOf(DraftState())
        private set

    /** UUID нового черновика: повтор «Продолжить» при плохой связи не создаст второй. */
    private var draftRequestId: String? = null

    @OptIn(ExperimentalUuidApi::class)
    fun startNewClaim() {
        openClaim(null)
        reply = ReplyState()
        draftRequestId = Uuid.random().toString()
        draft =
            DraftState(
                eventTypes = draft.eventTypes,
                form =
                    ClaimForm(
                        policyId = claims.policies.singleOrNull()?.id,
                        text = user?.phone?.let { mapOf(ClaimField.CONTACT_PHONE.apiName to it) }.orEmpty(),
                    ),
            )
        loadEventTypes()
    }

    /** Продолжение черновика из списка «Черновики». */
    fun openDraft(id: Long) {
        reply = ReplyState()
        draftRequestId = null
        draft = DraftState(eventTypes = draft.eventTypes, loading = true)
        openClaim(id)
        loadEventTypes()
        viewModelScope.launch {
            try {
                val details = api.claim(id)
                draft = draft.copy(loading = false, form = details.toForm(user?.phone))
            } catch (e: ApiException) {
                checkSession(e)
                draft = draft.copy(loading = false, error = e.message)
            }
        }
    }

    fun updateForm(change: (ClaimForm) -> ClaimForm) {
        draft = draft.copy(form = change(draft.form), errors = emptyMap(), error = null)
    }

    /** Экран 02: создаём черновик или меняем у него полис и тип события. */
    fun saveContract(onSaved: () -> Unit) {
        val form = draft.form
        if (form.policyId == null || form.eventType == null) {
            draft = draft.copy(error = "Выберите договор и что случилось")
            return
        }
        persist(onSaved) { claimId ->
            if (claimId == null)
                api.createClaim(
                    CreateClaimRequest(
                        policyId = form.policyId,
                        eventType = form.eventType,
                        contactPhone = form.text[ClaimField.CONTACT_PHONE.apiName],
                        clientRequestId = draftRequestId,
                    )
                )
            else api.updateClaim(claimId, UpdateClaimRequest(policyId = form.policyId, eventType = form.eventType))
        }
    }

    /** Экран 03: проверяем поля у себя и сохраняем всё одним PATCH. */
    fun saveEvent(onSaved: () -> Unit) {
        val type = draft.selectedType
        val errors = validateForm(draft.form, type)
        if (errors.isNotEmpty()) {
            draft = draft.copy(errors = errors)
            return
        }
        val name = user?.fullName ?: "Заявитель"
        persist(onSaved) { claimId ->
            api.updateClaim(claimId ?: return@persist null, draft.form.toUpdate(type, name, user?.phone))
        }
    }

    /** Экран 04: лишний файл в черновике удаляется (на сервере помечается DELETED). */
    fun deleteDraftFile(fileId: Long) {
        val claimId = claim.id ?: return
        viewModelScope.launch {
            try {
                api.deleteFile(claimId, fileId)
                reply = reply.copy(uploads = reply.uploads.filterNot { it.result.id == fileId })
            } catch (e: ApiException) {
                checkSession(e)
                reply = reply.copy(error = e.message)
            }
            refreshClaimDetails()
        }
    }

    private fun persist(onSaved: () -> Unit, save: suspend (claimId: Long?) -> ClaimDetails?) {
        if (draft.saving) return
        draft = draft.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val saved = save(claim.id)
                if (saved != null) claim = claim.copy(id = saved.id, loading = false, details = saved)
                draft = draft.copy(saving = false)
                onSaved()
            } catch (e: ApiException) {
                checkSession(e)
                draft = draft.copy(saving = false, error = e.message)
            }
        }
    }

    private fun loadEventTypes() {
        if (draft.eventTypes.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val types = api.eventTypes()
                draft = draft.copy(eventTypes = types)
            } catch (e: ApiException) {
                checkSession(e)
                draft = draft.copy(error = e.message)
            }
        }
    }

    private suspend fun refreshClaimDetails() {
        val id = claim.id ?: return
        try {
            val details = api.claim(id)
            if (claim.id == id) claim = claim.copy(details = details)
        } catch (e: ApiException) {
            checkSession(e)
        }
    }

    private var claimJob: Job? = null

    /** null — демо-сценарий макета (например, после отправки локального черновика). */
    fun openClaim(id: Long?) {
        claimJob?.cancel()
        claim = ClaimState(id = id, loading = id != null)
        if (id == null) return
        claimJob =
            viewModelScope.launch {
                claim =
                    try {
                        coroutineScope {
                            val details = async { api.claim(id) }
                            val timeline = async { api.timeline(id) }
                            ClaimState(id, details = details.await(), timeline = timeline.await())
                        }
                    } catch (e: ApiException) {
                        checkSession(e)
                        ClaimState(id, error = e.message)
                    }
            }
    }

    fun reloadClaim() = openClaim(claim.id)

    fun login(phone: String, password: String) {
        if (login.loading) return
        if (phone.isBlank() || password.isBlank()) {
            login = LoginState(error = "Введите телефон и пароль")
            return
        }
        login = LoginState(loading = true)
        viewModelScope.launch {
            login =
                try {
                    // Список обращений загрузит экран Claims, когда появится пользователь.
                    user = api.login(normalizePhone(phone), password)
                    LoginState()
                } catch (e: ApiException) {
                    LoginState(error = e.message)
                }
        }
    }

    fun logout() {
        viewModelScope.launch { api.logout() }
        user = null
        claims = ClaimsState()
        notifications = NotificationsState()
        draft = DraftState()
        openClaim(null)
    }

    fun loadClaims() {
        if (claims.loading) return
        claims = claims.copy(loading = true, error = null)
        viewModelScope.launch {
            claims =
                try {
                    // coroutineScope: ошибка одного запроса не уронит viewModelScope, а придёт в catch.
                    coroutineScope {
                        val policies = async { api.policies() }
                        val list = async { api.claims() }
                        ClaimsState(policies = policies.await(), claims = list.await())
                    }
                } catch (e: ApiException) {
                    checkSession(e)
                    claims.copy(loading = false, error = e.message)
                }
        }
    }

    fun loadNotifications() {
        if (notifications.loading) return
        notifications = notifications.copy(loading = true, error = null)
        viewModelScope.launch {
            notifications =
                try {
                    coroutineScope {
                        val page = async { api.notifications() }
                        val unread = async { unreadCount() }
                        val first = page.await()
                        NotificationsState(
                            items = first.items,
                            nextBeforeId = first.nextBeforeId,
                            unreadCount = unread.await(),
                        )
                    }
                } catch (e: ApiException) {
                    checkSession(e)
                    notifications.copy(loading = false, error = e.message)
                }
        }
    }

    fun loadMoreNotifications() {
        val before = notifications.nextBeforeId ?: return
        if (notifications.loading || notifications.loadingMore) return
        notifications = notifications.copy(loadingMore = true, error = null)
        viewModelScope.launch {
            notifications =
                try {
                    val page = api.notifications(beforeId = before)
                    notifications.copy(
                        loadingMore = false,
                        items = notifications.items + page.items,
                        nextBeforeId = page.nextBeforeId,
                    )
                } catch (e: ApiException) {
                    checkSession(e)
                    notifications.copy(loadingMore = false, error = e.message)
                }
        }
    }

    /** Для значка на колокольчике: вызывается при возврате к списку обращений. */
    fun refreshUnreadCount() {
        viewModelScope.launch {
            try {
                notifications = notifications.copy(unreadCount = unreadCount())
            } catch (e: ApiException) {
                checkSession(e)
            }
        }
    }

    fun markRead(id: Long) {
        val item = notifications.items.find { it.id == id } ?: return
        if (item.readAt != null) return
        viewModelScope.launch {
            try {
                val updated = api.markNotificationRead(id)
                notifications =
                    notifications.copy(
                        items = notifications.items.map { if (it.id == id) updated else it },
                        unreadCount = (notifications.unreadCount - 1).coerceAtLeast(0),
                    )
            } catch (e: ApiException) {
                checkSession(e)
            }
        }
    }

    // В API нет «прочитать все»: отмечаем загруженные непрочитанные по одному.
    fun markAllRead() {
        notifications.items.filter { it.readAt == null }.forEach { markRead(it.id) }
    }

    private suspend fun unreadCount(): Int =
        api.notifications(limit = MaxUnreadShown, unreadOnly = true).items.size

    private fun checkSession(e: ApiException) {
        if (e.unauthorized) {
            user = null
            login = LoginState(error = e.message)
        }
    }
}

/** Больше значок не показывает: «99+». */
const val MaxUnreadShown = 100

/** Куда загрузить выбранные файлы: тип документа и, при замене, id старого файла. */
data class UploadTarget(val documentType: String, val name: String, val replacesFileId: Long? = null) {
    companion object {
        fun of(item: InfoRequestItemDto) =
            UploadTarget(
                item.documentType.orEmpty(),
                item.documentTypeName ?: item.documentType.orEmpty(),
                item.fileId,
            )
    }
}
