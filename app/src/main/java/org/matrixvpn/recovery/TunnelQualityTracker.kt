package org.matrixvpn.recovery

enum class TunnelQuality { UNKNOWN, HEALTHY, DEGRADED, UNREACHABLE }

data class TunnelQualitySnapshot(
    val quality: TunnelQuality,
    val averageLatencyMs: Long,
    val consecutiveFailures: Int,
    val totalSamples: Int,
    val shouldFailover: Boolean
)

/**
 * Stateful, deterministic VPN quality evaluator. It deliberately needs repeated
 * failures before recommending a tunnel switch, avoiding a failover on one noisy probe.
 */
class TunnelQualityTracker(
    private val failoverFailureThreshold: Int = 3,
    private val degradedLatencyMs: Long = 2_500L
) {
    private var averageLatencyMs = 0L
    private var consecutiveFailures = 0
    private var totalSamples = 0

    init {
        require(failoverFailureThreshold >= 1)
        require(degradedLatencyMs > 0L)
    }

    @Synchronized
    fun record(reachable: Boolean, latencyMs: Long): TunnelQualitySnapshot {
        totalSamples++
        if (reachable) {
            consecutiveFailures = 0
            val sample = latencyMs.coerceAtLeast(1L)
            averageLatencyMs = if (averageLatencyMs == 0L) sample
                else (averageLatencyMs * 3L + sample) / 4L
        } else {
            consecutiveFailures++
        }

        val quality = when {
            consecutiveFailures >= failoverFailureThreshold -> TunnelQuality.UNREACHABLE
            consecutiveFailures > 0 -> TunnelQuality.DEGRADED
            averageLatencyMs >= degradedLatencyMs -> TunnelQuality.DEGRADED
            totalSamples > 0 -> TunnelQuality.HEALTHY
            else -> TunnelQuality.UNKNOWN
        }
        return TunnelQualitySnapshot(
            quality = quality,
            averageLatencyMs = averageLatencyMs,
            consecutiveFailures = consecutiveFailures,
            totalSamples = totalSamples,
            shouldFailover = consecutiveFailures >= failoverFailureThreshold
        )
    }

    @Synchronized
    fun reset() {
        averageLatencyMs = 0L
        consecutiveFailures = 0
        totalSamples = 0
    }
}
