package com.example.servermonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMetricsTest {
    @Test
    fun networkRateFormatterUsesReadableUnits() {
        assertEquals("0 B/s", formatNetworkRate(0.0))
        assertEquals("850 B/s", formatNetworkRate(850.0))
        assertEquals("1.5 KB/s", formatNetworkRate(1_500.0))
        assertEquals("2.00 MB/s", formatNetworkRate(2_000_000.0))
    }

    @Test
    fun networkRateFormatterRejectsNegativeAndNonFiniteValues() {
        assertEquals("0 B/s", formatNetworkRate(-10.0))
        assertEquals("0 B/s", formatNetworkRate(Double.NaN))
        assertEquals("0 B/s", formatNetworkRate(Double.POSITIVE_INFINITY))
    }

    @Test
    fun trafficFreshnessRequiresRecentPacketTimestamp() {
        assertTrue(networkTrafficFresh(0L))
        assertTrue(networkTrafficFresh(5_000L))
        assertFalse(networkTrafficFresh(null))
        assertFalse(networkTrafficFresh(5_001L))
        assertFalse(networkTrafficFresh(-1L))
    }
}
