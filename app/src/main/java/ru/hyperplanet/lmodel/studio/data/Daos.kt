package ru.hyperplanet.lmodel.studio.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// ===== MODEL =====
@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE kind = :kind ORDER BY createdAt DESC")
    fun observeByKind(kind: String): LiveData<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE kind = :kind ORDER BY createdAt DESC")
    suspend fun getByKindOnce(kind: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ModelEntity?

    @Insert
    suspend fun insert(model: ModelEntity): Long

    @Query("""
        UPDATE models SET 
            name = :name, 
            kind = :kind, 
            supportsText = :supportsText,
            supportsPhoto = :supportsPhoto, 
            supportsVideo = :supportsVideo, 
            supportsAudio = :supportsAudio,
            isTrained = :isTrained, 
            trainedDataJson = :trainedDataJson, 
            prologJson = :prologJson,
            apiKey = :apiKey,
            createdAt = :createdAt 
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: Long,
        name: String,
        kind: String,
        supportsText: Boolean,
        supportsPhoto: Boolean,
        supportsVideo: Boolean,
        supportsAudio: Boolean,
        isTrained: Boolean,
        trainedDataJson: String?,
        prologJson: String?,
        apiKey: String?,
        createdAt: Long
    ): Int

    @Query("""
        UPDATE models SET 
            isTrained = :isTrained, 
            trainedDataJson = :trainedDataJson,
            supportsPhoto = :supportsPhoto, 
            supportsAudio = :supportsAudio, 
            supportsVideo = :supportsVideo
        WHERE id = :id
    """)
    suspend fun updateTrained(
        id: Long,
        isTrained: Boolean,
        trainedDataJson: String?,
        supportsPhoto: Boolean,
        supportsAudio: Boolean,
        supportsVideo: Boolean
    ): Int

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

// ===== MODEL PARAMETER =====
@Dao
interface ModelParameterDao {
    @Query("SELECT * FROM model_parameters WHERE modelId = :modelId ORDER BY id ASC")
    fun observeForModel(modelId: Long): LiveData<List<ModelParameter>>

    @Query("SELECT * FROM model_parameters WHERE modelId = :modelId")
    suspend fun getForModel(modelId: Long): List<ModelParameter>

    @Insert
    suspend fun insert(param: ModelParameter): Long

    @Query("""
        UPDATE model_parameters SET 
            modelId = :modelId, 
            `key` = :key, 
            value = :value 
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: Long,
        modelId: Long,
        key: String,
        value: String
    ): Int

    @Query("DELETE FROM model_parameters WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

// ===== TRAINING ITEM =====
@Dao
interface TrainingItemDao {
    @Query("SELECT * FROM training_items WHERE modelId = :modelId ORDER BY id DESC LIMIT 100")
    fun observeForModel(modelId: Long): LiveData<List<TrainingItem>>

    @Query("SELECT COUNT(*) FROM training_items WHERE modelId = :modelId")
    fun observeCount(modelId: Long): LiveData<Int>

    @Query("SELECT COUNT(*) FROM training_items WHERE modelId = :modelId")
    suspend fun countForModel(modelId: Long): Int

    @Query("SELECT * FROM training_items WHERE modelId = :modelId AND type = :type")
    suspend fun getForModelByType(modelId: Long, type: String): List<TrainingItem>

    @Query("SELECT * FROM training_items WHERE modelId = :modelId")
    suspend fun getAllForModel(modelId: Long): List<TrainingItem>

    @Query("SELECT * FROM training_items WHERE modelId = :modelId ORDER BY id ASC LIMIT :limit")
    suspend fun getSampleForModel(modelId: Long, limit: Int): List<TrainingItem>

    @Insert
    suspend fun insert(item: TrainingItem): Long

    @Insert
    suspend fun insertAll(items: List<TrainingItem>)

    @Query("""
        UPDATE training_items SET 
            modelId = :modelId, 
            type = :type, 
            content = :content, 
            mediaPath = :mediaPath, 
            originalName = :originalName, 
            analysisText = :analysisText, 
            createdAt = :createdAt 
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: Long,
        modelId: Long,
        type: String,
        content: String,
        mediaPath: String?,
        originalName: String?,
        analysisText: String?,
        createdAt: Long
    ): Int

    @Query("DELETE FROM training_items WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

// ===== CHAT =====
@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Chat?

    @Insert
    suspend fun insert(chat: Chat): Long

    @Query("""
        UPDATE chats SET 
            name = :name, 
            systemPrompt = :systemPrompt, 
            modelId = :modelId,
            allowAttachments = :allowAttachments, 
            allowedTypes = :allowedTypes,
            lockedLanguage = :lockedLanguage, 
            sourceType = :sourceType,
            ragBotId = :ragBotId,
            languageSwitchAllowed = :languageSwitchAllowed,
            createdAt = :createdAt 
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: Long,
        name: String,
        systemPrompt: String,
        modelId: Long,
        allowAttachments: Boolean,
        allowedTypes: String,
        lockedLanguage: String?,
        sourceType: String,
        ragBotId: Long,
        languageSwitchAllowed: Boolean,
        createdAt: Long
    ): Int

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

// ===== MESSAGE =====
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun observeForChat(chatId: Long): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    suspend fun getForChat(chatId: Long): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    @Query("""
        UPDATE messages SET 
            chatId = :chatId, 
            isUser = :isUser, 
            text = :text, 
            reasoning = :reasoning, 
            promptTokens = :promptTokens, 
            completionTokens = :completionTokens, 
            createdAt = :createdAt 
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: Long,
        chatId: Long,
        isUser: Boolean,
        text: String,
        reasoning: String?,
        promptTokens: Int,
        completionTokens: Int,
        createdAt: Long
    ): Int

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

// ===== RAG BOT =====
@Dao
interface RagBotDao {
    @Query("SELECT * FROM rag_bots ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<RagBotEntity>>

    @Query("SELECT * FROM rag_bots ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<RagBotEntity>

    @Query("SELECT * FROM rag_bots WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RagBotEntity?

    @Insert
    suspend fun insert(bot: RagBotEntity): Long

    @Query("""
        UPDATE rag_bots SET 
            name = :name, 
            description = :description, 
            knowledgeJson = :knowledgeJson
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: Long,
        name: String,
        description: String?,
        knowledgeJson: String?
    ): Int

    @Query("DELETE FROM rag_bots WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

// ===== MODEL UNION =====
@Dao
interface ModelUnionDao {
    @Query("SELECT * FROM model_unions ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<ModelUnion>>

    @Query("SELECT * FROM model_unions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ModelUnion?

    @Insert
    suspend fun insert(union: ModelUnion): Long

    @Query("""
        UPDATE model_unions SET 
            name = :name, 
            modelIdA = :modelIdA, 
            modelIdB = :modelIdB, 
            topic = :topic
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: Long,
        name: String,
        modelIdA: Long,
        modelIdB: Long,
        topic: String
    ): Int

    @Query("DELETE FROM model_unions WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

// ===== UNION MESSAGE =====
@Dao
interface UnionMessageDao {
    @Query("SELECT COUNT(*) FROM training_items WHERE modelId = :modelId")
    suspend fun countForModel(modelId: Long): Int

    @Query("SELECT * FROM training_items WHERE modelId = :modelId ORDER BY id ASC LIMIT :limit")
    suspend fun getSampleForModel(modelId: Long, limit: Int): List<TrainingItem>
    @Query("SELECT * FROM union_messages WHERE unionId = :unionId ORDER BY createdAt ASC")
    fun observeForUnion(unionId: Long): LiveData<List<UnionMessage>>

    @Query("SELECT * FROM union_messages WHERE unionId = :unionId ORDER BY createdAt ASC")
    suspend fun getForUnionOnce(unionId: Long): List<UnionMessage>

    @Insert
    suspend fun insert(message: UnionMessage): Long

    @Query("DELETE FROM union_messages WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}