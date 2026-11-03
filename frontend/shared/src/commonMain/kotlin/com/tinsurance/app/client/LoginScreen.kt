package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*

// Экрана входа нет в макете: собран из тех же компонентов, что и остальные экраны.
@Composable
fun LoginScreen(
    state: LoginState,
    onLogin: (phone: String, password: String) -> Unit,
    onOperator: () -> Unit = {},
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScreenBody(spacing = 16.dp) {
                PageTitle("Вход")
                Caption("Войдите, чтобы видеть свои обращения и сообщать о новых случаях.")
                InsuranceField(
                    "Телефон",
                    phone,
                    { phone = it },
                    keyboard = KeyboardType.Phone,
                )
                InsuranceField(
                    "Пароль",
                    password,
                    { password = it },
                    password = true,
                    error = state.error,
                )
                TextLink("Демокабинет оператора", onOperator)
            }
        }
        ActionFooter(
            if (state.loading) "Входим…" else "Войти",
            { onLogin(phone, password) },
            enabled = !state.loading,
        )
    }
}
