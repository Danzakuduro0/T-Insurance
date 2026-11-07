package ru.tinsurance.server.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.json.jsonb

// Схемой управляет Flyway (resources/db/migration); описания таблиц должны совпадать с миграциями.

private val dbJson = Json { ignoreUnknownKeys = true }

internal fun Table.jsonbElement(name: String): Column<JsonElement> = jsonb(name, dbJson, JsonElement.serializer())

internal fun Table.jsonbObject(name: String): Column<JsonObject> = jsonb(name, dbJson, JsonObject.serializer())

internal fun Table.textArray(name: String): Column<List<String>> = array(name, TextColumnType())

internal inline fun <reified T : Enum<T>> Table.enumText(name: String): Column<T> = enumerationByName<T>(name, 64)
