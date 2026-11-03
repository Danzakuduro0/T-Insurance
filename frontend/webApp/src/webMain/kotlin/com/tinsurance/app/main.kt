package com.tinsurance.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.tinsurance.app.client.ClientScreen
import com.tinsurance.app.operator.OperatorScreen
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
    ComposeViewport {
        App(showStatusBar = true, initialScreen = initialScreen, initialOperatorScreen = operatorScreen)
    }
}
