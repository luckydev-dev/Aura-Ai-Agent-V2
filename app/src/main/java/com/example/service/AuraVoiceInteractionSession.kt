package com.example.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.example.AuraApplication

/**
 * VoiceInteractionSession handling assist gestures (long-press home/power, corner swipe).
 * Starts or foregrounds Aura's live voice session and immediately dismisses default system window
 * so Aura's own orb and edge lighting are the exclusive UI.
 */
class AuraVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "Aura"
    }

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false) // No extra system assist panel
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "AuraVoiceInteractionSession: onShow triggered (flags=$showFlags, fromAssistGesture=true)")

        try {
            val isCallActive = AuraForegroundService.isCallActive.value
            val currentService = AuraForegroundService.currentService

            if (isCallActive && currentService != null) {
                // Already in a call: bring orb to front and unmute if muted
                Log.i(TAG, "AuraVoiceInteractionSession: Live call already active. Unmuting and foregrounding overlay.")
                if (currentService.audioStreamer.isMuted.value) {
                    currentService.audioStreamer.setMuted(false)
                }
                AuraApplication.instance.overlayController.showCallOverlays()
            } else {
                // Start call with fromAssistGesture flag for a crisp, punchy greeting
                AuraForegroundService.startCall(context, fromAssistGesture = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AuraVoiceInteractionSession: Failed handling assist trigger: ${e.message}", e)
        } finally {
            // Dismiss system assist window immediately so Aura's custom overlay is the only UI
            hide()
        }
    }
}
