package com.example.core.brain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Typed system and telemetry events for Aura's internal policy brain.
 */
sealed class AuraEvent {
    data class MessageReceived(
        val packageName: String,
        val sender: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : AuraEvent()

    data class CallIncoming(
        val number: String,
        val callerName: String,
        val isVoip: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : AuraEvent()

    data class BatteryLow(
        val percent: Int,
        val isCharging: Boolean
    ) : AuraEvent()

    data class TimeTick(
        val hour: Int,
        val minute: Int,
        val dayOfWeek: String
    ) : AuraEvent()

    data class UserIdle(
        val idleMinutes: Int
    ) : AuraEvent()
}

/**
 * Global reactive event bus for Aura's brain and background triggers.
 */
object EventBus {
    private val _events = MutableSharedFlow<AuraEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AuraEvent> = _events.asSharedFlow()

    fun emit(event: AuraEvent) {
        _events.tryEmit(event)
    }
}
