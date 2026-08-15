package ru.hyperplanet.lmodel.studio.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Без suspend на insert/update/delete — KSP не генерирует Continuation и не ломает *Dao_Impl.
 * Вызывай из withContext(Dispatchers.IO) { ... }.
 */
@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY createdAt DESC")
    fun getAllOnce(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    fun getById(id: Long): ModelEntity?

    @Insert
    fun insert(model: ModelEntity): Long

    @Query(
        """
        UPDATE models SET name = :name, supportsText = :supportsText, supportsPhoto = :supportsPhoto,
        supportsVideo = :supportsVideo, supportsAudio = :supportsAudio, isTrained = :isTrained,
        trainedDataJson = :trainedDataJson, apiKey = :apiKey, createdAt = :createdAt
        WHERE id = :id
        """
    )
    fun updateFields(
        id: Long,
        name: String,
        supportsText: Boolean,
        supportsPhoto: Boolean,
        supportsVideo: Boolean,
        supportsAudio: Boolean,
        isTrained: Boolean,
        trainedDataJson: String?,
        apiKey: String?,
        createdAt: Long
    ): Int

    @Query("DELETE FROM models WHERE id = :id")
    fun deleteById(id: Long): Int
}

@Dao
interface ModelParameterDao {
    @Query("SELECT * FROM model_parameters WHERE modelId = :modelId ORDER BY id ASC")
    fun observeForModel(modelId: Long): LiveData<List<ModelParameter>>

    @Query("SELECT * FROM model_parameters WHERE modelId = :modelId ORDER BY id ASC")
    fun getForModel(modelId: Long): List<ModelParameter>

    @Insert
    fun insert(param: ModelParameter): Long

    @Query("UPDATE model_parameters SET modelId = :modelId, `key` = :key, value = :value WHERE id = :id")
    fun updateFields(id: Long, modelId: Long, key: String, value: String): Int

    @Query("DELETE FROM model_parameters WHERE id = :id")
    fun deleteById(id: Long): Int
}

@Dao
interface TrainingItemDao {
    @Query("SELECT * FROM training_items WHERE modelId = :modelId ORDER BY addedAt ASC")
    fun observeForModel(modelId: Long): LiveData<List<TrainingItem>>

    @Query("SELECT * FROM training_items WHERE modelId = :modelId AND type = :type ORDER BY addedAt ASC")
    fun getForModelByType(modelId: Long, type: String): List<TrainingItem>

    @Query("SELECT * FROM training_items WHERE modelId = :modelId ORDER BY addedAt ASC")
    fun getAllForModel(modelId: Long): List<TrainingItem>

    @Insert
    fun insert(item: TrainingItem): Long

    @Query("DELETE FROM training_items WHERE id = :id")
    fun deleteById(id: Long): Int
}

@Dao
interface RagBotDao {
    @Query("SELECT * FROM rag_bots ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<RagBotEntity>>

    @Query("SELECT * FROM rag_bots ORDER BY createdAt DESC")
    fun getAllOnce(): List<RagBotEntity>

    @Query("SELECT * FROM rag_bots WHERE id = :id LIMIT 1")
    fun getById(id: Long): RagBotEntity?

    @Insert
    fun insert(bot: RagBotEntity): Long

    @Query(
        """
        UPDATE rag_bots SET name = :name, description = :description,
        knowledgeJson = :knowledgeJson, createdAt = :createdAt WHERE id = :id
        """
    )
    fun updateFields(
        id: Long,
        name: String,
        description: String,
        knowledgeJson: String,
        createdAt: Long
    ): Int

    @Query("DELETE FROM rag_bots WHERE id = :id")
    fun deleteById(id: Long): Int
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1")
    fun getById(id: Long): Chat?

    @Insert
    fun insert(chat: Chat): Long

    @Query(
        """
        UPDATE chats SET name = :name, systemPrompt = :systemPrompt, sourceType = :sourceType,
        modelId = :modelId, ragBotId = :ragBotId, allowAttachments = :allowAttachments,
        allowedTypes = :allowedTypes, lockedLanguage = :lockedLanguage, createdAt = :createdAt
        WHERE id = :id
        """
    )
    fun updateFields(
        id: Long,
        name: String,
        systemPrompt: String,
        sourceType: String,
        modelId: Long,
        ragBotId: Long,
        allowAttachments: Boolean,
        allowedTypes: String,
        lockedLanguage: String?,
        createdAt: Long
    ): Int

    @Query("DELETE FROM chats WHERE id = :id")
    fun deleteById(id: Long): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeForChat(chatId: Long): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getForChatOnce(chatId: Long): List<Message>

    @Insert
    fun insert(message: Message): Long
}

@Dao
interface ModelUnionDao {
    @Query("SELECT * FROM model_unions ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<ModelUnion>>

    @Query("SELECT * FROM model_unions WHERE id = :id LIMIT 1")
    fun getById(id: Long): ModelUnion?

    @Insert
    fun insert(u: ModelUnion): Long

    @Query("DELETE FROM model_unions WHERE id = :id")
    fun deleteById(id: Long): Int
}

@Dao
interface UnionMessageDao {
    @Query("SELECT * FROM union_messages WHERE unionId = :unionId ORDER BY timestamp ASC")
    fun observeForUnion(unionId: Long): LiveData<List<UnionMessage>>

    @Query("SELECT * FROM union_messages WHERE unionId = :unionId ORDER BY timestamp ASC")
    fun getForUnionOnce(unionId: Long): List<UnionMessage>

    @Insert
    fun insert(m: UnionMessage): Long

    @Query("DELETE FROM union_messages WHERE unionId = :unionId")
    fun clearUnion(unionId: Long): Int
}
