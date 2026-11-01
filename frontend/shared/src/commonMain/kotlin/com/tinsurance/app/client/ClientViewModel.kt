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
import kotlinx.coroutines.launch
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.UserDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.ClaimListItem
import ru.tinsurance.api.mobile.TimelineEvent

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

class ClientViewModel(private val api: MobileApiClient = MobileApiClient()) : ViewModel() {
    var user by mutableStateOf<UserDto?>(null)
        private set

    var login by mutableStateOf(LoginState())
        private set

    var claims by mutableStateOf(ClaimsState())
        private set

    var claim by mutableStateOf(ClaimState())
        private set

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
                        if (e.unauthorized) {
                            user = null
                            login = LoginState(error = e.message)
                        }
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
                    if (e.unauthorized) {
                        user = null
                        login = LoginState(error = e.message)
                    }
                    claims.copy(loading = false, error = e.message)
                }
        }
    }
}
