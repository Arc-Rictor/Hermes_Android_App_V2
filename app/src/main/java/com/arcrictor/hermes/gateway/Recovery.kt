package com.arcrictor.hermes.gateway

/** Recovery choices from PROTOCOL.md §5. */
object Recovery {
    sealed interface Plan {
        data object Replay : Plan
        data object ReplayFromZero : Plan
        data class Resume(val reason: String) : Plan
        data object Retry : Plan
    }

    fun decide(
        activateErrorCode: Long? = null,
        truncated: Boolean = false,
        epochChanged: Boolean = false,
    ): Plan = when {
        activateErrorCode == 4001L || activateErrorCode == 4007L ->
            Plan.Resume("runtime is gone")
        activateErrorCode != null -> Plan.Retry
        truncated -> Plan.Resume("event replay truncated")
        epochChanged -> Plan.ReplayFromZero
        else -> Plan.Replay
    }
}
