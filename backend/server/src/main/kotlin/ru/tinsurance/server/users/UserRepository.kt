package ru.tinsurance.server.users

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.Role
import ru.tinsurance.api.UserDto
import ru.tinsurance.server.db.Users
import java.time.OffsetDateTime

/** Вызывается внутри транзакции. */
object UserRepository {

    fun get(id: Long): UserDto = Users.selectAll().where { Users.id eq id }.single().toUserDto()

    fun findActive(id: Long): ResultRow? =
        Users.selectAll().where { (Users.id eq id) and (Users.isActive eq true) }.singleOrNull()

    fun findActiveClientByPhone(phone: String): ResultRow? =
        Users.selectAll()
            .where { (Users.phone eq phone) and (Users.role eq Role.CLIENT) and (Users.isActive eq true) }
            .singleOrNull()

    fun findActiveStaffByEmail(email: String): ResultRow? =
        Users.selectAll()
            .where { (Users.email.lowerCase() eq email) and (Users.role inList STAFF) and (Users.isActive eq true) }
            .singleOrNull()

    fun phone(id: Long): String? = Users.select(Users.phone).where { Users.id eq id }.single()[Users.phone]

    /** Имена пользователей одним запросом — для карточек и журналов. */
    fun namesByIds(ids: Collection<Long>): Map<Long, String> =
        if (ids.isEmpty()) emptyMap()
        else Users.select(Users.id, Users.fullName).where { Users.id inList ids.distinct() }
            .associate { it[Users.id] to it[Users.fullName] }

    fun touchLastSeen(id: Long, at: OffsetDateTime) {
        Users.update({ Users.id eq id }) { it[lastSeenAt] = at }
    }

    private val STAFF = listOf(Role.OPERATOR, Role.ADMIN)
}

fun ResultRow.toUserDto() = UserDto(
    id = this[Users.id],
    role = this[Users.role],
    fullName = this[Users.fullName],
    phone = this[Users.phone],
    email = this[Users.email],
)
