package ru.tinsurance.server.files

import java.io.InputStream

interface FileStorage : AutoCloseable {
    fun put(key: String, content: ByteArray)
    fun open(key: String): InputStream
    fun delete(key: String)
    override fun close() = Unit
}

internal fun requireValidStorageKey(key: String) {
    require(STORAGE_KEY_PATTERN.matches(key)) { "Недопустимый ключ файла" }
}

private val STORAGE_KEY_PATTERN = Regex("""^[a-z0-9]+(/[a-z0-9-]+)*\.[a-z0-9]{2,4}$""")
