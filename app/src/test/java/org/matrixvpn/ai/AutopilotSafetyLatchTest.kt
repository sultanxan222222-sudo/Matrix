package org.matrixvpn.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutopilotSafetyLatchTest {
    @Test
    fun emergencyStopInvalidatesPreviouslyIssuedPermission() {
        val latch = AutopilotSafetyLatch()
        val token = latch.enable()
        assertTrue(latch.isActive(token))

        latch.emergencyStop()

        assertFalse(latch.isActive(token))
        assertTrue(latch.isStopped())
    }

    @Test
    fun onlyExplicitNewStartReleasesEmergencyStop() {
        val latch = AutopilotSafetyLatch()
        val oldToken = latch.enable()
        latch.emergencyStop()

        val newToken = latch.enable()

        assertFalse(latch.isActive(oldToken))
        assertTrue(latch.isActive(newToken))
        assertFalse(latch.isStopped())
    }
}
