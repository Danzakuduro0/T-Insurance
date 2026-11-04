package com.tinsurance.app.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.MobileApiClient
import com.tinsurance.app.data.normalizePhone
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.InfoRequestItemDto
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.UserDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.ClaimListItem
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

/** Файл, загруженный в ответ на запрос: принят, дубль или отклонён с подсказкой. */
data class UploadedFile(val name: String, val documentTypeName: String, val result: ClaimFileDto)

/** Экран «Ответ на запрос»: загрузки этого сеанса и повторная отправка. */
data class ReplyState(
    val uploading: Boolean = false,
    val sending: Boolean = false,
    val uploads: List<UploadedFile> = emptyList(),
    val error: String? = null,
)

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

    /**
     * Загружает файлы по одному пункту запроса. После каждой загрузки обновляем обращение:
     * сервер сам отмечает пункты запроса выполненными.
     */
    fun uploadReplyFiles(files: List<Pair<String, ByteArray>>, item: InfoRequestItemDto) {
        val claimId = claim.id ?: return
        val documentType = item.documentType ?: return
        if (reply.uploading || files.isEmpty()) return
        reply = reply.copy(uploading = true, error = null)
        viewModelScope.launch {
            var uploads = reply.uploads
            var error: String? = null
            for ((name, bytes) in files) {
                try {
                    val result = api.uploadFile(claimId, documentType, name, bytes, item.fileId)
                    uploads = uploads + UploadedFile(name, item.documentTypeName ?: documentType, result)
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

private val textFields =
    setOf(ClaimField.DESCRIPTION, ClaimField.EVENT_LOCATION, ClaimField.CONTACT_PHONE)
        .map { it.apiName }

/** Текстом отвечаем на основные строковые поля и ключи details; дата, полис и тип — через форму. */
fun isTextField(name: String) = name in textFields || ClaimField.entries.none { it.apiName == name }

/** Основные поля обращения уходят своими полями UpdateClaimRequest, ключи details — в details. */
fun fieldsUpdate(fields: Map<String, String>): UpdateClaimRequest {
    val details = fields.filterKeys { it !in textFields && isTextField(it) }
    return UpdateClaimRequest(
        description = fields[ClaimField.DESCRIPTION.apiName],
        eventLocation = fields[ClaimField.EVENT_LOCATION.apiName],
        contactPhone = fields[ClaimField.CONTACT_PHONE.apiName],
        details =
            details
                .takeIf { it.isNotEmpty() }
                ?.let { map -> JsonObject(map.mapValues { (_, value) -> JsonPrimitive(value) }) },
    )
}
