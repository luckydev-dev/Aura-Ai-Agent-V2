package com.example.core.messaging

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.AuraApplication
import com.example.core.agent.ScreenXmlParser
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraAccessibilityService
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import java.util.Locale

/**
 * Result of a message delivery attempt.
 */
sealed class DeliveryResult {
    data class Sent(val channel: String, val queued: Boolean = false) : DeliveryResult()
    data class Failed(val reason: String) : DeliveryResult()
}

/**
 * Robust, verified single sender for WhatsApp across Maya.
 * Replaces duplicate implementations in CommTools and CallAnnouncementManager.
 */
class WhatsAppSender(
    private val context: Context,
    private val rootManager: RootManager,
    private val preferences: AuraPreferences
) {
    companion object {
        private const val TAG = "Aura"

        // Dialog keywords indicating phone number is not registered on WhatsApp or is invalid
        private val NOT_ON_WHATSAPP_PHRASES = listOf(
            "not on whatsapp",
            "isn't on whatsapp",
            "not a whatsapp user",
            "invite to whatsapp",
            "invalid phone number",
            "phone number is not on whatsapp",
            "whatsapp par nahi hai",
            "अमान्य फ़ोन नंबर",
            "व्हाट्सएप पर नहीं है",
            "आमंत्रित करें",
            "invite"
        )

        suspend fun send(
            context: Context,
            rootManager: RootManager,
            inputController: com.example.core.input.InputController? = null,
            preferences: AuraPreferences,
            target: String,
            message: String,
            isCallRejection: Boolean = false
        ): com.example.core.tools.ToolExecutionResult {
            val sender = WhatsAppSender(context, rootManager, preferences)
            val result = sender.send(target, message, isCallRejection)
            return when (result) {
                is DeliveryResult.Sent -> com.example.core.tools.ToolExecutionResult(true, "WhatsApp sent to $target (queued=${result.queued})")
                is DeliveryResult.Failed -> com.example.core.tools.ToolExecutionResult(false, "WhatsApp failed: ${result.reason}")
            }
        }
    }

    /**
     * Sends a WhatsApp message with end-to-end verification.
     */
    suspend fun send(
        target: String,
        message: String,
        isCallRejection: Boolean = false
    ): DeliveryResult = withContext(Dispatchers.IO) {
        val cleanMsg = message.trim().removeSuffix("/?").removeSuffix("/").trim()
        if (cleanMsg.isBlank()) {
            Log.w(TAG, "WhatsAppSender: Rejected blank message send to $target")
            return@withContext DeliveryResult.Failed("Blank message")
        }

        // 1. Normalize number to E.164 using SIM country or default setting
        val normalizedPhone = normalizeToE164(target)
        if (normalizedPhone.isBlank()) {
            Log.w(TAG, "WhatsAppSender: Could not normalize target '$target' to valid phone number")
            return@withContext DeliveryResult.Failed("INVALID_NUMBER")
        }

        // 2. Pick installed WhatsApp package
        val targetPackage = pickWhatsAppPackage()
        if (targetPackage == null) {
            Log.w(TAG, "WhatsAppSender: Neither WhatsApp nor WhatsApp Business is installed")
            return@withContext DeliveryResult.Failed("WHATSAPP_NOT_INSTALLED")
        }

        // 3. Wake screen and unlock keyguard if needed
        val wakeOk = wakeAndDismissKeyguard()
        if (!wakeOk) {
            Log.w(TAG, "WhatsAppSender: Device is securely locked and keyguard could not be dismissed")
            return@withContext DeliveryResult.Failed("LOCKED")
        }

        // Wait for telephony IDLE (max 3s) if triggered by call reject
        if (isCallRejection) {
            waitForTelephonyIdle(3000L)
        }

        // 4. Launch whatsapp://send with package explicitly set (Text pre-filled)
        val encodedText = try {
            URLEncoder.encode(cleanMsg, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            cleanMsg
        }
        val uri = Uri.parse("whatsapp://send?phone=$normalizedPhone&text=$encodedText")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val a11y = AuraAccessibilityService.instance
        var windowOpened = false

        // 5. Event-based wait for the WhatsApp window (up to 6s)
        val windowWaitJob = async {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 6000L) {
                val topPkg = a11y?.rootInActiveWindow?.packageName?.toString() ?: ""
                if (topPkg == targetPackage) {
                    windowOpened = true
                    break
                }
                delay(200L)
            }
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "WhatsAppSender: startActivity failed, trying root am start: ${e.message}")
            rootManager.execute("am start -a android.intent.action.VIEW -d \"whatsapp://send?phone=$normalizedPhone&text=$encodedText\" $targetPackage")
        }

        windowWaitJob.await()

        if (!windowOpened) {
            // Check top window via dumpsys / root as fallback
            val topWin = rootManager.execute("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").stdout
            if (!topWin.contains(targetPackage)) {
                Log.w(TAG, "WhatsAppSender: Timed out waiting for WhatsApp window to open for $target")
                return@withContext DeliveryResult.Failed("TIMEOUT")
            }
        }

        delay(400L) // Small settle delay for dialog/chat rendering

        // 6. Detect "Not on WhatsApp / Invalid number / Invite" dialog
        val currentScreenNodes = a11y?.extractScreenNodes() ?: ScreenXmlParser.parse(
            rootManager.execute("uiautomator dump /sdcard/ui_wa.xml && cat /sdcard/ui_wa.xml").stdout
        )

        val notOnWhatsappDialog = currentScreenNodes.any { node ->
            val textCombined = "${node.text} ${node.contentDescription}".lowercase(Locale.ROOT)
            NOT_ON_WHATSAPP_PHRASES.any { phrase -> textCombined.contains(phrase) }
        }

        if (notOnWhatsappDialog) {
            Log.i(TAG, "WhatsAppSender: Detected 'Not on WhatsApp' or Invite dialog for $normalizedPhone. Dismissing dialog...")
            // Close the dialog
            dismissCurrentDialog(a11y)
            return@withContext DeliveryResult.Failed("NOT_ON_WHATSAPP")
        }

        // 7. Click send once (reuse findSendButton)
        val sendSuccess = clickSendButton(a11y, currentScreenNodes)
        if (!sendSuccess) {
            Log.w(TAG, "WhatsAppSender: Could not find or click WhatsApp send button for $target")
            return@withContext DeliveryResult.Failed("SEND_BUTTON_NOT_FOUND")
        }

        // 8. Verify delivery within 3 seconds
        val verifyResult = verifyDelivery(a11y, cleanMsg, targetPackage, 3000L)

        // 9. Return to previous app / home
        returnHomeOrPreviousApp(a11y)

        // 10. Return sealed DeliveryResult
        when (verifyResult) {
            is DeliveryResult.Sent -> {
                Log.i(TAG, "WhatsAppSender: VERIFIED SUCCESS for $target (queued=${verifyResult.queued})")
                OutboundMessageLedger.recordSent(target, cleanMsg)
                verifyResult
            }
            is DeliveryResult.Failed -> {
                Log.w(TAG, "WhatsAppSender: Delivery verification failed for $target: ${verifyResult.reason}")
                verifyResult
            }
        }
    }

    private fun normalizeToE164(raw: String): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val simCountry = tm?.simCountryIso?.uppercase(Locale.ROOT)?.ifBlank { "IN" } ?: "IN"

        return try {
            val phoneUtil = PhoneNumberUtil.getInstance()
            val parsed = phoneUtil.parse(raw, simCountry)
            if (phoneUtil.isValidNumber(parsed)) {
                val formatted = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                formatted.removePrefix("+")
            } else {
                fallbackDigits(raw, simCountry)
            }
        } catch (e: Exception) {
            fallbackDigits(raw, simCountry)
        }
    }

    private fun fallbackDigits(raw: String, simCountry: String): String {
        val digits = raw.replace(Regex("""[^\d]"""), "")
        return if (digits.length == 10 && simCountry == "IN") {
            "91$digits"
        } else digits
    }

    private fun pickWhatsAppPackage(): String? {
        val pm = context.packageManager
        val isBusinessPreferred = runBlocking { preferences.isWhatsappBusiness.first() }

        val main = "com.whatsapp"
        val business = "com.whatsapp.w4b"

        val firstChoice = if (isBusinessPreferred) business else main
        val secondChoice = if (isBusinessPreferred) main else business

        return if (isPackageInstalled(pm, firstChoice)) {
            firstChoice
        } else if (isPackageInstalled(pm, secondChoice)) {
            secondChoice
        } else {
            null
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun wakeAndDismissKeyguard(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        // Wake screen
        if (pm?.isInteractive == false) {
            rootManager.execute("input keyevent 224")
            delay(200L)
        }

        if (km?.isKeyguardLocked == true) {
            // Attempt to dismiss keyguard
            rootManager.execute("wm dismiss-keyguard")
            delay(300L)

            if (km.isKeyguardLocked && km.isDeviceSecure) {
                // Try swipe up
                rootManager.execute("input swipe 500 1600 500 400 200")
                delay(300L)
                if (km.isKeyguardLocked) {
                    return false
                }
            }
        }
        return true
    }

    private suspend fun waitForTelephonyIdle(timeoutMs: Long) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (tm.callState == TelephonyManager.CALL_STATE_IDLE) {
                break
            }
            delay(200L)
        }
    }

    private suspend fun dismissCurrentDialog(a11y: AuraAccessibilityService?) {
        val ok = a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
        if (!ok) {
            rootManager.execute("input keyevent 4")
        }
        delay(300L)
    }

    private suspend fun clickSendButton(a11y: AuraAccessibilityService?, nodes: List<com.example.core.agent.ScreenNode>): Boolean {
        // First try via accessibility node inspection
        val sendNode = nodes.find {
            it.resourceId.contains("send", ignoreCase = true) ||
            it.contentDescription.equals("Send", ignoreCase = true) ||
            it.contentDescription.contains("भेजें")
        }

        if (sendNode != null) {
            val tapped = AuraApplication.instance.inputController.tap(
                sendNode.centerX.toFloat(),
                sendNode.centerY.toFloat(),
                "Click WhatsApp Send"
            )
            if (tapped) return true
        }

        // Direct accessibility click
        val a11yNode = a11y?.findNodeByQuery("Send") ?: a11y?.findNodeByQuery("भेजें")
        if (a11yNode != null && a11yNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // Keyevent 66 (Enter) fallback
        val keyRes = AuraApplication.instance.inputController.pressKey(66, "Send Key")
        return keyRes
    }

    private suspend fun verifyDelivery(
        a11y: AuraAccessibilityService?,
        sentText: String,
        targetPackage: String,
        timeoutMs: Long
    ): DeliveryResult {
        val start = System.currentTimeMillis()
        val textSnippet = sentText.take(20).lowercase(Locale.ROOT)

        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(400L)
            val currentNodes = a11y?.extractScreenNodes() ?: ScreenXmlParser.parse(
                rootManager.execute("uiautomator dump /sdcard/ui_wa_verify.xml && cat /sdcard/ui_wa_verify.xml").stdout
            )

            // 1. Verify input field is empty (message dispatched from composer)
            val composerNode = currentNodes.find {
                it.resourceId.contains("entry", ignoreCase = true) ||
                it.resourceId.contains("message_text", ignoreCase = true)
            }
            val inputEmpty = composerNode == null || composerNode.text.isBlank()

            // 2. Check for outgoing bubble containing our message text
            val bubbleFound = currentNodes.any {
                it.text.lowercase(Locale.ROOT).contains(textSnippet) ||
                it.contentDescription.lowercase(Locale.ROOT).contains(textSnippet)
            }

            // 3. Check for pending / clock icon (meaning message is queued on device)
            val isPendingClock = currentNodes.any {
                it.contentDescription.contains("pending", ignoreCase = true) ||
                it.contentDescription.contains("clock", ignoreCase = true)
            }

            if (bubbleFound || inputEmpty) {
                return DeliveryResult.Sent("whatsapp", queued = isPendingClock)
            }
        }

        return DeliveryResult.Failed("VERIFICATION_FAILED_TIMEOUT")
    }

    private suspend fun returnHomeOrPreviousApp(a11y: AuraAccessibilityService?) {
        val ok = a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) ?: false
        if (!ok) {
            rootManager.execute("input keyevent 3")
        }
    }
}
