package ru.tinsurance.server.auth

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.DeviceInfo
import ru.tinsurance.server.db.UserDevices
import java.time.OffsetDateTime

/** Устройства клиентов — для push-уведомлений. Вызывается внутри транзакции. */
object DeviceRepository {

    fun upsert(userId: Long, device: DeviceInfo, at: OffsetDateTime): Long {
        // push-токен принадлежит одному устройству: если он был у другой записи — снимаем его там
        device.pushToken?.let { token -> UserDevices.update({ UserDevices.pushToken eq token }) { it[pushToken] = null } }

        val existingId = UserDevices.select(UserDevices.id)
            .where { (UserDevices.userId eq userId) and (UserDevices.installationId eq device.installationId) }
            .singleOrNull()?.get(UserDevices.id)
        if (existingId != null) {
            UserDevices.update({ UserDevices.id eq existingId }) {
                it[platform] = device.platform
                it[pushToken] = device.pushToken
                it[appVersion] = device.appVersion
                it[osVersion] = device.osVersion
                it[isActive] = true
                it[lastSeenAt] = at
            }
            return existingId
        }
        return UserDevices.insert {
            it[UserDevices.userId] = userId
            it[installationId] = device.installationId
            it[platform] = device.platform
            it[pushToken] = device.pushToken
            it[appVersion] = device.appVersion
            it[osVersion] = device.osVersion
            it[isActive] = true
            it[createdAt] = at
            it[lastSeenAt] = at
        }[UserDevices.id]
    }
}
