package com.example.service

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

/**
 * RecognitionService declaration required for Android OS to register Aura
 * in the system digital assistant selection list.
 */
class AuraRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "Aura"
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "AuraRecognitionService: onStartListening")
    }

    override fun onCancel(listener: Callback?) {
        Log.d(TAG, "AuraRecognitionService: onCancel")
    }

    override fun onStopListening(listener: Callback?) {
        Log.d(TAG, "AuraRecognitionService: onStopListening")
    }
}
