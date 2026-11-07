package ru.tinsurance.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import ru.tinsurance.api.DevicePlatform
import ru.tinsurance.api.OperatorAvailability
import ru.tinsurance.api.Role

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val role = enumText<Role>("role")
    val fullName = text("full_name")
    val email = text("email").nullable()
    val phone = text("phone").nullable()
    val passwordHash = text("password_hash")
    val maxActiveClaims = short("max_active_claims").nullable()
    val isActive = bool("is_active")
    val availability = enumText<OperatorAvailability>("availability").nullable()
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object OperatorDirections : Table("operator_directions") {
    val operatorId = long("operator_id")
    val directionCode = text("direction_code")
    override val primaryKey = PrimaryKey(operatorId, directionCode)
}

object Policies : Table("policies") {
    val id = long("id").autoIncrement()
    val policyNumber = text("policy_number")
    val clientId = long("client_id")
    val product = text("product")
    val insuredObject = jsonbObject("insured_object")
    val validFrom = date("valid_from")
    val validTo = date("valid_to")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object UserDevices : Table("user_devices") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val platform = enumText<DevicePlatform>("platform")
    val installationId = text("installation_id")
    val pushToken = text("push_token").nullable()
    val appVersion = text("app_version").nullable()
    val osVersion = text("os_version").nullable()
    val isActive = bool("is_active")
    val createdAt = timestampWithTimeZone("created_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at")
    override val primaryKey = PrimaryKey(id)
}

object AuthSessions : Table("auth_sessions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val clientType = text("client_type")
    val deviceId = long("device_id").nullable()
    val refreshTokenHash = text("refresh_token_hash")
    val userAgent = text("user_agent").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val lastUsedAt = timestampWithTimeZone("last_used_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
