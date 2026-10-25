package ru.tinsurance.server.claims

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.operator.DirectionDto
import ru.tinsurance.server.db.Directions

/** Направления обработки. Вызывается внутри транзакции. */
object DirectionRepository {

    fun list(): List<DirectionDto> =
        Directions.selectAll().orderBy(Directions.code)
            .map { DirectionDto(it[Directions.code], it[Directions.name], it[Directions.description]) }

    fun exists(code: String): Boolean = !Directions.selectAll().where { Directions.code eq code }.empty()
}
