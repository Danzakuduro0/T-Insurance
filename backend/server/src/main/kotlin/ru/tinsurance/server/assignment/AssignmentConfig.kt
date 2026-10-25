package ru.tinsurance.server.assignment

import io.ktor.server.config.*
import ru.tinsurance.server.common.boolean
import ru.tinsurance.server.common.int

data class AssignmentConfig(
    val autoAssign: Boolean,
    /** Лимит обращений в работе, если у оператора не задан свой. */
    val defaultMaxActiveClaims: Int,
) {
    companion object {
        fun from(config: ApplicationConfig) = AssignmentConfig(
            autoAssign = config.boolean("assignment.autoAssign", true),
            defaultMaxActiveClaims = config.int("assignment.defaultMaxActiveClaims", 5),
        )
    }
}
