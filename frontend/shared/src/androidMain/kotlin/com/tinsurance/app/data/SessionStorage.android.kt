package com.tinsurance.app.data

import android.content.Context
import android.content.SharedPreferences

private var preferences: SharedPreferences? = null

/** Вызывается из MainActivity до первого экрана: хранилищу нужен Context приложения. */
fun initSessionStorage(context: Context) {
    preferences = context.applicationContext.getSharedPreferences("tinsurance_session", Context.MODE_PRIVATE)
}

private class PreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun set(key: String, value: String?) {
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

// Без initSessionStorage (тесты, @Preview) сессия живёт только в памяти.
actual fun platformKeyValueStore(): KeyValueStore =
    preferences?.let(::PreferencesStore) ?: MemoryStore()
