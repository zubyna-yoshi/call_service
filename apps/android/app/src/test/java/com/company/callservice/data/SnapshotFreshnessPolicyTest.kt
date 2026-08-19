package com.company.callservice.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFreshnessPolicyTest {
    private val now = 2_000_000_000_000L

    @Test
    fun `accepts snapshot through seven day boundary`() {
        assertTrue(SnapshotFreshnessPolicy.isUsable(now, now))
        assertTrue(
            SnapshotFreshnessPolicy.isUsable(
                now,
                now - SnapshotFreshnessPolicy.MAX_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun `rejects snapshot older than seven days`() {
        assertFalse(
            SnapshotFreshnessPolicy.isUsable(
                now,
                now - SnapshotFreshnessPolicy.MAX_AGE_MILLIS - 1L,
            ),
        )
    }

    @Test
    fun `allows only small future clock drift`() {
        assertTrue(
            SnapshotFreshnessPolicy.isUsable(
                now,
                now + SnapshotFreshnessPolicy.FUTURE_CLOCK_TOLERANCE_MILLIS,
            ),
        )
        assertFalse(
            SnapshotFreshnessPolicy.isUsable(
                now,
                now + SnapshotFreshnessPolicy.FUTURE_CLOCK_TOLERANCE_MILLIS + 1L,
            ),
        )
    }

    @Test
    fun `rejects missing or invalid timestamps`() {
        assertFalse(SnapshotFreshnessPolicy.isUsable(now, 0L))
        assertFalse(SnapshotFreshnessPolicy.isUsable(0L, now))
    }
}
