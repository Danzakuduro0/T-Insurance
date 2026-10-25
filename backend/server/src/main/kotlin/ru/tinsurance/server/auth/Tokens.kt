package ru.tinsurance.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import ru.tinsurance.server.common.AppMode
import ru.tinsurance.server.common.stringOrNull
import io.ktor.server.config.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/** Откуда вход: у каждого канала своя аудитория JWT, провайдер аутентификации и срок жизни сессии. */
enum class Channel(val clientType: String, val audience: String, val authProvider: String) {
    MOBILE("MOBILE", "tstrah-mobile", AUTH_MOBILE),
    WEB("WEB", "tstrah-operator", AUTH_OPERATOR),
}

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val accessTokenTtl: Duration,
    val mobileRefreshTtl: Duration,
    val webRefreshTtl: Duration,
) {
    fun refreshTtl(channel: Channel) = if (channel == Channel.MOBILE) mobileRefreshTtl else webRefreshTtl

    companion object {
        private const val MIN_SECRET_LENGTH = 32

        /** PROD: без JWT_SECRET сервер не стартует. DEV: без секрета — случайный до перезапуска. */
        fun from(config: ApplicationConfig, mode: AppMode): JwtConfig {
            val secret = config.stringOrNull("jwt.secret") ?: when (mode) {
                AppMode.PROD -> error("JWT_SECRET не задан. В режиме prod он обязателен (не короче $MIN_SECRET_LENGTH символов)")
                AppMode.DEV -> {
                    LoggerFactory.getLogger(JwtConfig::class.java)
                        .warn("JWT_SECRET не задан — используется случайный секрет до перезапуска (режим dev)")
                    ByteArray(48).also(SecureRandom()::nextBytes).let { Base64.getEncoder().encodeToString(it) }
                }
            }
            require(secret.length >= MIN_SECRET_LENGTH) { "JWT_SECRET должен быть не короче $MIN_SECRET_LENGTH символов" }
            return JwtConfig(
                secret = secret,
                issuer = config.property("jwt.issuer").getString(),
                accessTokenTtl = Duration.ofMinutes(config.property("jwt.accessTokenTtlMinutes").getString().toLong()),
                mobileRefreshTtl = Duration.ofDays(config.property("jwt.mobileRefreshTtlDays").getString().toLong()),
                webRefreshTtl = Duration.ofHours(config.property("jwt.webRefreshTtlHours").getString().toLong()),
            )
        }
    }
}

class TokenService(val config: JwtConfig) {
    private val algorithm = Algorithm.HMAC256(config.secret)
    private val random = SecureRandom()

    fun verifier(channel: Channel): JWTVerifier =
        JWT.require(algorithm).withIssuer(config.issuer).withAudience(channel.audience).build()

    fun accessToken(userId: Long, role: String, sessionId: Long, channel: Channel): String =
        JWT.create()
            .withIssuer(config.issuer)
            .withAudience(channel.audience)
            .withSubject(userId.toString())
            .withClaim(CLAIM_ROLE, role)
            .withClaim(CLAIM_SESSION, sessionId)
            .withExpiresAt(Date.from(Instant.now().plus(config.accessTokenTtl)))
            .sign(algorithm)

    /** Случайная строка; в БД хранится только её SHA-256. */
    fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(refreshToken: String): String =
        MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val CLAIM_ROLE = "role"
        const val CLAIM_SESSION = "sid"
    }
}

object PasswordHasher {
    private const val COST = 10

    // Сверяемся с этим хешем, если пользователь не найден: ответ занимает столько же времени,
    // и по задержке нельзя понять, существует ли такой телефон/email.
    private val dummyHash = hash("dummy-password-for-timing")

    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(COST, password.toCharArray())

    fun verify(password: String, hash: String?): Boolean {
        val result = BCrypt.verifyer().verify(password.toCharArray(), hash ?: dummyHash)
        return hash != null && result.verified
    }
}
