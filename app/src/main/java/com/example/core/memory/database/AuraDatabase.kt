package com.example.core.memory.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchConversations(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getBySession(sessionId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurnsSync(limit: Int = 10): List<ConversationEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_logs WHERE toolName LIKE '%' || :query || '%' OR output LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchLogs(query: String): Flow<List<ActionLogEntity>>

    @Insert
    suspend fun insert(log: ActionLogEntity): Long

    @Query("DELETE FROM action_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM action_logs")
    suspend fun clearAll()
}

@Dao
interface UserFactDao {
    @Query("SELECT * FROM user_facts ORDER BY category ASC, `key` ASC")
    fun getAll(): Flow<List<UserFactEntity>>

    @Query("SELECT * FROM user_facts WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): UserFactEntity?

    @Query("SELECT * FROM user_facts WHERE `key` LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun searchFacts(query: String, limit: Int = 20): List<UserFactEntity>

    @Query("SELECT * FROM user_facts ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentFacts(limit: Int = 20): List<UserFactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFactEntity): Long

    @Query("UPDATE user_facts SET `key` = :key, value = :value, category = :category, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateById(id: Long, key: String, value: String, category: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM user_facts WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM user_facts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM user_facts")
    suspend fun clearAll()
}

@Dao
interface TaskExecutionDao {
    @Query("SELECT * FROM task_executions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTasks(limit: Int = 50): Flow<List<TaskExecutionEntity>>

    @Query("SELECT * FROM task_executions WHERE taskId = :taskId LIMIT 1")
    suspend fun findById(taskId: String): TaskExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskExecutionEntity): Long

    @Query("DELETE FROM task_executions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM task_executions")
    suspend fun clearAll()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY createdAt DESC")
    fun getAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE isEnabled = 1")
    suspend fun getActiveRoutinesSync(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: RoutineEntity): Long

    @Update
    suspend fun update(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE LOWER(name) = LOWER(:name)")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM routines")
    suspend fun clearAll()
}

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE state IN ('QUEUED', 'RUNNING', 'WAITING_USER') ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveTask(): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestTask(): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE state IN ('QUEUED', 'RUNNING', 'WAITING_USER')")
    fun observeActiveTasks(): Flow<List<AgentTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(task: AgentTaskEntity)

    @Query("UPDATE agent_tasks SET state = :state, result = :result, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, result: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEpisodes(limit: Int = 10): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEpisode(): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE summary LIKE '%' || :query || '%' OR topics LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchEpisodes(query: String, limit: Int = 10): List<EpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(episode: EpisodeEntity): Long
}

@Dao
interface ContactProfileDao {
    @Query("SELECT * FROM contact_profiles WHERE contactKey = :contactKey LIMIT 1")
    suspend fun findByKey(contactKey: String): ContactProfileEntity?

    @Query("SELECT * FROM contact_profiles WHERE name LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%' LIMIT 10")
    suspend fun searchProfiles(query: String): List<ContactProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(profile: ContactProfileEntity): Long
}

@Dao
interface LearnedSkillDao {
    @Query("SELECT * FROM learned_skills WHERE goalPattern LIKE '%' || :pattern || '%' ORDER BY successCount DESC LIMIT 1")
    suspend fun findMatchingSkill(pattern: String): LearnedSkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(skill: LearnedSkillEntity): Long
}

@Database(
    entities = [
        ConversationEntity::class,
        ActionLogEntity::class,
        UserFactEntity::class,
        TaskExecutionEntity::class,
        RoutineEntity::class,
        AgentTaskEntity::class,
        EpisodeEntity::class,
        ContactProfileEntity::class,
        LearnedSkillEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun userFactDao(): UserFactDao
    abstract fun taskExecutionDao(): TaskExecutionDao
    abstract fun routineDao(): RoutineDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun contactProfileDao(): ContactProfileDao
    abstract fun learnedSkillDao(): LearnedSkillDao

    companion object {
        @Volatile
        private var INSTANCE: AuraDatabase? = null

        fun getInstance(context: Context): AuraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura_database.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
