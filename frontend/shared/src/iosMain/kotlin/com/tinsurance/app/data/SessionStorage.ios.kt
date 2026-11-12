package com.tinsurance.app.data

import platform.Foundation.NSUserDefaults

private object UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun set(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}

actual fun platformKeyValueStore(): KeyValueStore = UserDefaultsStore
