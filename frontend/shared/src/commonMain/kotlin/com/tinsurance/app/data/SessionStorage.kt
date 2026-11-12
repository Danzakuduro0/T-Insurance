package com.tinsurance.app.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Простое хранилище строк, которое переживает перезапуск приложения. */
interface KeyValueStore {
    fun get(key: String): String?

    /** null — удалить значение. */
    fun set(key: String, value: String?)
}

/** Для тестов, @Preview и случая, когда хранилище платформы недоступно. */
class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Web — localStorage, Android — SharedPreferences приложения, iOS — NSUserDefaults. */
expect fun platformKeyValueStore(): KeyValueStore

/**
 * То, что нужно, чтобы не входить заново после перезапуска: refresh-токен и id установки.
 * Access-токен не хранится: он живёт 15 минут и получается заново по refresh-токену.
 */
@OptIn(ExperimentalUuidApi::class)
class SessionStorage(private val store: KeyValueStore = platformKeyValueStore()) {
    /** Одноразовый: после каждого обновления сервер выдаёт новый, старый перестаёт работать. */
    var refreshToken: String?
        get() = store.get(RefreshToken)
        set(value) = store.set(RefreshToken, value)

    /** Один на установку приложения: по нему сервер узнаёт устройство при повторном входе. */
    val installationId: String
        get() = store.get(InstallationId) ?: Uuid.random().toString().also { store.set(InstallationId, it) }

    private companion object {
        const val RefreshToken = "tinsurance.refreshToken"
        const val InstallationId = "tinsurance.installationId"
    }
}
