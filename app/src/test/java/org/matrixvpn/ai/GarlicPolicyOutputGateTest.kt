package org.matrixvpn.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarlicPolicyOutputGateTest {
    @Test
    fun acceptsKeepCurrentWithNoAlternateProfiles() {
        val advice = GarlicPolicyOutputGate.validate(
            """{"action":"KEEP_CURRENT","profile_id":null,"confidence":0.96,"reason_code":"HTTPS_HEALTHY"}""",
            emptySet()
        )
        assertEquals(GarlicPolicyOutputGate.Action.KEEP_CURRENT, advice.action)
        assertNull(advice.profileId)
        assertEquals("HTTPS_HEALTHY", advice.reasonCode)
    }

    @Test
    fun acceptsOnlyAnExplicitlyWhitelistedCandidate() {
        val advice = GarlicPolicyOutputGate.validate(
            """{"action":"PROBE_PROFILE","profile_id":"candidate-b","confidence":0.73,"reason_code":"CURRENT_PATH_SLOW"}""",
            setOf("candidate-b")
        )
        assertEquals(GarlicPolicyOutputGate.Action.PROBE_PROFILE, advice.action)
        assertEquals("candidate-b", advice.profileId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsModelInventedProfileId() {
        GarlicPolicyOutputGate.validate(
            """{"action":"PROPOSE_FAILOVER","profile_id":"invented-profile","confidence":0.99,"reason_code":"SWITCH_NOW"}""",
            emptySet()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownActionAndNeverRunsArbitraryTool() {
        GarlicPolicyOutputGate.validate(
            """{"action":"EXECUTE_COMMAND","profile_id":null,"confidence":1.0,"reason_code":"RUN"}""",
            emptySet()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedFields() {
        GarlicPolicyOutputGate.validate(
            """{"action":"KEEP_CURRENT","profile_id":null,"confidence":0.9,"reason_code":"OK","shell":"rm -rf"}""",
            emptySet()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteConfidence() {
        GarlicPolicyOutputGate.validate(
            """{"action":"KEEP_CURRENT","profile_id":null,"confidence":1e309,"reason_code":"OK"}""",
            emptySet()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun keepCurrentMustNotNameCandidate() {
        GarlicPolicyOutputGate.validate(
            """{"action":"KEEP_CURRENT","profile_id":"candidate-a","confidence":0.9,"reason_code":"OK"}""",
            setOf("candidate-a")
        )
    }
}
