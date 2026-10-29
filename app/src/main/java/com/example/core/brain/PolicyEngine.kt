package com.example.core.brain

import android.content.Context
import android.util.Log
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.ContactProfileEntity
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PolicyDecision(
    val action: String, // "reply", "ignore", "ask_user", "notify"
    val text: String = "",
    val reason: String = ""
)

/**
 * Own Brain: Evaluates events using local deterministic rules first (DND, VIPs, quiet hours),
 * followed by Gemini Flash REST for intelligent contextual decisions without requiring an active Live socket.
 */
class PolicyEngine(
    private val context: Context,
    private val preferences: AuraPreferences,
    private val memoryRepository: MemoryRepository
) {
    companion object {
        private const val TAG = "Aura"
        private const val GEMINI_REST_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Evaluates an incoming message event.
     */
    suspend fun evaluateMessage(
        sender: String,
        text: String,
        packageName: String
    ): PolicyDecision = withContext(Dispatchers.IO) {
        // 1. Contact profile check (VIP, Relationship, Tone)
        val profile = memoryRepository.findContactProfile(sender)
        if (profile?.relationship?.equals("vip", ignoreCase = true) == true) {
            Log.i(TAG, "PolicyEngine: Deterministic rule VIP contact '$sender' -> notify priority")
            return@withContext PolicyDecision("notify", reason = "VIP contact")
        }

        // 2. Quiet hours check (e.g. 11 PM to 7 AM)
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isQuietHours = hour >= 23 || hour < 7
        if (isQuietHours) {
            Log.i(TAG, "PolicyEngine: Quiet hours active ($hour:00). Rule -> contextual polite busy reply")
            val quietReply = if (profile != null) "Hey ${profile.name}, abhi late ho gaya hai, kal subah reply karta hu." else "Mai abhi available nahi hu, subah baat karte hai."
            return@withContext PolicyDecision("reply", text = quietReply, reason = "Quiet hours auto-reply")
        }

        // 3. Busy Mode: Contextual reply generation via Gemini Flash REST
        val isBusy = preferences.notifAutoReplyBusy.first()
        if (isBusy) {
            val owner = preferences.ownerName.first().ifBlank { "Boss" }
            val contextualReply = generateContextualReplyWithFlash(
                ownerName = owner,
                sender = sender,
                incomingText = text,
                profile = profile
            )
            return@withContext contextualReply
        }

        PolicyDecision("notify", reason = "Standard notification")
    }

    /**
     * Calls Gemini Flash over REST with strict JSON schema to generate a contextual, natural reply.
     */
    private suspend fun generateContextualReplyWithFlash(
        ownerName: String,
        sender: String,
        incomingText: String,
        profile: ContactProfileEntity?
    ): PolicyDecision {
        val apiKey = preferences.apiKey.first()
        if (apiKey.isBlank()) {
            return PolicyDecision("reply", text = "Mai busy hu, baad me text karta hu.", reason = "Default busy (no API key)")
        }

        return try {
            val systemPrompt = """
                You are Maya, ${ownerName}'s personal assistant and companion on his Android phone.
                An incoming message arrived from '$sender': "$incomingText".
                Relationship notes: ${profile?.relationship ?: "contact"}, Preferred tone: ${profile?.preferredTone ?: "casual"}.
                Owner is currently busy. Decide the best action and response.
                Output ONLY a valid JSON object matching this exact schema:
                {
                  "action": "reply",
                  "text": "short natural Hinglish 1-sentence reply in feminine tone explaining owner is busy",
                  "reason": "short explanation"
                }
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Sender: $sender\nMessage: $incomingText"))
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", systemPrompt))
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.3)
                    put("maxOutputTokens", 150)
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = requestJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("$GEMINI_REST_URL?key=$apiKey")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val root = JSONObject(respStr)
                val textCandidate = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val parsed = JSONObject(textCandidate)
                val action = parsed.optString("action", "reply")
                val replyText = parsed.optString("text", "Mai abhi busy hu, thodi der me baat karta hu.")
                val reason = parsed.optString("reason", "Flash policy reasoning")

                Log.i(TAG, "PolicyEngine: Flash generated contextual reply for '$sender': '$replyText' (reason: $reason)")
                PolicyDecision(action = action, text = replyText, reason = reason)
            } else {
                Log.w(TAG, "PolicyEngine: Flash REST call HTTP ${response.code}, falling back to static busy message")
                PolicyDecision("reply", text = "Mai abhi busy hu, baad me text karta hu.", reason = "HTTP fallback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "PolicyEngine: Error in Flash REST call: ${e.message}", e)
            PolicyDecision("reply", text = "Mai abhi busy hu, baad me text karta hu.", reason = "Exception fallback")
        }
    }
}
