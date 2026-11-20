package com.tinsurance.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.tinsurance.app.client.ClientScreen
import com.tinsurance.app.operator.OperatorScreen
import com.tinsurance.app.admin.AdminScreen
import web.location.location

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Direct links are useful when comparing each screen with Figma.
    val clientScreen =
        ClientScreen.entries.firstOrNull {
            it.name.equals(location.hash.removePrefix("#"), ignoreCase = true)
        }
    val operatorScreen = OperatorScreen.entries.firstOrNull {
        "Operator${it.name}".equals(location.hash.removePrefix("#"), ignoreCase = true)
    }
    val adminScreen = AdminScreen.entries.firstOrNull {
        "Admin${it.name}".equals(location.hash.removePrefix("#"), ignoreCase = true)
    }
    ComposeViewport {
        // Рамка телефона 390×844 — только по прямой ссылке на экран (сравнение с макетом);
        // обычный запуск подстраивается под ширину окна.
        App(showStatusBar = clientScreen != null, initialScreen = clientScreen ?: ClientScreen.Claims,
            initialOperatorScreen = operatorScreen,
            initialAdminScreen = adminScreen, showDemo = location.hash.equals("#Demo", ignoreCase = true))
    }
}
