package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.core.telephony.CallAnnouncementManager
import com.example.core.telephony.CallSessionTracker

/**
 * BroadcastReceiver for android.intent.action.PHONE_STATE.
 * Extracts incoming caller number provided in EXTRA_INCOMING_NUMBER and merges into CallSessionTracker.
 */
class AuraPhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Aura"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        try {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.i(TAG, "AuraPhoneStateReceiver: Phone state changed to $stateStr (number=${incomingNumber ?: "null"})")

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (!incomingNumber.isNullOrBlank()) {
                        CallSessionTracker.instance.onCallRinging(
                            number = incomingNumber,
                            name = null,
                            source = "PHONE_STATE_RECEIVER",
                            isVoip = false
                        )
                        CallAnnouncementManager.instance?.onPhoneStateNumberDetected(incomingNumber)
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    CallSessionTracker.instance.onCallEnded(5000L)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Call answered
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AuraPhoneStateReceiver error: ${e.message}", e)
        }
    }
}
