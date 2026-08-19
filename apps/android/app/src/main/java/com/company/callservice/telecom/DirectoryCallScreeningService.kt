package com.company.callservice.telecom

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import com.company.callservice.data.SnapshotFreshnessPolicy
import com.company.callservice.directoryGraph

/**
 * Always allows the incoming call immediately, then performs an optional local-only caller lookup.
 * Network access and JSON parsing are deliberately absent from this five-second system callback.
 */
class DirectoryCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        // This must happen within five seconds. Keep it before disk lookup, UI, or any other work.
        respondToCall(callDetails, ALLOW_CALL_RESPONSE)

        val graph = applicationContext.directoryGraph
        val settings = graph.settingsStore.read()
        if (!settings.callerIdEnabled) return

        val nowEpochMillis = System.currentTimeMillis()
        val checkedAtEpochMillis = graph.snapshotStore.info()?.checkedAtEpochMillis ?: return
        if (!SnapshotFreshnessPolicy.isUsable(nowEpochMillis, checkedAtEpochMillis)) return

        val rawPhoneNumber = callDetails.handle?.schemeSpecificPart ?: return
        val entry = graph.snapshotStore.lookup(
            rawPhoneNumber = rawPhoneNumber,
            defaultCountryCallingCode = settings.defaultCountryCallingCode,
            nowEpochMillis = nowEpochMillis,
        ) ?: return

        CallerIdPresenter.show(this, entry)
    }

    private companion object {
        val ALLOW_CALL_RESPONSE: CallResponse = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
    }
}
