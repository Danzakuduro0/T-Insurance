package ru.tinsurance.server.claims

import ru.tinsurance.api.ActorType
import ru.tinsurance.api.Role

data class Actor(val type: ActorType, val id: Long?) {
    companion object {
        val SYSTEM = Actor(ActorType.SYSTEM, null)
        fun client(id: Long) = Actor(ActorType.CLIENT, id)
        fun user(role: Role, id: Long) = Actor(ActorType.valueOf(role.name), id)
    }
}
