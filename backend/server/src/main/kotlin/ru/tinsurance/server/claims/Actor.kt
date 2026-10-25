package ru.tinsurance.server.claims

import ru.tinsurance.api.ActorType

data class Actor(val type: ActorType, val id: Long?) {
    companion object {
        val SYSTEM = Actor(ActorType.SYSTEM, null)
        fun client(id: Long) = Actor(ActorType.CLIENT, id)
    }
}
