package org.matrixvpn.caravan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaravanRecoveryPolicyTest {
    @Test
    fun triesAlternativeAfterServerOrTunnelFailure() {
        assertEquals(
            CaravanAction.TRY_NEXT_ROUTE,
            CaravanRecoveryPolicy.nextAction(CaravanFailure.TRANSPORT_BLOCKED, true)
        )
        assertEquals(
            CaravanAction.TRY_NEXT_ROUTE,
            CaravanRecoveryPolicy.nextAction(CaravanFailure.EGRESS_CHECK_FAILED, true)
        )
    }

    @Test
    fun waitsWhenUnderlyingNetworkIsLostAndStopsOnUserRequest() {
        assertEquals(
            CaravanAction.WAIT_FOR_NETWORK,
            CaravanRecoveryPolicy.nextAction(CaravanFailure.NETWORK_UNAVAILABLE, true)
        )
        assertEquals(
            CaravanAction.STOP,
            CaravanRecoveryPolicy.nextAction(CaravanFailure.USER_CANCELLED, true)
        )
        assertFalse(CaravanRecoveryPolicy.shouldScoutAlternatives(CaravanFailure.USER_CANCELLED))
        assertTrue(CaravanRecoveryPolicy.shouldScoutAlternatives(CaravanFailure.CONNECT_TIMEOUT))
    }
}
