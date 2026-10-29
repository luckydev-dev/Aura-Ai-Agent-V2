package com.example.core.messaging

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Verified SMS sender using SubscriptionManager, multipart send with PendingIntent feedback.
 */
class SmsSender(
    private val context: Context
) {
    companion object {
        private const val TAG = "Aura"
        private const val ACTION_SMS_SENT = "com.example.aura.SMS_SENT_ACTION"

        suspend fun send(
            context: Context,
            rootManager: com.example.core.root.RootManager? = null,
            number: String,
            text: String
        ): com.example.core.tools.ToolExecutionResult {
            val sender = SmsSender(context)
            val result = sender.send(number, text)
            return when (result) {
                is DeliveryResult.Sent -> com.example.core.tools.ToolExecutionResult(true, "SMS sent to $number via ${result.channel}")
                is DeliveryResult.Failed -> com.example.core.tools.ToolExecutionResult(false, "Failed to send SMS: ${result.reason}")
            }
        }
    }

    suspend fun send(number: String, text: String): DeliveryResult = withContext(Dispatchers.IO) {
        val cleanNumber = number.replace(Regex("""[^\d+]"""), "").trim()
        val cleanMsg = text.trim()
        if (cleanNumber.isBlank() || cleanMsg.isBlank()) {
            return@withContext DeliveryResult.Failed("INVALID_ARGUMENTS")
        }

        // Check OutboundMessageLedger
        if (!OutboundMessageLedger.canSend(cleanNumber, cleanMsg)) {
            Log.i(TAG, "SmsSender: BLOCKED duplicate SMS to $cleanNumber by OutboundMessageLedger")
            return@withContext DeliveryResult.Failed("DUPLICATE_BLOCKED")
        }

        val actionId = "$ACTION_SMS_SENT.${UUID.randomUUID()}"
        val sentDeferred = CompletableDeferred<Int>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == actionId) {
                    val resultCode = resultCode
                    sentDeferred.complete(resultCode)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(actionId), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(actionId))
            }

            // Get proper SmsManager for default subscription ID
            val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
            val smsManager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sm = context.getSystemService(SmsManager::class.java)
                sm?.createForSubscriptionId(subId) ?: SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(cleanMsg)
            val sentIntents = ArrayList<PendingIntent>()

            for (i in parts.indices) {
                val intent = Intent(actionId).apply {
                    setPackage(context.packageName)
                    putExtra("part_index", i)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    i,
                    intent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                sentIntents.add(pi)
            }

            Log.i(TAG, "SmsSender: Dispatching SMS multipart (${parts.size} parts) to $cleanNumber via subId $subId")
            smsManager.sendMultipartTextMessage(cleanNumber, null, parts, sentIntents, null)

            // Wait up to 10s for RESULT_OK confirmation
            val resCode = withTimeoutOrNull(10_000L) {
                sentDeferred.await()
            }

            if (resCode == Activity.RESULT_OK) {
                Log.i(TAG, "SmsSender: VERIFIED SMS delivered successfully to $cleanNumber (RESULT_OK)")
                OutboundMessageLedger.recordSent(cleanNumber, cleanMsg)
                return@withContext DeliveryResult.Sent("sms")
            } else {
                Log.w(TAG, "SmsSender: SMS send failed with resultCode=$resCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SmsSender: Exception sending SMS via telephony: ${e.message}", e)
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }

        // Last resort: Open default SMS app via smsto: and send with accessibility sender
        val fallbackResult = fallbackViaDefaultSmsApp(cleanNumber, cleanMsg)
        if (fallbackResult is DeliveryResult.Sent) {
            OutboundMessageLedger.recordSent(cleanNumber, cleanMsg)
        }
        fallbackResult
    }

    private suspend fun fallbackViaDefaultSmsApp(number: String, text: String): DeliveryResult {
        Log.i(TAG, "SmsSender: Attempting last-resort fallback via default SMS app (smsto:$number)...")
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val a11y = AuraAccessibilityService.instance
            if (a11y != null) {
                val (ok, _) = a11y.typeAndSendInCurrentChat(text)
                if (ok) {
                    DeliveryResult.Sent("sms_fallback")
                } else {
                    DeliveryResult.Failed("SMS_FALLBACK_CLICK_FAILED")
                }
            } else {
                DeliveryResult.Failed("SMS_APP_OPENED_NO_A11Y")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SmsSender: Fallback SMS via default app failed: ${e.message}", e)
            DeliveryResult.Failed("FALLBACK_FAILED: ${e.message}")
        }
    }
}
