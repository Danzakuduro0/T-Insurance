package com.tinsurance.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import web.events.addHandler
import web.location.location
import web.window.hashChangeEvent
import web.window.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        var fragment by remember { mutableStateOf(location.hash.removePrefix("#")) }
        DisposableEffect(Unit) {
            val unsubscribe = window.hashChangeEvent.addHandler { fragment = location.hash.removePrefix("#") }
            onDispose { unsubscribe() }
        }
        key(fragment) {
            WebApp(fragment, navigate = { location.hash = it })
        }
    }
}
