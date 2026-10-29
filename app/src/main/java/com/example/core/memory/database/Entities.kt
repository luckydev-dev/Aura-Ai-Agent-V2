package com.example.core.memory.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val speaker: String, // "user" or "aura"
    val text: String,
    val language: String = "Hinglish",
    val toolsUsed: String = "",
    val audioDurationSec: Float = 0f
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val argsJson: String,
    val output: String,
    val success: Boolean,
    val durationMs: Long
)

@Entity(
    tableName = "user_facts",
    indices = [Index(value = ["key"], unique = true)]
)
data class UserFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val category: String, // "alias", "contact", "preference", "fact"
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_executions")
data class TaskExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val goal: String,
    val stepsJson: String,
    val outcome: String,
    val success: Boolean,
    val durationMs: Long
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val triggerPhrase: String = "",
    val stepsJson: String, // serialized JSON list of tool invocations
    val scheduledTime: String = "", // e.g. "08:30" or empty
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Autonomous background agent task managed by TaskManager.
 */
@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val state: String, // "QUEUED", "RUNNING", "WAITING_USER", "DONE", "FAILED", "CANCELLED"
    val stepsJson: String = "[]",
    val result: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val currentStep: Int = 1,
    val maxSteps: Int = 20
) {
    val taskId: String get() = id
    val status: String get() = state
}

/**
 * Summarized session episode generated at voice session completion.
 */
@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val summary: String,
    val topics: String, // comma separated topics
    val mood: String = "neutral"
)

/**
 * Profile and relational history for contacts.
 */
@Entity(
    tableName = "contact_profiles",
    indices = [Index(value = ["contactKey"], unique = true)]
)
data class ContactProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactKey: String, // normalized phone or sanitized name
    val name: String,
    val relationship: String = "", // e.g. "friend", "colleague", "family", "vip"
    val preferredTone: String = "casual",
    val lastInteractionMs: Long = System.currentTimeMillis(),
    val notes: String = ""
)

/**
 * Stored successful automation execution traces reused by the planner.
 */
@Entity(tableName = "learned_skills")
data class LearnedSkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalPattern: String,
    val toolSequenceJson: String,
    val successCount: Int = 1,
    val lastUsedMs: Long = System.currentTimeMillis()
)
