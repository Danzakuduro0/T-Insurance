package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.ParticipantDto
import ru.tinsurance.api.ParticipantInput
import ru.tinsurance.server.common.normalizePhone
import ru.tinsurance.server.db.ClaimParticipants

object ParticipantRepository {

    fun list(claimId: Long): List<ParticipantDto> =
        ClaimParticipants.selectAll()
            .where { ClaimParticipants.claimId eq claimId }
            .orderBy(ClaimParticipants.id)
            .map {
                ParticipantDto(
                    id = it[ClaimParticipants.id],
                    role = it[ClaimParticipants.role],
                    fullName = it[ClaimParticipants.fullName],
                    phone = it[ClaimParticipants.phone],
                    vehicle = it[ClaimParticipants.vehicle] as? JsonObject,
                    insurer = it[ClaimParticipants.insurer],
                    note = it[ClaimParticipants.note],
                )
            }

    fun exists(claimId: Long): Boolean =
        ClaimParticipants.selectAll().where { ClaimParticipants.claimId eq claimId }.any()

    fun replace(claimId: Long, participants: List<ParticipantInput>) {
        ClaimParticipants.deleteWhere { ClaimParticipants.claimId eq claimId }
        participants.forEach { p ->
            ClaimParticipants.insert {
                it[ClaimParticipants.claimId] = claimId
                it[role] = p.role
                it[fullName] = p.fullName.trim()
                it[phone] = p.phone?.let(::normalizePhone)
                it[vehicle] = p.vehicle
                it[insurer] = p.insurer
                it[note] = p.note
            }
        }
    }
}
