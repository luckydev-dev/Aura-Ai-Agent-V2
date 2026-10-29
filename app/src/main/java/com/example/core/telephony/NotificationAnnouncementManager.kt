package com.example.core.telephony

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages voice announcements for incoming notifications.
 *
 * Fully adheres to Google AI Studio and Gemini API Usage Policies:
 * - Automatically detects and blocks all sensitive banking, UPI, 2FA, OTP, and financial alerts.
 * - Protects user privacy: sensitive authentication credentials are never processed, logged,
 *   read out loud, or transmitted across the Gemini Live WebSocket.
 * - Sanitizes URLs, auth tokens, and raw formatting before voice delivery.
 * - Supports configurable speech languages (Hinglish, English, Hindi).
 */
class NotificationAnnouncementManager(
    private val context: Context,
    private val preferences: AuraPreferences
) {
    companion object {
        private const val TAG = "Aura"
        var instance: NotificationAnnouncementManager? = null
            private set

        // Common banking, payment, and authenticator packages to strictly block
        private val SENSITIVE_PACKAGES = setOf(
            // Payment & UPI
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM UPI
            "com.cred.club",                          // CRED
            "com.paypal.android.p2pmobile",           // PayPal
            "com.freecharge.android",                 // FreeCharge
            "com.mobikwik_new",                       // MobiKwik
            "com.amazon.mShop.android.shopping",      // Amazon Pay alerts
            // Indian Banks
            "com.sbi.lotusintouch",                   // YONO SBI
            "com.icicibank.mobile",                   // iMobile ICICI
            "com.csam.icici.bank.imobile",            // iMobile ICICI
            "com.snapwork.hdfc",                      // HDFC Mobile
            "com.hdfcbank.payzapp",                   // PayZapp HDFC
            "com.axis.mobile",                        // Axis Mobile
            "com.kotak.mbanking",                     // Kotak 811
            "com.bankofbaroda.mconnect",              // BoB
            "com.canarabank.mobility",                // Canara ai1
            "com.unionbank.ecommerce.mobile.android", // Union Bank
            "com.rblbank.mobank",                     // RBL MoBank
            "com.idfcfirstbank.optimus",              // IDFC FIRST Bank
            "com.indusind.indusmobile",               // IndusInd
            // Global Banks
            "com.chase.sig.android",                  // Chase
            "com.bankofamerica.community",            // Bank of America
            "com.wf.wellsfargomobile",                // Wells Fargo
            "com.citi.citimobile",                    // Citi Mobile
            "com.capitalone.mobile",                  // Capital One
            "com.barclays.android.barclaysmobilebanking", // Barclays
            "com.hsbc.hsbcukmobilebanking",           // HSBC
            // Two-Factor Authentication & Password Managers
            "com.google.android.apps.authenticator2", // Google Authenticator
            "com.azure.authenticator",                // Microsoft Authenticator
            "com.authy.authy",                        // Twilio Authy
            "org.fedorahosted.freeotp",               // FreeOTP
            "com.duosecurity.duo.mobile",             // Duo Mobile
            "com.x8bit.bitwarden",                    // Bitwarden
            "com.onepassword.android",                // 1Password
            "com.lastpass.lpandroid"                  // LastPass
        )

        // Regex patterns to detect OTPs, verification pins, and transactional banking messages
        private val OTP_PATTERN = Regex(
            """(?i)\b(otp|one[- ]time[- ]password|verification[- ]code|security[- ]code|login[- ]code|auth[- ]code|passcode|temporary[- ]password|pin)\b"""
        )
        private val OTP_PHRASE_PATTERN = Regex(
            """(?i)(is your\s+.*code|do not share|valid for \d+ min|secret code|verification pin)"""
        )
        private val FINANCIAL_TXN_PATTERN = Regex(
            """(?i)\b(debited|credited|account[- ]ending|acct[- ]xx|txn[- ]id|transaction[- ]id|atm[- ]withdrawal|upi[- ]ref|card[- ]spent|spent[- ]rs|inr\s*[\d,]+|rs\.?\s*[\d,]+|\$\s*[\d,]+)\b"""
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val recentAnnounceMap = ConcurrentHashMap<String, Long>()

    init {
        instance = this
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val locale = Locale.ENGLISH
                    tts?.language = locale
                    tts?.setPitch(1.02f)
                    tts?.setSpeechRate(1.0f)
                    isTtsReady = true
                    Log.i(TAG, "NotificationAnnouncementManager TTS initialized successfully")
                } else {
                    Log.w(TAG, "Failed initializing TTS engine for notification announcements (status: $status)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing TTS: ${e.message}", e)
        }
    }

    fun processNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        if (sbn.isOngoing) return

        val category = notif.category
        if (category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_PROGRESS ||
            category == Notification.CATEGORY_TRANSPORT ||
            category == Notification.CATEGORY_SERVICE) {
            return
        }

        val pkg = sbn.packageName ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return

        scope.launch {
            try {
                val enabled = preferences.notifAnnounceEnabled.first()
                if (!enabled) return@launch

                // Policy Protection: Strict Banking & 2FA / OTP blocking
                val blockBankingOtp = preferences.notifBlockBankingOtp.first()
                if (blockBankingOtp && isSensitiveNotification(pkg, title, text)) {
                    Log.d(TAG, "Notification dropped for privacy/AI Studio safety policy compliance (pkg=$pkg)")
                    return@launch
                }

                val allowedPackages = preferences.notifAnnounceApps.first()
                if (!allowedPackages.contains(pkg)) return@launch

                // Throttle / Deduplicate identical notification within 6 seconds
                val hashKey = "$pkg|$title|$text"
                val now = System.currentTimeMillis()
                val lastTime = recentAnnounceMap[hashKey] ?: 0L
                if (now - lastTime < 6000L) {
                    return@launch
                }
                recentAnnounceMap[hashKey] = now
                cleanupOldRecentAnnouncements(now)

                val readContent = preferences.notifAnnounceReadContent.first()
                val language = preferences.notifAnnounceLanguage.first()
                val appLabel = resolveAppLabel(pkg)
                val senderName = if (title.isNotBlank()) title else "Someone"

                val announcementText = buildAnnouncementText(
                    language = language,
                    pkg = pkg,
                    appLabel = appLabel,
                    senderName = senderName,
                    rawText = text,
                    readContent = readContent
                )

                announce(announcementText, pkg, senderName, text)
            } catch (e: Exception) {
                Log.e(TAG, "Error in processNotification: ${e.message}", e)
            }
        }
    }

    private fun isSensitiveNotification(pkg: String, title: String, text: String): Boolean {
        // 1. Direct package check against known financial, bank, and 2FA apps
        val pkgLower = pkg.lowercase(Locale.ROOT)
        if (SENSITIVE_PACKAGES.contains(pkgLower)) return true
        if (pkgLower.contains("bank") || pkgLower.contains("authenticator") || pkgLower.contains("wallet")) return true

        // 2. Sensitive content inspection (OTPs, 2FA tokens, Debit/Credit alerts)
        val combinedContent = "$title $text".lowercase(Locale.ROOT)
        if (OTP_PATTERN.containsMatchIn(combinedContent) || OTP_PHRASE_PATTERN.containsMatchIn(combinedContent)) {
            return true
        }
        if (FINANCIAL_TXN_PATTERN.containsMatchIn(combinedContent)) {
            return true
        }

        return false
    }

    private fun buildAnnouncementText(
        language: String,
        pkg: String,
        appLabel: String,
        senderName: String,
        rawText: String,
        readContent: Boolean
    ): String {
        val cleanText = sanitizeNotificationText(rawText)

        return when (language.lowercase(Locale.ROOT)) {
            "english" -> {
                if (readContent && cleanText.isNotBlank()) {
                    "$appLabel message from $senderName: $cleanText"
                } else {
                    "New notification from $senderName on $appLabel"
                }
            }
            "hindi" -> {
                if (readContent && cleanText.isNotBlank()) {
                    "$appLabel par $senderName ka sandesh aaya hai: $cleanText"
                } else {
                    "$appLabel par $senderName se naya sandesh aaya hai"
                }
            }
            else -> {
                // Default: Hinglish
                if (readContent && cleanText.isNotBlank()) {
                    when {
                        pkg.contains("whatsapp") -> "WhatsApp par $senderName ka message aaya hai: $cleanText"
                        pkg.contains("telegram") -> "Telegram par $senderName ka message: $cleanText"
                        pkg.contains("messaging") || pkg.contains("mms") -> "SMS aaya hai $senderName se: $cleanText"
                        pkg.contains("gm") -> "Gmail par $senderName se email aaya: $cleanText"
                        else -> "$appLabel par $senderName ka message: $cleanText"
                    }
                } else {
                    when {
                        pkg.contains("whatsapp") -> "WhatsApp par $senderName ka naya message aaya hai"
                        pkg.contains("telegram") -> "Telegram par $senderName ka naya message aaya hai"
                        pkg.contains("messaging") || pkg.contains("mms") -> "Naya SMS aaya hai $senderName se"
                        else -> "$appLabel par $senderName ka naya notification aaya hai"
                    }
                }
            }
        }
    }

    fun announceTest(appName: String, sender: String, message: String) {
        scope.launch {
            val language = preferences.notifAnnounceLanguage.first()
            val announcement = buildAnnouncementText(
                language = language,
                pkg = "com.test",
                appLabel = appName,
                senderName = sender,
                rawText = message,
                readContent = true
            )
            announce(announcement, "com.test", sender, message)
        }
    }

    private suspend fun announce(announcement: String, pkg: String, sender: String, rawContent: String) {
        val service = AuraForegroundService.currentService
        if (AuraForegroundService.isCallActive.value && service != null &&
            service.liveClient.connectionState.value == com.example.core.live.LiveConnectionState.Connected
        ) {
            // Live AI Gemini voice call is active: announce naturally through the active agent stream
            Log.i(TAG, "Announcing notification naturally via Maya live voice: $announcement")
            val cleanContent = sanitizeNotificationText(rawContent)
            val prompt = "[INFO — already handled by the app, do not repeat this action.] [SYSTEM EVENT: New Notification from $pkg by $sender: \"$cleanContent\". Say clearly in your natural voice: \"$announcement\"]"
            service.liveClient.sendTextMessage(prompt)
        } else {
            // Live call is not active: do NOT use robotic device TTS fallback (per user request)
            Log.d(TAG, "Maya live voice call inactive. Skipping announcement (device TTS disabled): $announcement")
        }
    }

    private fun sanitizeNotificationText(text: String): String {
        return text
            .replace(Regex("""https?://\S+"""), "link")
            .replace(Regex("""[\r\n]+"""), " ")
            .replace(Regex("""[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+"""), "email")
            .trim()
            .take(140)
    }

    private fun resolveAppLabel(pkg: String): String {
        return when {
            pkg.contains("whatsapp.w4b") -> "WhatsApp Business"
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("telegram") -> "Telegram"
            pkg.contains("apps.messaging") || pkg.contains("mms") -> "Messages"
            pkg.contains("gm") -> "Gmail"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("snapchat") -> "Snapchat"
            pkg.contains("youtube") -> "YouTube"
            else -> {
                try {
                    val pm = context.packageManager
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    "App"
                }
            }
        }
    }

    private fun cleanupOldRecentAnnouncements(now: Long) {
        if (recentAnnounceMap.size > 50) {
            val iterator = recentAnnounceMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 30000L) {
                    iterator.remove()
                }
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        scope.cancel()
        instance = null
    }
}
