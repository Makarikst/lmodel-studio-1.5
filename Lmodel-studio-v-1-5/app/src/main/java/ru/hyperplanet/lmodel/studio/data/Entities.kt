package ru.hyperplanet.lmodel.studio.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val supportsText: Boolean = true,
    val supportsPhoto: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsAudio: Boolean = false,
    val isTrained: Boolean = false,
    val trainedDataJson: String? = null,
    val apiKey: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "model_parameters",
    foreignKeys = [ForeignKey(entity = ModelEntity::class, parentColumns = ["id"], childColumns = ["modelId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("modelId")]
)
data class ModelParameter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: Long,
    val key: String,
    val value: String
)

@Entity(
    tableName = "training_items",
    foreignKeys = [ForeignKey(entity = ModelEntity::class, parentColumns = ["id"], childColumns = ["modelId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("modelId")]
)
data class TrainingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: Long,
    val type: String,
    val content: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_TEXT = "TEXT"
        const val TYPE_PROLOG = "PROLOG"
    }
}

@Entity(tableName = "rag_bots")
data class RagBotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val knowledgeJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chats", indices = [Index("modelId"), Index("ragBotId")])
data class Chat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val systemPrompt: String,
    val sourceType: String = SOURCE_MODEL,
    val modelId: Long = 0,
    val ragBotId: Long = 0,
    val allowAttachments: Boolean = false,
    val allowedTypes: String = "",
    val lockedLanguage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_MODEL = "MODEL"
        const val SOURCE_RAG = "RAG"
    }
}

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = Chat::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chatId")]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val isUser: Boolean,
    val text: String,
    val attachmentPath: String? = null,
    val reasoning: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/** Объединение двух моделей (Beta): диалог модель↔модель */
@Entity(tableName = "model_unions")
data class ModelUnion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val modelIdA: Long,
    val modelIdB: Long,
    val topic: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "union_messages",
    foreignKeys = [ForeignKey(
        entity = ModelUnion::class,
        parentColumns = ["id"],
        childColumns = ["unionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("unionId")]
)
data class UnionMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unionId: Long,
    /** 0 = model A, 1 = model B */
    val speaker: Int,
    val modelId: Long,
    val modelName: String,
    val text: String,
    val reasoning: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
