package com.example.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import com.example.service.AuraAccessibilityService
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CallAnnouncementManager(
    private val context: Context,
    private val preferences: AuraPreferences,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "Aura"
        var instance: CallAnnouncementManager? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    var isRinging = false
        private set
    var activeCallerName: String? = null
        private set
    var activeCallerNumber: String? = null
        private set
    var isVoipCall: Boolean = false
        private set

    init {
        instance = this
        registerTelephonyListener()
    }

    private fun registerTelephonyListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering telephony listener: ${e.message}", e)
        }
    }

    private fun handleCallState(state: Int, number: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isRinging = true
                isVoipCall = false
                val session = CallSessionTracker.onRinging(number = number, app = "Phone", isVoip = false)
                val callerNumber = session.number
                val contactName = session.callerName ?: callerNumber?.let { resolveCallerName(it) }
                activeCallerName = contactName
                activeCallerNumber = callerNumber

                scope.launch {
                    val autoReject = preferences.callAutoRejectEnabled.first()
                    if (autoReject) {
                        CallRejectResponder.handleAutoReject(
                            context = context,
                            rootManager = rootManager,
                            preferences = preferences,
                            session = session,
                            rejectAction = { rejectCall() }
                        )
                        return@launch
                    }

                    val enabled = preferences.callAnnouncementEnabled.first()
                    if (enabled) {
                        val displayCaller = contactName ?: callerNumber?.ifBlank { "Unknown Caller" } ?: "Unknown Caller"
                        announceIncomingCall(displayCaller, isVoip = false)
                    }
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (isRinging) {
                    isRinging = false
                    restoreRingerVolume()
                    releaseMicrophoneForCall()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                isRinging = false
                restoreRingerVolume()
                activeCallerName = null
                activeCallerNumber = null
                CallSessionTracker.onCallEnded()
            }
        }
    }

    fun onPhoneStateNumberDetected(number: String) {
        val session = CallSessionTracker.onNumberDiscovered(number)
        activeCallerNumber = session.number
        if (activeCallerName == null) {
            activeCallerName = resolveCallerName(number)
        }
    }

    fun onCallScreenedAndRejected(number: String) {
        val session = CallSessionTracker.onScreeningDisallowed(number)
        activeCallerNumber = session.number
        scope.launch {
            CallRejectResponder.handleAutoReject(
                context = context,
                rootManager = rootManager,
                preferences = preferences,
                session = session,
                rejectAction = { true }
            )
        }
    }

    fun onVoipCallDetected(appName: String, caller: String) {
        scope.launch {
            val cleanAppName = when {
                appName.lowercase().contains("whatsapp") -> "WhatsApp"
                appName.lowercase().contains("telegram") -> "Telegram"
                appName.lowercase().contains("phone") || appName.lowercase().contains("dialer") -> "Phone"
                appName.contains(".") -> "Phone"
                else -> appName
            }
            val cleanCaller = caller.replace(Regex("""(?i)^(whatsapp\s+call\s+from\s+|call\s+from\s+)"""), "").trim()

            val session = CallSessionTracker.onVoipNotification(appName = cleanAppName, callerName = cleanCaller)
            isRinging = true
            isVoipCall = true
            activeCallerName = cleanCaller
            activeCallerNumber = session.number

            val autoReject = preferences.callAutoRejectEnabled.first()
            if (autoReject) {
                CallRejectResponder.handleAutoReject(
                    context = context,
                    rootManager = rootManager,
                    preferences = preferences,
                    session = session,
                    rejectAction = { rejectCall() }
                )
                return@launch
            }

            val enabled = preferences.callAnnouncementEnabled.first()
            val allowWhatsapp = preferences.callAnnounceWhatsapp.first()
            if (enabled && allowWhatsapp) {
                announceIncomingCall(cleanCaller, isVoip = true, appName = cleanAppName)
            }
        }
    }

    private fun isNumberOnWhatsapp(phoneNumber: String): Boolean {
        val digits = phoneNumber.replace(Regex("""[^\d]"""), "")
        if (digits.isBlank()) return false
        val last10 = if (digits.length >= 10) digits.takeLast(10) else digits
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ? AND (${ContactsContract.Data.DATA1} LIKE ? OR ${ContactsContract.Data.DATA3} LIKE ?)",
                arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.profile", "%$last10%", "%$last10%"),
                null
            )
            cursor?.use {
                it.count > 0
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp contact lookup error: ${e.message}")
            false
        }
    }

    private suspend fun getLatestIncomingNumber(): String? {
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                    "${CallLog.Calls.TYPE} = ? OR ${CallLog.Calls.TYPE} = ?",
                    arrayOf(CallLog.Calls.INCOMING_TYPE.toString(), CallLog.Calls.MISSED_TYPE.toString()),
                    "${CallLog.Calls.DATE} DESC"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val num = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                        if (System.currentTimeMillis() - date < 45_000) {
                            return num
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Privileged root fallback via dumpsys
        try {
            val res = rootManager.execute("dumpsys telephony.registry | grep -i 'mCallIncomingNumber\\|incomingNumber'")
            val match = Regex("""incomingNumber=([+0-9]+)""").find(res.stdout) ?: Regex("""mCallIncomingNumber=([+0-9]+)""").find(res.stdout)
            if (match != null) {
                return match.groupValues[1]
            }
        } catch (_: Exception) {}

        return null
    }

    @SuppressLint("Range")
    private fun resolveNumberForName(name: String): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun sendWhatsappDirect(target: String, message: String): Boolean {
        return try {
            val isBusiness = preferences.isWhatsappBusiness.first()
            val pkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
            val digits = target.replace(Regex("""[^\d]"""), "")
            val phone = if (digits.length == 10) "91$digits" else digits

            var cleanMsg = message.trim()
            while (cleanMsg.endsWith("/") || cleanMsg.endsWith("/?")) {
                cleanMsg = cleanMsg.removeSuffix("/?").removeSuffix("/").trim()
            }

            if (phone.isNotBlank()) {
                val directIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(pkg)
                    putExtra(Intent.EXTRA_TEXT, cleanMsg)
                    putExtra("jid", "$phone@s.whatsapp.net")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(directIntent)
                delay(400)
                val a11y = AuraAccessibilityService.instance
                if (a11y != null) {
                    a11y.typeAndSendInCurrentChat(cleanMsg)
                } else {
                    rootManager.execute("input keyevent 66")
                }
                true
            } else {
                AuraNotificationListener.replyToNotification(target, cleanMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending auto-reply via WhatsApp: ${e.message}", e)
            false
        }
    }

    private suspend fun sendSmsDirect(number: String, message: String): Boolean {
        return try {
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (e: Exception) {
            val res = rootManager.execute("service call isms 7 i32 0 s16 \"com.android.mms\" s16 \"$number\" s16 \"null\" s16 \"$message\"")
            res.success
        }
    }

    private var originalRingerVolume: Int? = null

    fun restoreRingerVolume() {
        originalRingerVolume?.let { vol ->
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, vol, 0)
                Log.i(TAG, "Restored normal ringer volume to $vol")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore ringer volume: ${e.message}")
            }
            originalRingerVolume = null
        }
    }

    private fun announceIncomingCall(caller: String, isVoip: Boolean, appName: String? = null) {
        scope.launch {
            val service = AuraForegroundService.currentService
            val isAiActive = AuraForegroundService.isCallActive.value && service != null &&
                    service.liveClient.connectionState.value == com.example.core.live.LiveConnectionState.Connected

            if (!isAiActive) {
                // If AI is not active, play normal ring volume! Do not lower volume.
                Log.i(TAG, "AI session is inactive. Keeping normal full ring volume.")
                return@launch
            }

            // Lower ringer volume ONLY when AI session is active
            try {
                if (originalRingerVolume == null) {
                    originalRingerVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                }
                val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                val softRing = (maxRing * 0.05f).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, softRing, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting soft ringer volume: ${e.message}")
            }

            // If user is muted and AI is active in background, automatically unmute user
            if (service.audioStreamer.isMuted.value) {
                Log.i(TAG, "Incoming call while muted: Automatically unmuting user microphone.")
                service.audioStreamer.setMuted(false)
            }

            val owner = preferences.ownerName.first().ifBlank { "Boss" }
            val cleanApp = when {
                appName?.lowercase()?.contains("whatsapp") == true -> "WhatsApp"
                appName?.lowercase()?.contains("telegram") == true -> "Telegram"
                appName?.lowercase()?.contains("phone") == true -> "Phone"
                appName?.contains(".") == true -> "Phone"
                else -> appName ?: "Phone"
            }
            val cleanCaller = caller.replace(Regex("""(?i)^(whatsapp\s+call\s+from\s+|call\s+from\s+)"""), "").trim()

            val announcement = if (isVoip) {
                "$owner, $cleanApp par $cleanCaller ka call aa raha hai. Uthau ya kaat du?"
            } else {
                "$owner, $cleanCaller ka phone aa raha hai. Uthau ya kaat du?"
            }

            Log.i(TAG, "Incoming call announcement: $announcement")

            service.liveClient.sendTextMessage("[SYSTEM EVENT: Incoming Call from $cleanCaller. Say immediately: \"$announcement\"]")
        }
    }

    suspend fun handleVoiceCallDecision(command: String): Boolean {
        val lower = command.lowercase().trim()
        return when {
            lower.contains("utha") || lower.contains("answer") || lower.contains("receive") || lower.contains("pick up") || lower.contains("haan utha") -> {
                answerCall()
                true
            }
            lower.contains("kaat") || lower.contains("reject") || lower.contains("decline") || lower.contains("cut") -> {
                rejectCall()
                true
            }
            lower.contains("silent") || lower.contains("chup") || lower.contains("mute") -> {
                silenceRinger()
                true
            }
            lower.contains("message") || lower.contains("bol do") -> {
                val msg = command.substringAfter("message", "").ifBlank { "I'll call you later." }
                rejectCallWithMessage(msg.trim())
                true
            }
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun answerCall(): Boolean {
        Log.i(TAG, "Answering incoming call...")
        var answered = false

        if (isVoipCall) {
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
            val callNotif = notifs.firstOrNull {
                it.sbn.notification.category == android.app.Notification.CATEGORY_CALL ||
                it.title.contains("call", ignoreCase = true) ||
                it.text.contains("call", ignoreCase = true)
            }
            if (callNotif != null) {
                val actions = callNotif.sbn.notification.actions ?: emptyArray()
                val answerAction = actions.firstOrNull {
                    it.title.toString().contains("answer", ignoreCase = true) ||
                    it.title.toString().contains("accept", ignoreCase = true) ||
                    it.title.toString().contains("उठाएं")
                }
                if (answerAction != null) {
                    try {
                        answerAction.actionIntent.send()
                        answered = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed triggering answer notification action", e)
                    }
                }
            }
        }

        if (!answered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.acceptRingingCall()
                    answered = true
                } catch (e: Exception) {
                    Log.e(TAG, "telecomManager.acceptRingingCall failed: ${e.message}", e)
                }
            }
        }

        if (!answered) {
            val res = com.example.AuraApplication.instance.inputController.pressKey(5, "Answer Call")
            answered = res
        }

        releaseMicrophoneForCall()
        isRinging = false
        return answered
    }

    @SuppressLint("MissingPermission")
    suspend fun rejectCall(): Boolean {
        Log.i(TAG, "Rejecting incoming call...")
        var rejected = false

        if (isVoipCall) {
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
            val callNotif = notifs.firstOrNull {
                it.sbn.notification.category == android.app.Notification.CATEGORY_CALL ||
                it.title.contains("call", ignoreCase = true)
            }
            if (callNotif != null) {
                val actions = callNotif.sbn.notification.actions ?: emptyArray()
                val declineAction = actions.firstOrNull {
                    it.title.toString().contains("decline", ignoreCase = true) ||
                    it.title.toString().contains("reject", ignoreCase = true) ||
                    it.title.toString().contains("काटें")
                }
                if (declineAction != null) {
                    try {
                        declineAction.actionIntent.send()
                        rejected = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed triggering reject notification action", e)
                    }
                }
            }
        }

        if (!rejected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.endCall()
                    rejected = true
                } catch (e: Exception) {
                    Log.e(TAG, "telecomManager.endCall failed: ${e.message}", e)
                }
            }
        }

        if (!rejected) {
            val res = com.example.AuraApplication.instance.inputController.pressKey(6, "Reject Call")
            rejected = res
        }

        if (!rejected) {
            val rootRes = rootManager.execute("telecom end-call || input keyevent 6 || service call phone 5 || service call phone 6")
            if (rootRes.success) rejected = true
        }

        isRinging = false
        return rejected
    }

    suspend fun rejectCallWithMessage(message: String): Boolean {
        val num = activeCallerNumber ?: ""
        rejectCall()
        val session = CallSessionTracker.getActiveSession() ?: CallSession(
            id = java.util.UUID.randomUUID().toString(),
            startedAt = System.currentTimeMillis(),
            number = num,
            name = activeCallerName,
            source = "TELEPHONY"
        )
        return CallRejectResponder.sendMessage(
            context = context,
            rootManager = rootManager,
            preferences = preferences,
            session = session,
            message = message
        )
    }

    suspend fun silenceRinger(): Boolean {
        return try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            true
        } catch (e: Exception) {
            val res = com.example.AuraApplication.instance.inputController.pressKey(164, "Mute Ringer")
            res
        }
    }

    private fun releaseMicrophoneForCall() {
        AuraForegroundService.currentService?.let { service ->
            service.audioStreamer.setMuted(true)
            service.audioPlayer.stop()
        }
    }

    @SuppressLint("Range")
    private fun resolveCallerName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact name: ${e.message}", e)
            null
        }
    }

    fun simulateIncomingCall(callerName: String, number: String) {
        isRinging = true
        isVoipCall = false
        activeCallerName = callerName
        activeCallerNumber = number
        announceIncomingCall(callerName, isVoip = false)
    }

    fun destroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it as TelephonyCallback)
                }
            } else {
                phoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering telephony listener", e)
        }
        scope.cancel()
        instance = null
    }
}
