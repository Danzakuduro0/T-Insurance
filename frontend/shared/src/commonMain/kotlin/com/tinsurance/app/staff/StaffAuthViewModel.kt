package com.tinsurance.app.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.StaffApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.tinsurance.api.UserDto

internal sealed interface StaffAuthState {
    data object Restoring : StaffAuthState

    data class SignedOut(
        val error: String? = null,
        val signingIn: Boolean = false,
        val canRetryRestore: Boolean = false,
    ) : StaffAuthState

    data class SignedIn(val user: UserDto, val signingOut: Boolean = false) : StaffAuthState
}

internal class StaffAuthViewModel(private val api: StaffApiClient) : ViewModel() {
    private val _state = MutableStateFlow<StaffAuthState>(StaffAuthState.Restoring)
    val state = _state.asStateFlow()

    init {
        restore()
    }

    fun login(email: String, password: String) {
        val state = _state.value as? StaffAuthState.SignedOut ?: return
        if (state.signingIn || email.isBlank() || password.isBlank()) return

        _state.value = StaffAuthState.SignedOut(signingIn = true)
        viewModelScope.launch {
            _state.value = try {
                StaffAuthState.SignedIn(api.login(email, password))
            } catch (error: ApiException) {
                StaffAuthState.SignedOut(error.message, canRetryRestore = api.hasStoredSession)
            }
        }
    }

    fun retryRestore() {
        val state = _state.value as? StaffAuthState.SignedOut ?: return
        if (state.canRetryRestore && !state.signingIn) restore()
    }

    fun logout() {
        val state = _state.value as? StaffAuthState.SignedIn ?: return
        if (state.signingOut) return

        _state.value = state.copy(signingOut = true)
        viewModelScope.launch {
            _state.value = try {
                api.logout()
                StaffAuthState.SignedOut()
            } catch (error: ApiException) {
                StaffAuthState.SignedOut(error.message)
            }
        }
    }

    private fun restore() {
        _state.value = StaffAuthState.Restoring
        viewModelScope.launch {
            _state.value = try {
                api.restore()?.let { StaffAuthState.SignedIn(it) } ?: StaffAuthState.SignedOut()
            } catch (error: ApiException) {
                StaffAuthState.SignedOut(error.message, canRetryRestore = api.hasStoredSession)
            }
        }
    }

    override fun onCleared() = api.close()
}
