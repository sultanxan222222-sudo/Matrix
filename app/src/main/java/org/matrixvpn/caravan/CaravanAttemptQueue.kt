package org.matrixvpn.caravan

/**
 * Deterministic, single-use route queue. A relay is marked attempted before
 * the caller starts it, so callbacks/timeouts cannot start the same relay twice.
 */
class CaravanAttemptQueue(
    candidates: List<VpnGateServer>,
    preferredId: String? = null
) {
    private val ordered = buildList {
        if (preferredId != null) candidates.firstOrNull { it.id == preferredId }?.let(::add)
        candidates.filterNot { it.id == preferredId }.forEach(::add)
    }.distinctBy { it.id }

    private val attempted = linkedSetOf<String>()

    fun next(): VpnGateServer? {
        val candidate = ordered.firstOrNull { it.id !in attempted } ?: return null
        attempted.add(candidate.id)
        return candidate
    }

    fun hasNext(): Boolean = ordered.any { it.id !in attempted }
    fun attemptedIds(): Set<String> = attempted.toSet()
}
