package ru.tinsurance.server.analysis

import io.ktor.server.config.*
import ru.tinsurance.server.common.minutes
import ru.tinsurance.server.common.seconds
import ru.tinsurance.server.common.string
import ru.tinsurance.server.common.stringOrNull
import java.time.Duration

data class AnalysisConfig(
    /** адрес ML-сервиса; null — локальная проверка качества без классификации повреждений */
    val mlUrl: String?,
    /** раздел 6.5: ML-сервис не ответил за 10 с — задача повторяется */
    val timeout: Duration,
    val modelVersion: String,
    /** раздел 6.5: паузы между повторами 30 / 90 / 270 с, потом — «анализ недоступен» */
    val retryDelays: List<Duration>,
    /** сколько обращение ждёт анализ фото, прежде чем уйти оператору без него */
    val maxWait: Duration,
    /** как часто задача ROUTE_CLAIM перепроверяет, закончен ли анализ (завершение анализа будит её раньше) */
    val routeRecheck: Duration,
) {
    fun retryDelay(attempt: Int): Duration = retryDelays.getOrElse(attempt - 1) { retryDelays.last() }

    companion object {
        fun from(config: ApplicationConfig) = AnalysisConfig(
            mlUrl = config.stringOrNull("analysis.mlUrl")?.trim(),
            timeout = config.seconds("analysis.timeoutSeconds", 10),
            modelVersion = config.string("analysis.modelVersion", "latest"),
            retryDelays = config.string("analysis.retryDelaysSeconds", "30,90,270")
                .split(',').map { Duration.ofSeconds(it.trim().toLong()) },
            maxWait = config.minutes("analysis.maxWaitMinutes", 15),
            routeRecheck = config.seconds("analysis.routeRecheckSeconds", 30),
        )
    }
}
