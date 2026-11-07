package ru.tinsurance.server

import io.ktor.client.request.*
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.tinsurance.server.jobs.*
import java.time.Duration
import kotlin.test.*

/** Очередь задач и воркер на крайних случаях: сбой при завершении, «зависшие» задачи, повторная постановка. */
class JobWorkerTest {

    private class FailingHandler : JobHandler {
        override val type = JobType.SEND_NOTIFICATION
        override suspend fun handle(job: Job) = JobOutcome.Failed("Нет получателя")
        override suspend fun giveUp(job: Job, error: String) = error("Сбой при завершении")
    }

    private val config = WorkerConfig(
        enabled = false, pollInterval = Duration.ofSeconds(1), batchSize = 10, concurrency = 1,
        lease = Duration.ofMinutes(5), maintenanceInterval = Duration.ofMinutes(1),
    )

    /** Приложение поднимается ради миграций; задачи других тестов снимаются, чтобы воркер их не взял. */
    private fun workerTest(block: suspend (JobWorker) -> Unit) = apiTest { http ->
        http.get("/health")
        sql("update background_jobs set status = 'DONE' where status in ('PENDING', 'RUNNING')")
        block(JobWorker(TestDatabase.database, listOf(FailingHandler()), config))
    }

    private fun insertJob(key: String, status: String, attempts: Int, maxAttempts: Int, lockedHoursAgo: Int? = null) = sql(
        """
        insert into background_jobs (type, payload, dedup_key, status, attempts, max_attempts, run_after, locked_at, locked_by)
        values ('SEND_NOTIFICATION', '{}', '$key', '$status', $attempts, $maxAttempts, now(),
                ${lockedHoursAgo?.let { "now() - interval '$it hours'" } ?: "null"}, ${if (lockedHoursAgo != null) "'dead'" else "null"})
        """.trimIndent()
    )

    private fun jobStatus(key: String) = query("select status from background_jobs where dedup_key = '$key' order by id desc limit 1")

    @Test
    fun errorInGiveUpStillMarksJobFailed() = workerTest { worker ->
        val key = "test-give-up-${System.nanoTime()}"
        insertJob(key, "PENDING", attempts = 0, maxAttempts = 1)
        worker.runReady()
        assertEquals("FAILED", jobStatus(key))
    }

    @Test
    fun staleJobWithoutAttemptsLeftFailsInsteadOfRunningAgain() = workerTest { worker ->
        val exhausted = "test-stale-last-${System.nanoTime()}"
        val retryable = "test-stale-retry-${System.nanoTime()}"
        insertJob(exhausted, "RUNNING", attempts = 3, maxAttempts = 3, lockedHoursAgo = 1)
        insertJob(retryable, "RUNNING", attempts = 1, maxAttempts = 3, lockedHoursAgo = 1)

        worker.maintenance()
        assertEquals("PENDING", jobStatus(exhausted))
        assertEquals("PENDING", jobStatus(retryable))
        worker.runReady()
        assertEquals("FAILED", jobStatus(exhausted))
        assertEquals("3", query("select attempts from background_jobs where dedup_key = '$exhausted'"))
    }

    @Test
    fun sameKeyIsQueuedOnceWhileActiveAndAgainAfterCompletion() = workerTest { _ ->
        val key = "test-dedup-${System.nanoTime()}"
        fun enqueue() = transaction(TestDatabase.database) { JobQueue.enqueue(JobType.SEND_NOTIFICATION, buildJsonObject { }, key) }

        enqueue()
        enqueue()
        assertEquals("1", query("select count(*) from background_jobs where dedup_key = '$key'"))
        sql("update background_jobs set status = 'DONE' where dedup_key = '$key'")
        enqueue()
        assertEquals("2", query("select count(*) from background_jobs where dedup_key = '$key'"))
    }
}
