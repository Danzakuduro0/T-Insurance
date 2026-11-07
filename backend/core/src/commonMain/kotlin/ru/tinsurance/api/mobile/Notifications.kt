package ru.tinsurance.api.mobile

import kotlinx.serialization.Serializable

@Serializable
data class NotificationDto(val id: Long, val claimId: Long?, val type: String, val title: String, val body: String, val createdAt: String, val readAt: String?)

@Serializable
data class NotificationsPage(val items: List<NotificationDto>, val nextBeforeId: Long?)
