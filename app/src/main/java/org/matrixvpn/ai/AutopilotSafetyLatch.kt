package org.matrixvpn.ai

import java.util.concurrent.atomic.AtomicLong

/**
 * Human-owned stop latch for autonomous VPN selection.
 *
 * Every new run receives a generation token. An emergency stop invalidates all
 * previously issued tokens, so a delayed model/network callback cannot reconnect.
 */
class AutopilotSafetyLatch {
    private val generation = AtomicLong(0L)
    @Volatile private var stopped = true

    fun enable(): Long {
        stopped = false
        return generation.incrementAndGet()
    }

    fun emergencyStop(): Long {
        stopped = true
        return generation.incrementAndGet()
    }

    fun isActive(token: Long): Boolean = !stopped && generation.get() == token

    fun isStopped(): Boolean = stopped
}
