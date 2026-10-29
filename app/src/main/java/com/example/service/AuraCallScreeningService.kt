package com.example.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.AuraApplication
import com.example.core.settings.AuraPreferences
import com.example.core.telephony.CallAnnouncementManager
import com.example.core.telephony.CallSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * CallScreeningService implementation for immediate incoming call interception,
 * number extraction, and zero-latency rejection.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AuraCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "Aura"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        try {
            val handle = callDetails.handle
            val rawNumber = handle?.schemeSpecificPart ?: ""
            val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

            if (!isIncoming) return

            Log.i(TAG, "AuraCallScreeningService: onScreenCall incoming from '$rawNumber'")

            val (session, isNew) = CallSessionTracker.instance.onCallRinging(
                number = rawNumber,
                name = null,
                source = "CALL_SCREENING",
                isVoip = false
            )

            val preferences = AuraPreferences(this)
            scope.launch {
                val autoReject = preferences.callAutoRejectEnabled.first()
                if (autoReject) {
                    val response = CallResponse.Builder()
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipNotification(true)
                        .setSkipCallLog(false)
                        .build()

                    respondToCall(callDetails, response)
                    Log.i(TAG, "AuraCallScreeningService: Responded with disallow & reject for call $rawNumber")

                    CallAnnouncementManager.instance?.onCallScreenedAndRejected(session.number ?: rawNumber)
                } else {
                    val response = CallResponse.Builder()
                        .setDisallowCall(false)
                        .setRejectCall(false)
                        .build()
                    respondToCall(callDetails, response)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AuraCallScreeningService: Error screening call: ${e.message}", e)
        }
    }
}
