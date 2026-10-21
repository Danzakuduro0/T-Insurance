package ru.tinsurance.server.auth

import ru.tinsurance.server.common.ApiException
import io.ktor.http.*
import io.ktor.server.config.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class TooManyAttemptsException(val retryAfterSeconds: Long) :
    ApiException(HttpStatusCode.TooManyRequests, "TOO_MANY_ATTEMPTS", "Слишком много неудачных попыток входа. Попробуйте через $retryAfterSeconds с")

data class LoginThrottleConfig(
    val maxFailures: Int,
    val window: Duration,
    val lock: Duration,
) {
    companion object {
        fun from(config: ApplicationConfig) = LoginThrottleConfig(
            maxFailures = config.propertyOrNull("security.maxFailedLogins")?.getString()?.toInt() ?: 5,
            window = Duration.ofMinutes(config.propertyOrNull("security.failedLoginWindowMinutes")?.getString()?.toLong() ?: 15),
            lock = Duration.ofMinutes(config.propertyOrNull("security.loginLockMinutes")?.getString()?.toLong() ?: 5),
        )
    }
}

/**
 * Защита от перебора пароля для конкретного логина (телефон / email):
 * после maxFailures неудач за window вход по этому логину блокируется на lock — даже с верным паролем.
 * Перебор с одного адреса по многим логинам ограничивает RateLimit на /auth/… (Security.kt).
 * Хранится в памяти процесса: для одного экземпляра сервера этого достаточно.
 */
class LoginThrottle(private val config: LoginThrottleConfig, private val now: () -> Instant = Instant::now) {

    private class State {
        val failures = ArrayDeque<Instant>()
        var lockedUntil: Instant? = null
    }

    private val states = ConcurrentHashMap<String, State>()

    fun checkAllowed(key: String) {
        val state = states[key] ?: return
        synchronized(state) {
            val until = state.lockedUntil ?: return
            val left = Duration.between(now(), until).seconds
            if (left > 0) throw TooManyAttemptsException(left)
            state.lockedUntil = null
        }
    }

    fun onFailure(key: String) {
        cleanupIfLarge()
        val state = states.computeIfAbsent(key) { State() }
        synchronized(state) {
            val t = now()
            state.failures.addLast(t)
            while (state.failures.isNotEmpty() && state.failures.first() < t - config.window) state.failures.removeFirst()
            if (state.failures.size >= config.maxFailures) {
                state.lockedUntil = t + config.lock
                state.failures.clear()
            }
        }
    }

    fun onSuccess(key: String) {
        states.remove(key)
    }

    /** Не даём таблице расти бесконечно от перебора случайных логинов. */
    private fun cleanupIfLarge() {
        if (states.size < MAX_TRACKED) return
        val t = now()
        states.entries.removeIf { (_, s) ->
            synchronized(s) {
                (s.lockedUntil == null || s.lockedUntil!! < t) && (s.failures.lastOrNull()?.let { it < t - config.window } ?: true)
            }
        }
    }

    private companion object {
        const val MAX_TRACKED = 10_000
    }
}
