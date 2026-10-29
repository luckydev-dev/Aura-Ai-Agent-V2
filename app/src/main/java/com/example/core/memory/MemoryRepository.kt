package com.example.core.memory

import android.content.Context
import android.util.Log
import com.example.core.memory.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MemoryRepository(context: Context) {
    private val database = AuraDatabase.getInstance(context)
    private val conversationDao = database.conversationDao()
    private val actionLogDao = database.actionLogDao()
    private val userFactDao = database.userFactDao()
    private val taskExecutionDao = database.taskExecutionDao()
    private val routineDao = database.routineDao()
    private val episodeDao = database.episodeDao()
    private val contactProfileDao = database.contactProfileDao()
    private val learnedSkillDao = database.learnedSkillDao()

    // Conversations
    fun getRecentConversations(limit: Int = 100): Flow<List<ConversationEntity>> =
        conversationDao.getRecent(limit)

    fun searchConversations(query: String): Flow<List<ConversationEntity>> =
        conversationDao.searchConversations(query)

    fun getConversationsBySession(sessionId: String): Flow<List<ConversationEntity>> =
        conversationDao.getBySession(sessionId)

    suspend fun getRecentTurnsSync(limit: Int = 10): List<ConversationEntity> =
        conversationDao.getRecentTurnsSync(limit)

    suspend fun logConversation(
        speaker: String,
        text: String,
        sessionId: String = "",
        language: String = "Hinglish",
        toolsUsed: String = "",
        audioDurationSec: Float = 0f
    ) {
        if (text.isBlank()) return
        conversationDao.insert(
            ConversationEntity(
                sessionId = sessionId,
                speaker = speaker,
                text = text.trim(),
                language = language,
                toolsUsed = toolsUsed,
                audioDurationSec = audioDurationSec
            )
        )
    }

    suspend fun clearConversations() = conversationDao.clearAll()
    suspend fun deleteConversation(id: Long) = conversationDao.deleteById(id)
    suspend fun deleteSession(sessionId: String) = conversationDao.deleteBySession(sessionId)

    // Action Logs
    fun getActionLogs(limit: Int = 100): Flow<List<ActionLogEntity>> =
        actionLogDao.getRecentLogs(limit)

    fun searchLogs(query: String): Flow<List<ActionLogEntity>> =
        actionLogDao.searchLogs(query)

    suspend fun logAction(
        toolName: String,
        argsJson: String,
        output: String,
        success: Boolean,
        durationMs: Long
    ) {
        actionLogDao.insert(
            ActionLogEntity(
                toolName = toolName,
                argsJson = argsJson,
                output = output,
                success = success,
                durationMs = durationMs
            )
        )
    }

    suspend fun clearActionLogs() = actionLogDao.clearAll()
    suspend fun deleteActionLog(id: Long) = actionLogDao.deleteById(id)

    // User Facts & Memory
    fun getAllFacts(): Flow<List<UserFactEntity>> = userFactDao.getAll()

    suspend fun saveFact(key: String, value: String, category: String = "fact") {
        userFactDao.insert(
            UserFactEntity(
                key = key.trim(),
                value = value.trim(),
                category = category.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateFact(id: Long, key: String, value: String, category: String = "fact") {
        userFactDao.updateById(id, key.trim(), value.trim(), category.trim())
    }

    suspend fun findFact(key: String): String? {
        return userFactDao.findByKey(key.trim())?.value
    }

    suspend fun deleteFactByKey(key: String) {
        userFactDao.deleteByKey(key.trim())
    }

    suspend fun deleteFact(id: Long) = userFactDao.deleteById(id)
    suspend fun clearAllFacts() = userFactDao.clearAll()

    suspend fun searchFacts(query: String): List<UserFactEntity> {
        return userFactDao.searchFacts(query.trim())
    }

    // Episodes
    fun getRecentEpisodes(limit: Int = 10): Flow<List<EpisodeEntity>> =
        episodeDao.getRecentEpisodes(limit)

    suspend fun getLatestEpisode(): EpisodeEntity? = episodeDao.getLatestEpisode()

    suspend fun saveEpisode(sessionId: String, summary: String, topics: String, mood: String = "neutral"): Long {
        return episodeDao.insert(
            EpisodeEntity(
                sessionId = sessionId,
                summary = summary.trim(),
                topics = topics.trim(),
                mood = mood.trim()
            )
        )
    }

    suspend fun searchEpisodes(query: String): List<EpisodeEntity> =
        episodeDao.searchEpisodes(query.trim())

    // Contact Profiles
    suspend fun findContactProfile(contactKey: String): ContactProfileEntity? {
        val clean = contactKey.replace(Regex("""[^\d]"""), "").ifBlank { contactKey.trim() }
        return contactProfileDao.findByKey(clean)
    }

    suspend fun saveContactProfile(profile: ContactProfileEntity): Long {
        return contactProfileDao.insertOrUpdate(profile)
    }

    suspend fun searchContactProfiles(query: String): List<ContactProfileEntity> {
        return contactProfileDao.searchProfiles(query.trim())
    }

    // Learned Skills
    suspend fun findMatchingSkill(goal: String): LearnedSkillEntity? {
        return learnedSkillDao.findMatchingSkill(goal.trim())
    }

    suspend fun saveLearnedSkill(goalPattern: String, toolSequenceJson: String): Long {
        return learnedSkillDao.insertOrUpdate(
            LearnedSkillEntity(
                goalPattern = goalPattern.trim(),
                toolSequenceJson = toolSequenceJson.trim()
            )
        )
    }

    /**
     * Builds top-k relevant memories and facts with ~1,500 tokens cap (~6,000 chars)
     * plus latest episode summary.
     */
    suspend fun buildMemoryContextSummary(): String {
        return try {
            val sb = StringBuilder()

            // 1. Inject latest episode summary if present
            val latestEpisode = episodeDao.getLatestEpisode()
            if (latestEpisode != null && latestEpisode.summary.isNotBlank()) {
                sb.append("=== LAST CONVERSATION EPISODE ===\n")
                sb.append("Summary: ${latestEpisode.summary}\n")
                sb.append("Topics: ${latestEpisode.topics} (Mood: ${latestEpisode.mood})\n\n")
            }

            // 2. Fetch recent and prioritized facts
            val facts = userFactDao.getRecentFacts(25)
            if (facts.isNotEmpty()) {
                sb.append("=== USER KNOWN FACTS & ALIASES ===\n")
                var charCount = 0
                for (fact in facts) {
                    if (fact.key.equals("auto_reply_busy_rule", ignoreCase = true)) continue
                    val line = "- [${fact.category}] ${fact.key}: ${fact.value}\n"
                    if (charCount + line.length > 5500) break // ~1500 tokens cap
                    sb.append(line)
                    charCount += line.length
                }
            }

            sb.toString().trim()
        } catch (e: Exception) {
            Log.e("Aura", "Failed to build memory context summary", e)
            ""
        }
    }

    // Task Executions
    fun getRecentTasks(limit: Int = 50): Flow<List<TaskExecutionEntity>> =
        taskExecutionDao.getRecentTasks(limit)

    suspend fun logTask(task: TaskExecutionEntity) {
        taskExecutionDao.insert(task)
    }

    suspend fun deleteTask(id: Long) = taskExecutionDao.deleteById(id)
    suspend fun clearTasks() = taskExecutionDao.clearAll()

    // Routines
    fun getAllRoutines(): Flow<List<RoutineEntity>> = routineDao.getAllRoutines()

    suspend fun findRoutine(name: String): RoutineEntity? = routineDao.findByName(name.trim())

    suspend fun saveRoutine(routine: RoutineEntity): Long = routineDao.insert(routine)

    suspend fun updateRoutine(routine: RoutineEntity) = routineDao.update(routine)

    suspend fun deleteRoutineByName(name: String) = routineDao.deleteByName(name.trim())

    suspend fun deleteRoutineById(id: Long) = routineDao.deleteById(id)

    suspend fun getActiveRoutinesSync(): List<RoutineEntity> = routineDao.getActiveRoutinesSync()
}
