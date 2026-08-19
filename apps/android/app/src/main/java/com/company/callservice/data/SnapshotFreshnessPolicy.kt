package com.company.callservice.data

/**
 * Keeps caller-ID data from being used indefinitely after access to the directory is revoked.
 * A small future tolerance avoids suppressing results for ordinary device clock drift.
 */
object SnapshotFreshnessPolicy {
    const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
    const val FUTURE_CLOCK_TOLERANCE_MILLIS = 5L * 60 * 1_000

    fun isUsable(nowEpochMillis: Long, checkedAtEpochMillis: Long): Boolean {
        if (nowEpochMillis <= 0L || checkedAtEpochMillis <= 0L) return false

        return if (checkedAtEpochMillis > nowEpochMillis) {
            checkedAtEpochMillis - nowEpochMillis <= FUTURE_CLOCK_TOLERANCE_MILLIS
        } else {
            nowEpochMillis - checkedAtEpochMillis <= MAX_AGE_MILLIS
        }
    }
}
