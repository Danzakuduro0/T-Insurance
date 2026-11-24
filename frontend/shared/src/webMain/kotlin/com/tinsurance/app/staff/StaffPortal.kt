package com.tinsurance.app.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinsurance.app.admin.AdminApp
import com.tinsurance.app.data.StaffApiClient
import com.tinsurance.app.operator.OperatorApp
import com.tinsurance.app.ui.Caption
import com.tinsurance.app.ui.InsuranceButton
import com.tinsurance.app.ui.InsuranceCard
import com.tinsurance.app.ui.InsuranceField
import com.tinsurance.app.ui.InsuranceTheme
import com.tinsurance.app.ui.Label
import com.tinsurance.app.ui.PageTitle
import com.tinsurance.app.ui.ScreenBody
import com.tinsurance.app.ui.TextLink
import ru.tinsurance.api.Role

@Composable
internal fun StaffPortal(onClient: () -> Unit) {
    val auth = viewModel { StaffAuthViewModel(StaffApiClient(StaffBrowserStore)) }
    val state by auth.state.collectAsStateWithLifecycle()

    InsuranceTheme {
        when (val current = state) {
            StaffAuthState.Restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Caption("Восстанавливаем сессию…")
                }
            }
            is StaffAuthState.SignedOut -> StaffLoginScreen(current, auth::login, auth::retryRestore, onClient)
            is StaffAuthState.SignedIn -> StaffCabinet(current, auth::logout, onClient)
        }
    }
}

@Composable
private fun StaffLoginScreen(
    state: StaffAuthState.SignedOut,
    onLogin: (String, String) -> Unit,
    onRetry: () -> Unit,
    onClient: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ScreenBody(Modifier.widthIn(max = 460.dp)) {
            InsuranceCard(Modifier.fillMaxWidth(), padding = 20.dp, spacing = 16.dp) {
                PageTitle("Вход для сотрудников")
                InsuranceField("Email", email, { email = it }, keyboard = KeyboardType.Email)
                InsuranceField("Пароль", password, { password = it }, password = true, error = state.error)
                InsuranceButton(
                    text = if (state.signingIn) "Входим…" else "Войти",
                    onClick = { onLogin(email, password) },
                    enabled = !state.signingIn && email.isNotBlank() && password.isNotBlank(),
                )
                if (state.canRetryRestore) TextLink("Восстановить сессию", onRetry)
                TextLink("К клиентскому кабинету", onClient)
            }
        }
    }
}

@Composable
private fun StaffCabinet(state: StaffAuthState.SignedIn, onLogout: () -> Unit, onClient: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label(state.user.fullName)
                Caption("Демонстрационные данные")
            }
            TextButton(onClick = onLogout, enabled = !state.signingOut) {
                Text(if (state.signingOut) "Выходим…" else "Выйти")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.user.role) {
                Role.OPERATOR -> OperatorApp(onClient = onClient)
                Role.ADMIN -> AdminApp(onClient = onClient)
                Role.CLIENT -> Unit
            }
        }
    }
}
