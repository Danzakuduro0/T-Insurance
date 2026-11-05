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
    val initialScreen =
        ClientScreen.entries.firstOrNull {
            it.name.equals(location.hash.removePrefix("#"), ignoreCase = true)
        } ?: ClientScreen.Claims
    val operatorScreen = OperatorScreen.entries.firstOrNull {
        "Operator${it.name}".equals(location.hash.removePrefix("#"), ignoreCase = true)
    }
    val adminScreen = AdminScreen.entries.firstOrNull {
        "Admin${it.name}".equals(location.hash.removePrefix("#"), ignoreCase = true)
    }
    ComposeViewport {
        App(showStatusBar = true, initialScreen = initialScreen, initialOperatorScreen = operatorScreen,
            initialAdminScreen = adminScreen, showDemo = location.hash.equals("#Demo", ignoreCase = true))
    }
}
