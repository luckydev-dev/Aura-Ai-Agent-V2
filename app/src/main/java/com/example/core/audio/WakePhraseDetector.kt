package com.example.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class WakePhraseDetector(
    private val context: Context,
    private val modelManager: VoskModelManager,
    private val onWakePhraseDetected: () -> Unit
) {
    companion object {
        private const val TAG = "Aura"
        const val SAMPLE_RATE = 16000
    }

    private var isListening = false
    private var listeningJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null

    private var lastTriggerTimestamp = 0L

    private val _lastHeardText = MutableStateFlow("")
    val lastHeardText: StateFlow<String> = _lastHeardText.asStateFlow()

    private val _isDetectorActive = MutableStateFlow(false)
    val isDetectorActive: StateFlow<Boolean> = _isDetectorActive.asStateFlow()

    var sensitivity: String = "normal" // strict, normal, loose
    var assistantName: String = "Maya"

    private val ownerVoiceVerifier = OwnerVoiceVerifier(context)

    private fun buildGrammarJson(): String {
        val name = assistantName.trim().lowercase().ifBlank { "maya" }
        // Require explicit 2-word wake phrases: NO standalone single-word triggers
        val phrases = mutableSetOf(
            "hey $name", "ok $name", "hello $name",
            "[unk]"
        )
        val arr = JSONArray()
        phrases.forEach { arr.put(it) }
        return arr.toString()
    }

    @SuppressLint("MissingPermission")
    fun startListening(scope: CoroutineScope): Boolean {
        if (isListening) return true

        if (!modelManager.isModelReady()) {
            Log.w(TAG, "Cannot start WakePhraseDetector: Vosk model is not downloaded or ready.")
            return false
        }

        try {
            val modelPath = modelManager.getModelDirectory().absolutePath
            if (voskModel == null) {
                voskModel = Model(modelPath)
            }

            val grammar = buildGrammarJson()
            val rec = Recognizer(voskModel, SAMPLE_RATE.toFloat(), grammar)
            recognizer = rec

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                4096
            )

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize for WakePhraseDetector")
                record.release()
                return false
            }

            record.startRecording()
            audioRecord = record
            isListening = true
            _isDetectorActive.value = true

            Log.i(TAG, "WakePhraseDetector listening strictly for two-word phrase 'Hey/Ok $assistantName'")

            listeningJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                while (isActive && isListening) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val isFinal = rec.acceptWaveForm(buffer, read)
                        if (isFinal) {
                            checkWakePhraseMatch(rec.result, buffer, read)
                        } else {
                            // Check partial only if it explicitly contains the full 2-word phrase
                            val partialJson = rec.partialResult
                            if (partialJson.contains("hey $assistantName", ignoreCase = true) ||
                                partialJson.contains("ok $assistantName", ignoreCase = true)
                            ) {
                                checkWakePhraseMatch(partialJson, buffer, read)
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing WakePhraseDetector", e)
            stopListening()
            return false
        }
    }

    private fun checkWakePhraseMatch(jsonStr: String, rawBuffer: ByteArray, readSize: Int) {
        try {
            val json = JSONObject(jsonStr)
            val text = (if (json.has("text")) json.optString("text") else json.optString("partial")).trim().lowercase()
            if (text.isNotBlank() && text != "[unk]") {
                _lastHeardText.value = text
                Log.d(TAG, "Vosk confirmed: $text")

                val targetName = assistantName.trim().lowercase().ifBlank { "maya" }

                // Strictly require explicit 2-word phrase: 'hey maya', 'ok maya', or 'hello maya'
                val validPhrases = listOf("hey $targetName", "ok $targetName", "hello $targetName")
                val matches = validPhrases.any { text.contains(it) }

                if (matches) {
                    val now = System.currentTimeMillis()
                    if (now - lastTriggerTimestamp > 5000L) {
                        // Check owner voice recognition if profile exists
                        if (ownerVoiceVerifier.hasProfile()) {
                            val pcmShorts = ShortArray(readSize / 2)
                            for (i in pcmShorts.indices) {
                                pcmShorts[i] = ((rawBuffer[i * 2 + 1].toInt() shl 8) or (rawBuffer[i * 2].toInt() and 0xFF)).toShort()
                            }
                            val matchResult = ownerVoiceVerifier.verifyOwnerVoice(pcmShorts, pcmShorts.size)
                            if (!matchResult.isMatch) {
                                Log.w(TAG, "🚫 Unrecognized voice attempted to wake Maya: ${matchResult.message}")
                                return
                            } else {
                                Log.i(TAG, "✅ Owner voice verified: ${matchResult.message}")
                            }
                        }

                        lastTriggerTimestamp = now
                        Log.i(TAG, "🎯 Confirmed 2-word wake phrase triggered: '$text'")
                        stopListening()
                        onWakePhraseDetected()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking wake phrase match from JSON: $jsonStr", e)
        }
    }

    fun stopListening() {
        isListening = false
        _isDetectorActive.value = false
        listeningJob?.cancel()
        listeningJob = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audioRecord in WakePhraseDetector", e)
        }
        audioRecord = null

        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk recognizer", e)
        }
        recognizer = null
    }

    fun release() {
        stopListening()
        try {
            voskModel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk model", e)
        }
        voskModel = null
    }
}
