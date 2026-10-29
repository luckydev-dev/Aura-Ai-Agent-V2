package com.example.service

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * VoiceInteractionService implementation making Aura selectable and operable as the
 * device's default digital assistant.
 */
class AuraVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "Aura"
        var isActive = false
            private set
    }

    override fun onReady() {
        super.onReady()
        isActive = true
        Log.i(TAG, "AuraVoiceInteractionService: onReady — Aura is active default assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        isActive = false
        Log.i(TAG, "AuraVoiceInteractionService: onShutdown")
    }
}
