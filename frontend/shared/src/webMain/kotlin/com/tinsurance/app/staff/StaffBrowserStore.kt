@file:OptIn(ExperimentalWasmJsInterop::class)

package com.tinsurance.app.staff

import com.tinsurance.app.data.KeyValueStore
import kotlin.js.ExperimentalWasmJsInterop

internal object StaffBrowserStore : KeyValueStore {
    override fun get(key: String) = readSessionItem(key)

    override fun set(key: String, value: String?) {
        if (value == null) removeSessionItem(key) else writeSessionItem(key, value)
    }
}

private fun readSessionItem(key: String): String? =
    js("(function () { try { return window.sessionStorage.getItem(key) } catch (e) { return null } })()")

private fun writeSessionItem(key: String, value: String) {
    js("try { window.sessionStorage.setItem(key, value) } catch (e) {}")
}

private fun removeSessionItem(key: String) {
    js("try { window.sessionStorage.removeItem(key) } catch (e) {}")
}
