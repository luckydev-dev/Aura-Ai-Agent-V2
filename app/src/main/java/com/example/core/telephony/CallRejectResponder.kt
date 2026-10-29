package com.example.core.telephony

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.example.core.messaging.DeliveryResult
import com.example.core.messaging.OutboundMessageLedger
import com.example.core.messaging.SmsSender
import com.example.core.messaging.WhatsAppSender
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Executes automatic call rejection and sequential auto-reply (WhatsApp-first, SMS-fallback).
 * Ensures exactly one message reaches the caller and honours all user settings.
 */
class CallRejectResponder(
    private val context: Context,
    private val preferences: AuraPreferences,
    private val rootManager: RootManager,
    private val whatsAppSender: WhatsAppSender = WhatsAppSender(context, rootManager, preferences),
    private val smsSender: SmsSender = SmsSender(context)
) {
    companion object {
        private const val TAG = "Aura"

        suspend fun handleAutoReject(
            context: Context,
            rootManager: RootManager,
            preferences: AuraPreferences,
            session: CallSession,
            rejectAction: suspend () -> Boolean
        ): Boolean {
            val responder = CallRejectResponder(context, preferences, rootManager)
            return responder.processCallSession(session, rejectAction)
        }

        suspend fun sendMessage(
            context: Context,
            rootManager: RootManager,
            preferences: AuraPreferences,
            session: CallSession,
            message: String
        ): Boolean {
            val responder = CallRejectResponder(context, preferences, rootManager)
            return responder.sendRejectionMessage(session, message)
        }
    }

    suspend fun sendRejectionMessage(session: CallSession, message: String): Boolean = withContext(Dispatchers.IO) {
        val rawNumber = session.number?.trim() ?: ""
        if (rawNumber.isBlank() || isHiddenNumber(rawNumber)) {
            return@withContext false
        }
        val replyWhatsapp = preferences.callAutoReplyWhatsapp.first()
        val replySms = preferences.callAutoReplySms.first()

        var result: DeliveryResult = DeliveryResult.Failed("NONE")
        if (replyWhatsapp) {
            result = whatsAppSender.send(rawNumber, message, isCallRejection = true)
        }
        if (result is DeliveryResult.Failed && replySms) {
            result = smsSender.send(rawNumber, message)
        }
        result is DeliveryResult.Sent
    }

    suspend fun processCallSession(
        session: CallSession,
        rejectCallAction: suspend () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        // Atomic guard: run auto-reject exactly once per session
        if (!session.handled.compareAndSet(false, true)) {
            Log.i(TAG, "CallRejectResponder: Session ${session.id} already handled. Skipping duplicate auto-reject.")
            return@withContext false
        }

        val rawNumber = session.number?.trim() ?: ""
        val callerName = session.name?.trim() ?: "Unknown Caller"

        Log.i(TAG, "CallRejectResponder: Executing auto-reject for session ${session.id} (number='$rawNumber', name='$callerName', isVoip=${session.isVoip})")

        // 1. Reject the incoming call immediately
        val rejected = rejectCallAction()
        Log.i(TAG, "CallRejectResponder: Call reject action returned $rejected")

        // Hidden / Private numbers: reject only, send no message
        if (rawNumber.isBlank() || isHiddenNumber(rawNumber)) {
            Log.i(TAG, "CallRejectResponder: Caller number is hidden or blank ('$rawNumber'). Rejected call, no message will be sent.")
            return@withContext true
        }

        // Per-number cooldown check (60s)
        if (!CallSessionTracker.instance.checkAndRecordCooldown(rawNumber)) {
            Log.i(TAG, "CallRejectResponder: Cooldown active for number '$rawNumber'. Skipping auto-reply message.")
            return@withContext true
        }

        // Check "only reply to saved contacts" setting
        val onlySavedContacts = preferences.callAutoReplyOnlyContacts.first()
        if (onlySavedContacts && !isContactSaved(rawNumber)) {
            Log.i(TAG, "CallRejectResponder: Setting 'only reply to saved contacts' is active and '$rawNumber' is not in contacts. Skipping message.")
            return@withContext true
        }

        val busyMessage = preferences.callAutoRejectMessage.first().ifBlank { "Mai busy hu, baad me call karta hu." }.trim()

        val replyWhatsapp = preferences.callAutoReplyWhatsapp.first()
        val replySms = preferences.callAutoReplySms.first()

        var finalResult: DeliveryResult = DeliveryResult.Failed("NO_CHANNEL_ENABLED")

        // 2. WhatsApp first
        if (replyWhatsapp) {
            if (OutboundMessageLedger.canSend(rawNumber, busyMessage)) {
                Log.i(TAG, "CallRejectResponder: Attempting WhatsApp dispatch to $rawNumber...")
                finalResult = whatsAppSender.send(rawNumber, busyMessage, isCallRejection = true)
            } else {
                Log.i(TAG, "CallRejectResponder: WhatsApp send blocked by OutboundMessageLedger (already sent recently).")
                return@withContext true
            }
        }

        // 3. SMS fallback if WhatsApp failed or was disabled
        if (finalResult is DeliveryResult.Failed && replySms) {
            val waReason = (finalResult as DeliveryResult.Failed).reason
            Log.i(TAG, "CallRejectResponder: WhatsApp delivery failed ($waReason). Falling back to SMS for $rawNumber...")

            if (OutboundMessageLedger.canSend(rawNumber, busyMessage)) {
                finalResult = smsSender.send(rawNumber, busyMessage)
            } else {
                Log.i(TAG, "CallRejectResponder: SMS fallback blocked by OutboundMessageLedger.")
                return@withContext true
            }
        }

        // 4. Log one line summary of send/reject/skip decision with reason
        when (finalResult) {
            is DeliveryResult.Sent -> {
                Log.i(TAG, "CallRejectResponder: SUCCESS sent busy message to $rawNumber via ${finalResult.channel} (queued=${finalResult.queued}): '$busyMessage'")
                // Inform Live model with INFO prefix so model does not repeat the message
                AuraForegroundService.currentService?.let { service ->
                    if (AuraForegroundService.isCallActive.value &&
                        service.liveClient.connectionState.value == com.example.core.live.LiveConnectionState.Connected
                    ) {
                        service.liveClient.sendTextMessage(
                            "[INFO — already handled by the app, do not repeat this action.] " +
                                    "[SYSTEM EVENT: Auto-rejected call from $callerName ($rawNumber). Busy message sent via ${finalResult.channel}: \"$busyMessage\"]"
                        )
                    }
                }
            }
            is DeliveryResult.Failed -> {
                Log.w(TAG, "CallRejectResponder: FAILED sending message to $rawNumber: ${finalResult.reason}")
            }
        }

        return@withContext true
    }

    private fun isHiddenNumber(number: String): Boolean {
        val lower = number.lowercase()
        return lower.contains("private") || lower.contains("unknown") || lower.contains("hidden") ||
                lower.contains("restricted") || number.replace(Regex("""[^\d]"""), "").length < 3
    }

    private fun isContactSaved(phoneNumber: String): Boolean {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )
            cursor?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
