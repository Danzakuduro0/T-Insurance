package ru.tinsurance.server.common

import io.ktor.server.routing.*

fun RoutingCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw NotFoundException()
