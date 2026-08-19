package com.company.callservice.data

object AutoSyncPolicy {
    const val MINIMUM_INTERVAL_MILLIS = 15 * 60 * 1_000L

    fun shouldAttempt(
        nowEpochMillis: Long,
        lastSuccessfulCheckEpochMillis: Long,
        lastAutoAttemptEpochMillis: Long,
    ): Boolean {
        val latestRelevantEvent = listOf(
            lastSuccessfulCheckEpochMillis,
            lastAutoAttemptEpochMillis,
        ).filter { it in 1L..nowEpochMillis }.maxOrNull() ?: return true

        return nowEpochMillis - latestRelevantEvent >= MINIMUM_INTERVAL_MILLIS
    }
}
