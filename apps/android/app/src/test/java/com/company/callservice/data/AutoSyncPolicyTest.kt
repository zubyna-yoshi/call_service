package com.company.callservice.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncPolicyTest {
    private val now = 2_000_000_000_000L

    @Test
    fun `allows first configured sync`() {
        assertTrue(AutoSyncPolicy.shouldAttempt(now, 0L, 0L))
    }

    @Test
    fun `skips until fifteen minutes after latest check or attempt`() {
        val fourteenMinutesAgo = now - 14 * 60 * 1_000L
        assertFalse(AutoSyncPolicy.shouldAttempt(now, fourteenMinutesAgo, 0L))
        assertFalse(AutoSyncPolicy.shouldAttempt(now, 0L, fourteenMinutesAgo))
    }

    @Test
    fun `allows at fifteen minute boundary`() {
        val fifteenMinutesAgo = now - AutoSyncPolicy.MINIMUM_INTERVAL_MILLIS
        assertTrue(AutoSyncPolicy.shouldAttempt(now, fifteenMinutesAgo, 0L))
    }

    @Test
    fun `ignores impossible future timestamp`() {
        assertTrue(AutoSyncPolicy.shouldAttempt(now, now + 1_000L, 0L))
    }
}
