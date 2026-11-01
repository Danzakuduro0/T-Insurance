package ru.tinsurance.server.notifications

import ru.tinsurance.api.ClaimStatus

object NotificationPolicy {
    private val important = setOf(ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO, ClaimStatus.IN_REVIEW, ClaimStatus.COMPLETED, ClaimStatus.CANCELLED)

    fun channels(status: ClaimStatus): List<String> = when {
        status !in important -> emptyList()
        status == ClaimStatus.NEEDS_INFO -> listOf("IN_APP", "PUSH", "SMS")
        else -> listOf("IN_APP", "PUSH")
    }
}
