package ru.tinsurance.server.notifications

import org.slf4j.LoggerFactory

fun interface NotificationSender {
    suspend fun send(notificationId: Long, channel: String)
}

class SimulatedNotificationSender : NotificationSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override suspend fun send(notificationId: Long, channel: String) {
        log.info("Simulated notification delivery: id={}, channel={}", notificationId, channel)
    }
}
