package org.matrixvpn.caravan

/**
 * Single-use route queue. Freshly scouted routes can move forward, but
 * attempted relays are never reintroduced during the same connection run.
 */
class CaravanAttemptQueue(
    candidates: List<VpnGateServer>,
    preferredId: String? = null
) {
    private var ordered = buildList {
        if (preferredId != null) candidates.firstOrNull { it.id == preferredId }?.let(::add)
        candidates.filterNot { it.id == preferredId }.forEach(::add)
    }.distinctBy { it.id }

    private val attempted = linkedSetOf<String>()

    @Synchronized
    fun next(): VpnGateServer? {
        val candidate = ordered.firstOrNull { it.id !in attempted } ?: return null
        attempted.add(candidate.id)
        return candidate
    }

    @Synchronized
    fun prioritize(serverIds: List<String>) {
        if (serverIds.isEmpty()) return
        val priority = serverIds.withIndex().associate { it.value to it.index }
        val tried = ordered.filter { it.id in attempted }
        val pending = ordered.filterNot { it.id in attempted }
        ordered = tried + pending.sortedWith(
            compareBy<VpnGateServer> { priority[it.id] ?: Int.MAX_VALUE }
                .thenByDescending { it.qualityScore }
        )
    }

    @Synchronized
    fun hasNext(): Boolean = ordered.any { it.id !in attempted }

    @Synchronized
    fun attemptedIds(): Set<String> = attempted.toSet()
}
