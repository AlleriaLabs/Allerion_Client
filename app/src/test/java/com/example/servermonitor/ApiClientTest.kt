package com.example.servermonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {
    @Test
    fun rejectsBlankApiTokens() {
        val error = try {
            validateApiToken("  \n")
            null
        } catch (expected: ApiException) {
            expected
        }

        assertNotNull(error)
        assertTrue(error!!.message!!.contains("API token is required"))
    }

    @Test
    fun acceptsNonBlankApiTokens() {
        validateApiToken("allerion-test-token")
    }

    @Test
    fun pollDelayUsesBaseIntervalWithoutFailures() {
        assertEquals(2_000L, pollDelay(2_000L, 0))
    }

    @Test
    fun pollDelayAppliesBoundedExponentialBackoff() {
        assertEquals(4_000L, pollDelay(2_000L, 1))
        assertEquals(64_000L, pollDelay(2_000L, 5))
        assertEquals(64_000L, pollDelay(2_000L, 6))
    }

    @Test
    fun pollDelayClampsBaseAndMaximum() {
        assertEquals(1_000L, pollDelay(10L, 0))
        assertEquals(60_000L, pollDelay(90_000L, 0))
        assertEquals(300_000L, pollDelay(60_000L, 5))
    }
}
