package ru.hyperplanet.lmodel.studio.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<ModelEntity>>
    @Query("SELECT * FROM models ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<ModelEntity>
    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ModelEntity?
    @Insert suspend fun insert(model: ModelEntity): Long
    @Update suspend fun update(model: ModelEntity)
    @Delete suspend fun delete(model: ModelEntity)
}

@Dao
interface ModelParameterDao {
    @Query("SELECT * FROM model_parameters WHERE modelId = :modelId ORDER BY id ASC")
    fun observeForModel(modelId: Long): LiveData<List<ModelParameter>>
    @Query("SELECT * FROM model_parameters WHERE modelId = :modelId ORDER BY id ASC")
    suspend fun getForModel(modelId: Long): List<ModelParameter>
    @Insert suspend fun insert(param: ModelParameter): Long
    @Update suspend fun update(param: ModelParameter)
    @Delete suspend fun delete(param: ModelParameter)
}

@Dao
interface TrainingItemDao {
    @Query("SELECT * FROM training_items WHERE modelId = :modelId ORDER BY addedAt ASC")
    fun observeForModel(modelId: Long): LiveData<List<TrainingItem>>
    @Query("SELECT * FROM training_items WHERE modelId = :modelId AND type = :type ORDER BY addedAt ASC")
    suspend fun getForModelByType(modelId: Long, type: String): List<TrainingItem>
    @Query("SELECT * FROM training_items WHERE modelId = :modelId ORDER BY addedAt ASC")
    suspend fun getAllForModel(modelId: Long): List<TrainingItem>
    @Insert suspend fun insert(item: TrainingItem): Long
    @Delete suspend fun delete(item: TrainingItem)
}

@Dao
interface RagBotDao {
    @Query("SELECT * FROM rag_bots ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<RagBotEntity>>
    @Query("SELECT * FROM rag_bots ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<RagBotEntity>
    @Query("SELECT * FROM rag_bots WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RagBotEntity?
    @Insert suspend fun insert(bot: RagBotEntity): Long
    @Update suspend fun update(bot: RagBotEntity)
    @Delete suspend fun delete(bot: RagBotEntity)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<Chat>>
    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Chat?
    @Insert suspend fun insert(chat: Chat): Long
    @Update suspend fun update(chat: Chat)
    @Delete suspend fun delete(chat: Chat)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeForChat(chatId: Long): LiveData<List<Message>>
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getForChatOnce(chatId: Long): List<Message>
    @Insert suspend fun insert(message: Message): Long
}

@Dao
interface ModelUnionDao {
    @Query("SELECT * FROM model_unions ORDER BY createdAt DESC")
    fun observeAll(): LiveData<List<ModelUnion>>
    @Query("SELECT * FROM model_unions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ModelUnion?
    @Insert suspend fun insert(u: ModelUnion): Long
    @Delete suspend fun delete(u: ModelUnion)
}

@Dao
interface UnionMessageDao {
    @Query("SELECT * FROM union_messages WHERE unionId = :unionId ORDER BY timestamp ASC")
    fun observeForUnion(unionId: Long): LiveData<List<UnionMessage>>
    @Query("SELECT * FROM union_messages WHERE unionId = :unionId ORDER BY timestamp ASC")
    suspend fun getForUnionOnce(unionId: Long): List<UnionMessage>
    @Insert suspend fun insert(m: UnionMessage): Long
    @Query("DELETE FROM union_messages WHERE unionId = :unionId")
    suspend fun clearUnion(unionId: Long)
}
