package org.matrixvpn.recovery

enum class PathStatus { UNKNOWN, CHECKING, HEALTHY, FAILED }

data class PathSnapshot(val id: String, val status: PathStatus, val latencyMillis: Long? = null)

sealed interface RecoveryDecision {
    data object KeepCurrent : RecoveryDecision
    data class ProbeCandidate(val candidateId: String) : RecoveryDecision
    data class SwitchTo(val candidateId: String) : RecoveryDecision
    data object NoUsablePath : RecoveryDecision
}

/** Pure policy model: no sockets or VPN tunnel are opened here. */
class RecoveryPolicy {
    fun decide(current: PathSnapshot?, candidates: List<PathSnapshot>): RecoveryDecision {
        val healthy = candidates.filter { it.status == PathStatus.HEALTHY && it.id != current?.id }
            .sortedWith(compareBy<PathSnapshot> { it.latencyMillis ?: Long.MAX_VALUE }.thenBy { it.id })
        if (current?.status == PathStatus.HEALTHY) {
            val latency = current.latencyMillis ?: Long.MAX_VALUE
            val better = healthy.firstOrNull { (it.latencyMillis ?: Long.MAX_VALUE) < latency }
            return better?.let { RecoveryDecision.SwitchTo(it.id) } ?: RecoveryDecision.KeepCurrent
        }
        healthy.firstOrNull()?.let { return RecoveryDecision.SwitchTo(it.id) }
        candidates.firstOrNull { it.id != current?.id && it.status == PathStatus.UNKNOWN }
            ?.let { return RecoveryDecision.ProbeCandidate(it.id) }
        candidates.firstOrNull { it.id != current?.id && it.status == PathStatus.FAILED }
            ?.let { return RecoveryDecision.ProbeCandidate(it.id) }
        return RecoveryDecision.NoUsablePath
    }
}
