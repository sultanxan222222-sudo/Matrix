package org.matrixvpn.recovery

import org.junit.Assert.assertTrue
import org.junit.Test

class NodeHealthScorerTest {
    @Test
    fun recentFastSuccessfulProfileOutranksUntestedProfile() {
        val fast = NodeHealthScorer.score(8, 1, 180, 5 * 60 * 1000L)
        val unknown = NodeHealthScorer.score(0, 0, 0, Long.MAX_VALUE)
        assertTrue(fast > unknown)
    }

    @Test
    fun repeatedFailuresReduceProfileScore() {
        val mostlyWorking = NodeHealthScorer.score(9, 1, 400, 30 * 60 * 1000L)
        val mostlyFailing = NodeHealthScorer.score(1, 9, 400, 30 * 60 * 1000L)
        assertTrue(mostlyWorking > mostlyFailing)
    }
}
