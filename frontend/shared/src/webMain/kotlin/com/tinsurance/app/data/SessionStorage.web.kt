@file:OptIn(ExperimentalWasmJsInterop::class)

package com.tinsurance.app.data

import kotlin.js.ExperimentalWasmJsInterop

// localStorage может быть недоступен (приватный режим, запрет в настройках) — тогда работаем без сохранения.
private fun storageGet(key: String): String? =
    js("(function () { try { return window.localStorage.getItem(key) } catch (e) { return null } })()")

private fun storageSet(key: String, value: String) {
    js("try { window.localStorage.setItem(key, value) } catch (e) {}")
}

private fun storageRemove(key: String) {
    js("try { window.localStorage.removeItem(key) } catch (e) {}")
}

private object BrowserStore : KeyValueStore {
    override fun get(key: String) = storageGet(key)

    override fun set(key: String, value: String?) {
        if (value == null) storageRemove(key) else storageSet(key, value)
    }
}

actual fun platformKeyValueStore(): KeyValueStore = BrowserStore
