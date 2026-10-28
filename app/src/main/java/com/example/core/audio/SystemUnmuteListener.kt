package com.example.core.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Offline system speech recognizer dedicated to detecting the "unmute" command
 * while the user's microphone is muted.
 * Runs on Android's built-in SpeechRecognizer (Google Speech / On-Device).
 */
class SystemUnmuteListener(
    private val context: Context,
    private val onUnmuteDetected: () -> Unit
) {
    companion object {
        private const val TAG = "SystemUnmuteListener"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var isListening = false
    private var restartRunnable: Runnable? = null

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "SpeechRecognizer ready for unmute listening")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech detected while muted")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
        }

        override fun onError(error: Int) {
            Log.d(TAG, "SpeechRecognizer error: $error")
            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            if (processSpeechResults(results)) {
                return
            }
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            processSpeechResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processSpeechResults(bundle: Bundle?): Boolean {
        if (!isListening || bundle == null) return false
        val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false

        for (phrase in matches) {
            val lower = phrase.lowercase().trim()
            Log.d(TAG, "Heard phrase while muted: '$lower'")
            if (isUnmuteKeyword(lower)) {
                Log.i(TAG, "Matched unmute keyword: '$lower'! Triggering unmute...")
                stopListening()
                onUnmuteDetected()
                return true
            }
        }
        return false
    }

    private fun isUnmuteKeyword(text: String): Boolean {
        return text.contains("unmute") ||
                text.contains("un mute") ||
                text.contains("anmute") ||
                text.contains("mic on") ||
                text.contains("mike on") ||
                text.contains("chalu karo") ||
                text.contains("chalu kar do") ||
                text.contains("kholo mic") ||
                text.contains("awaz suno")
    }

    fun startListening() {
        mainHandler.post {
            if (isListening) return@post
            isListening = true
            cancelScheduledRestart()

            try {
                if (speechRecognizer == null) {
                    if (SpeechRecognizer.isRecognitionAvailable(context)) {
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                            setRecognitionListener(recognitionListener)
                        }
                    } else {
                        Log.w(TAG, "Speech recognition is not available on this device")
                        return@post
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                speechRecognizer?.startListening(intent)
                Log.i(TAG, "Started offline system SpeechRecognizer for unmute detection")
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting SpeechRecognizer: ${e.message}", e)
                scheduleRestart()
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            isListening = false
            cancelScheduledRestart()
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying SpeechRecognizer: ${e.message}")
            }
            speechRecognizer = null
            Log.i(TAG, "Stopped system SpeechRecognizer")
        }
    }

    private fun scheduleRestart() {
        if (!isListening) return
        cancelScheduledRestart()
        restartRunnable = Runnable {
            if (isListening) {
                try {
                    speechRecognizer?.cancel()
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    }
                    speechRecognizer?.startListening(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Restart listening attempt notice: ${e.message}")
                    scheduleRestart()
                }
            }
        }
        mainHandler.postDelayed(restartRunnable!!, 300)
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }
}
