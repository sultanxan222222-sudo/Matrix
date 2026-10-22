package org.matrixvpn.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryPolicyTest {
    private val policy = RecoveryPolicy()

    @Test fun keepsCurrentWhenCandidateIsSlower() {
        assertEquals(RecoveryDecision.KeepCurrent, policy.decide(PathSnapshot("a", PathStatus.HEALTHY, 40), listOf(PathSnapshot("b", PathStatus.HEALTHY, 70))))
    }

    @Test fun switchesToFasterHealthyCandidate() {
        assertEquals(RecoveryDecision.SwitchTo("b"), policy.decide(PathSnapshot("a", PathStatus.HEALTHY, 100), listOf(PathSnapshot("b", PathStatus.HEALTHY, 30))))
    }

    @Test fun probesUnknownCandidateWhenCurrentFailed() {
        assertEquals(RecoveryDecision.ProbeCandidate("b"), policy.decide(PathSnapshot("a", PathStatus.FAILED), listOf(PathSnapshot("b", PathStatus.UNKNOWN))))
    }

    @Test fun prefersHealthyCandidateOverUnverifiedOne() {
        assertEquals(RecoveryDecision.SwitchTo("c"), policy.decide(PathSnapshot("a", PathStatus.FAILED), listOf(PathSnapshot("b", PathStatus.UNKNOWN), PathSnapshot("c", PathStatus.HEALTHY, 80))))
    }

    @Test fun doesNotSwitchToCheckingCandidate() {
        assertEquals(RecoveryDecision.NoUsablePath, policy.decide(PathSnapshot("a", PathStatus.FAILED), listOf(PathSnapshot("b", PathStatus.CHECKING))))
    }
}
