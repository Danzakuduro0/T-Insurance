package ru.tinsurance.server.jobs

import ru.tinsurance.server.common.*
import io.ktor.server.config.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.uuid.Uuid

/** Обработчик задач одного вида. */
interface JobHandler {
    val type: JobType
    suspend fun handle(job: Job): JobOutcome
    /** Попытки исчерпаны или повторять бессмысленно — привести данные в конечное состояние. */
    suspend fun giveUp(job: Job, error: String) {}
}

data class WorkerConfig(
    /** false — цикл не запускается (тесты вызывают runReady() сами) */
    val enabled: Boolean,
    val pollInterval: Duration,
    val batchSize: Int,
    /** сколько задач обрабатывается одновременно (в том числе запросов к ML-сервису) */
    val concurrency: Int,
    /** задача RUNNING дольше этого — воркер упал, задача возвращается в очередь */
    val lease: Duration,
    val maintenanceInterval: Duration,
) {
    companion object {
        fun from(config: ApplicationConfig) = WorkerConfig(
            enabled = config.boolean("jobs.enabled", true),
            pollInterval = config.millis("jobs.pollIntervalMs", 1000),
            batchSize = config.int("jobs.batchSize", 10),
            concurrency = config.int("jobs.concurrency", 4),
            lease = config.seconds("jobs.leaseSeconds", 300),
            maintenanceInterval = config.seconds("jobs.maintenanceSeconds", 60),
        )
    }
}

/**
 * Фоновый воркер: берёт готовые задачи из background_jobs и передаёт обработчикам.
 * Ошибка обработчика не роняет воркер — задача повторяется позже; после последней попытки вызывается giveUp.
 */
class JobWorker(
    private val db: Database,
    handlers: List<JobHandler>,
    private val config: WorkerConfig,
    /** периодические проверки: вернуть «зависшие» задачи, поставить пропущенные */
    private val maintenanceTasks: List<suspend () -> Unit> = emptyList(),
) {
    private val log = LoggerFactory.getLogger(JobWorker::class.java)
    private val handlers = handlers.associateBy { it.type }
    private val permits = Semaphore(config.concurrency)
    val id = "worker-${Uuid.random().toHexString().take(12)}"

    /** Обработать все задачи, готовые сейчас; возвращает число обработанных. */
    suspend fun runReady(): Int {
        var processed = 0
        repeat(MAX_ROUNDS) {
            val jobs = db.tx { JobQueue.claim(this, this@JobWorker.id, config.batchSize) }
            if (jobs.isEmpty()) return processed
            coroutineScope { jobs.forEach { job -> launch { permits.withPermit { process(job) } } } }
            processed += jobs.size
        }
        return processed
    }

    suspend fun maintenance() {
        val released = db.tx { JobQueue.releaseStale(this, config.lease) }
        if (released > 0) log.warn("Возвращено в очередь зависших задач: $released")
        maintenanceTasks.forEach { it() }
    }

    fun start(scope: CoroutineScope): kotlinx.coroutines.Job = scope.launch(Dispatchers.IO + CoroutineName("job-worker")) {
        log.info("Фоновый воркер $id запущен")
        var lastMaintenance = 0L
        while (isActive) {
            try {
                if (System.currentTimeMillis() - lastMaintenance >= config.maintenanceInterval.toMillis()) {
                    maintenance()
                    lastMaintenance = System.currentTimeMillis()
                }
                runReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Ошибка цикла фонового воркера", e)
            }
            delay(config.pollInterval.toMillis())
        }
    }

    private suspend fun process(job: Job) {
        val handler = handlers[job.type]
        if (handler == null) {
            db.tx { JobQueue.fail(job.id, "Нет обработчика для ${job.type}") }
            return
        }
        if (job.exhausted) {
            giveUpAndFail(handler, job, "Воркер не завершил последнюю попытку")
            return
        }
        val outcome = try {
            handler.handle(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Задача ${job.type} #${job.id}: ${e.message}", e)
            JobOutcome.Retry(e.message ?: e::class.simpleName.orEmpty(), UNEXPECTED_ERROR_DELAY)
        }
        when (outcome) {
            JobOutcome.Done -> db.tx { JobQueue.complete(job.id) }
            is JobOutcome.Later -> db.tx { JobQueue.postpone(this, job.id, outcome.delay) }
            is JobOutcome.Retry ->
                if (job.isLastAttempt) giveUpAndFail(handler, job, outcome.error)
                else db.tx { JobQueue.retry(job.id, outcome.error, outcome.delay) }
            is JobOutcome.Failed -> giveUpAndFail(handler, job, outcome.error)
        }
    }

    /** Ошибка в giveUp не должна оставить задачу в RUNNING: она всё равно помечается FAILED. */
    private suspend fun giveUpAndFail(handler: JobHandler, job: Job, error: String) {
        try {
            handler.giveUp(job, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Задача ${job.type} #${job.id}: ошибка при завершении после последней попытки", e)
        }
        db.tx { JobQueue.fail(job.id, error) }
    }

    private companion object {
        /** защита от бесконечного цикла, если задачи появляются быстрее, чем обрабатываются */
        const val MAX_ROUNDS = 100
        val UNEXPECTED_ERROR_DELAY: Duration = Duration.ofSeconds(30)
    }
}
