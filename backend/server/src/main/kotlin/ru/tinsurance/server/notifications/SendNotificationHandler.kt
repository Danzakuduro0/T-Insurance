package ru.tinsurance.server.notifications

import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.Notifications as N
import ru.tinsurance.server.jobs.*
import java.time.Duration
import java.time.OffsetDateTime

class SendNotificationHandler(private val db: Database, private val sender: NotificationSender) : JobHandler {
    override val type = JobType.SEND_NOTIFICATION

    override suspend fun handle(job: Job): JobOutcome {
        val id = job.long("notificationId")
        val channel = db.tx {
            N.selectAll().where { N.id eq id }.singleOrNull()?.takeIf { it[N.sentAt] == null }?.get(N.channel)
        } ?: return JobOutcome.Done
        if (channel !in setOf("PUSH", "SMS")) return JobOutcome.Failed("Unsupported delivery channel")
        try {
            sender.send(id, channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return JobOutcome.Retry("Notification delivery failed", Duration.ofSeconds(30L * job.attempts.coerceAtLeast(1)))
        }
        db.tx { N.update({ N.id eq id }) { it[sentAt] = OffsetDateTime.now() } }
        return JobOutcome.Done
    }
}
