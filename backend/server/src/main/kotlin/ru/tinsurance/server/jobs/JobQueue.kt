package ru.tinsurance.server.jobs

import ru.tinsurance.server.db.BackgroundJobs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.OffsetDateTime

/** Виды фоновых задач (CHECK в background_jobs.type). maxAttempts — всего запусков, включая первый. */
enum class JobType(val maxAttempts: Int) {
    /** 1 запуск + 3 повтора. */
    ANALYZE_IMAGE(4),
    /** Ждёт анализ фото, поэтому запусков много. */
    ROUTE_CLAIM(20),
    SEND_NOTIFICATION(5),
}

data class Job(val id: Long, val type: JobType, val payload: JsonObject, val attempts: Int, val maxAttempts: Int, val exhausted: Boolean = false) {
    val isLastAttempt get() = attempts >= maxAttempts
    fun long(field: String): Long = payload.getValue(field).jsonPrimitive.long
}

/** Чем закончилась обработка задачи. */
sealed interface JobOutcome {
    data object Done : JobOutcome
    /** Ошибка, после которой стоит повторить (сервис недоступен): попытка засчитывается. */
    data class Retry(val error: String, val delay: Duration) : JobOutcome
    /** Рано выполнять (ждём другие задачи): попытка не засчитывается. */
    data class Later(val delay: Duration) : JobOutcome
    /** Повторять бессмысленно. */
    data class Failed(val error: String) : JobOutcome
}

/**
 * Очередь задач в background_jobs. Задача ставится в той же транзакции, что и породившее её изменение,
 * поэтому при сбое не теряется. Несколько воркеров берут задачи без конфликтов: FOR UPDATE SKIP LOCKED.
 */
object JobQueue {
    private val json = Json

    /** Внутри транзакции. Пока задача с тем же dedupKey ждёт или выполняется, такая же не ставится. */
    fun enqueue(type: JobType, payload: JsonObject, dedupKey: String?, runAfter: OffsetDateTime = OffsetDateTime.now()) {
        BackgroundJobs.insertIgnore {
            it[BackgroundJobs.type] = type.name
            it[BackgroundJobs.payload] = payload
            it[BackgroundJobs.dedupKey] = dedupKey
            it[status] = "PENDING"
            it[attempts] = 0
            it[maxAttempts] = type.maxAttempts
            it[BackgroundJobs.runAfter] = runAfter
            it[createdAt] = OffsetDateTime.now()
        }
    }

    /** Берёт до [limit] готовых задач и помечает их RUNNING за воркером [workerId]. */
    fun claim(tx: JdbcTransaction, workerId: String, limit: Int): List<Job> {
        require(workerId.all { it.isLetterOrDigit() || it == '-' }) { "workerId: только буквы, цифры и дефис" }
        val sql = """
            with ready as (
                select id, attempts >= max_attempts as exhausted from background_jobs
                 where status = 'PENDING' and run_after <= now()
                 order by run_after, id
                 limit ${limit.coerceIn(1, 100)}
                 for update skip locked
            )
            update background_jobs j
               set status = 'RUNNING', locked_at = now(), locked_by = '$workerId',
                   attempts = j.attempts + case when ready.exhausted then 0 else 1 end
              from ready where j.id = ready.id
            returning j.id, j.type, j.payload::text, j.attempts, j.max_attempts, ready.exhausted
        """.trimIndent()
        return tx.exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
            buildList {
                while (rs.next()) {
                    val type = JobType.entries.firstOrNull { it.name == rs.getString(2) } ?: continue
                    add(Job(rs.getLong(1), type, json.parseToJsonElement(rs.getString(3)).jsonObject, rs.getInt(4), rs.getInt(5), rs.getBoolean(6)))
                }
            }
        } ?: emptyList()
    }

    fun complete(id: Long) {
        BackgroundJobs.update({ BackgroundJobs.id eq id }) {
            it[status] = "DONE"
            it[finishedAt] = OffsetDateTime.now()
            it[lockedAt] = null
            it[lockedBy] = null
        }
    }

    fun retry(id: Long, error: String, delay: Duration) {
        BackgroundJobs.update({ BackgroundJobs.id eq id }) {
            it[status] = "PENDING"
            it[lastError] = error.take(1000)
            it[runAfter] = OffsetDateTime.now().plus(delay)
            it[lockedAt] = null
            it[lockedBy] = null
        }
    }

    /** Отложить без расхода попытки: счётчик, увеличенный при взятии задачи, возвращается назад. */
    fun postpone(tx: JdbcTransaction, id: Long, delay: Duration) {
        tx.exec(
            "update background_jobs set status = 'PENDING', attempts = greatest(attempts - 1, 0), " +
                "run_after = now() + interval '${delay.toMillis()} milliseconds', locked_at = null, locked_by = null where id = $id",
        )
    }

    fun fail(id: Long, error: String) {
        BackgroundJobs.update({ BackgroundJobs.id eq id }) {
            it[status] = "FAILED"
            it[lastError] = error.take(1000)
            it[finishedAt] = OffsetDateTime.now()
            it[lockedAt] = null
            it[lockedBy] = null
        }
    }

    /** Исчерпанные задачи тоже возвращаются: воркер выполнит только giveUp, без новой попытки обработки. */
    fun releaseStale(tx: JdbcTransaction, lease: Duration): Int = countUpdated(
        tx,
        "update background_jobs set status = 'PENDING', run_after = now(), locked_at = null, locked_by = null " +
            "where status = 'RUNNING' and locked_at < now() - interval '${lease.toMillis()} milliseconds' returning id",
    )

    private fun countUpdated(tx: JdbcTransaction, sql: String): Int =
        tx.exec(sql, explicitStatementType = StatementType.SELECT) { rs -> var n = 0; while (rs.next()) n++; n } ?: 0

    /** Разбудить ожидающую задачу [type], у которой в payload поле [field] = [value] (например, ROUTE_CLAIM обращения). */
    fun wakeUp(tx: JdbcTransaction, type: JobType, field: String, value: Long) {
        require(field.all { it.isLetterOrDigit() })
        tx.exec(
            "update background_jobs set run_after = now() " +
                "where type = '${type.name}' and status = 'PENDING' and (payload ->> '$field')::bigint = $value",
        )
    }
}
