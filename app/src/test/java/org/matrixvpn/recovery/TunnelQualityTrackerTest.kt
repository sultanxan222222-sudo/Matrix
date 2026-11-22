package org.matrixvpn.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelQualityTrackerTest {
    @Test
    fun oneFailureDoesNotTriggerFailoverAndSuccessResetsCounter() {
        val tracker = TunnelQualityTracker(failoverFailureThreshold = 3)
        assertFalse(tracker.record(false, 0).shouldFailover)
        assertFalse(tracker.record(true, 180).shouldFailover)
        assertEquals(0, tracker.record(true, 220).consecutiveFailures)
    }

    @Test
    fun failoverRequiresThreeConsecutiveFailedProbes() {
        val tracker = TunnelQualityTracker(failoverFailureThreshold = 3)
        tracker.record(false, 0)
        tracker.record(false, 0)
        val snapshot = tracker.record(false, 0)
        assertEquals(TunnelQuality.UNREACHABLE, snapshot.quality)
        assertEquals(3, snapshot.consecutiveFailures)
        assertTrue(snapshot.shouldFailover)
    }

    @Test
    fun highLatencyIsMarkedDegradedWithoutFailover() {
        val tracker = TunnelQualityTracker(degradedLatencyMs = 2_500)
        val snapshot = tracker.record(true, 4_500)
        assertEquals(TunnelQuality.DEGRADED, snapshot.quality)
        assertFalse(snapshot.shouldFailover)
    }
}
