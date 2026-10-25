package ru.tinsurance.api

import kotlinx.serialization.Serializable

// Даты передаются строками ISO-8601, например "2026-10-10T12:00:00+07:00".

@Serializable
data class ErrorResponse(val error: String, val message: String? = null)

@Serializable
data class Page<T>(val items: List<T>, val total: Long, val limit: Int, val offset: Long)
