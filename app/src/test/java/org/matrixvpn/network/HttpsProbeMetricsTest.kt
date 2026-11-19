package org.matrixvpn.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsProbeMetricsTest {
    @Test
    fun calculatesMedianAndVariationFromThreeSuccessfulSamples() {
        val result = HttpsProbeMetrics.summarize(
            listOf(
                HttpsProbeSample(200, 100, "on"),
                HttpsProbeSample(200, 150, "on"),
                HttpsProbeSample(200, 120, "on")
            ),
            attempts = 3,
            lastHttpCode = 200,
            lastError = null
        )

        assertTrue(result.reachable)
        assertEquals(3, result.sampleCount)
        assertEquals(3, result.successfulSamples)
        assertEquals(0, result.probeFailurePercent)
        assertEquals(120L, result.latencyMillis)
        assertEquals(40L, result.jitterMillis)
        assertEquals("on", result.warpStatus)
    }

    @Test
    fun oneOfThreeSuccessfulSamplesIsNotStableConnectivity() {
        val result = HttpsProbeMetrics.summarize(
            listOf(HttpsProbeSample(200, 80, "on")),
            attempts = 3,
            lastHttpCode = 200,
            lastError = "timeout"
        )

        assertFalse(result.reachable)
        assertEquals(1, result.successfulSamples)
        assertEquals(67, result.probeFailurePercent)
        assertEquals(80L, result.latencyMillis)
        assertNull(result.jitterMillis)
    }

    @Test
    fun twoOfThreeSuccessfulSamplesRemainReachableButExposeOneFailure() {
        val result = HttpsProbeMetrics.summarize(
            listOf(
                HttpsProbeSample(200, 110, "on"),
                HttpsProbeSample(200, 130, "on")
            ),
            attempts = 3,
            lastHttpCode = 503,
            lastError = "one request returned HTTP 503"
        )

        assertTrue(result.reachable)
        assertEquals(2, result.successfulSamples)
        assertEquals(33, result.probeFailurePercent)
        assertEquals(120L, result.latencyMillis)
        assertEquals(20L, result.jitterMillis)
    }

    @Test
    fun noSuccessfulSamplesHaveNoLatencyOrVariation() {
        val result = HttpsProbeMetrics.summarize(
            emptyList(),
            attempts = 3,
            lastHttpCode = null,
            lastError = "timeout"
        )

        assertFalse(result.reachable)
        assertEquals(100, result.probeFailurePercent)
        assertNull(result.latencyMillis)
        assertNull(result.jitterMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMoreSamplesThanAttempts() {
        HttpsProbeMetrics.summarize(
            listOf(
                HttpsProbeSample(200, 1, null),
                HttpsProbeSample(200, 2, null)
            ),
            attempts = 1,
            lastHttpCode = 200,
            lastError = null
        )
    }
}
