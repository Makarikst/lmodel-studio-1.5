package ru.hyperplanet.lmodel.studio.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "model" — думающая модель; "rag" — RAG-бот */
    val kind: String = KIND_MODEL,
    val supportsText: Boolean = true,
    val supportsPhoto: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsAudio: Boolean = false,
    val isTrained: Boolean = false,
    val trainedDataJson: String? = null,
    val apiKey: String? = null,
    /** Prolog facts/rules JSON (optional) */
    val prologJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_MODEL = "model"
        const val KIND_RAG = "rag"
    }
}

@Entity(tableName = "rag_bots")
data class RagBotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val knowledgeJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "model_unions")
data class ModelUnion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val modelIdA: Long,
    val modelIdB: Long,
    val topic: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "union_messages")
data class UnionMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unionId: Long,
    val speaker: Int,          // 0 = модель A, 1 = модель B
    val modelId: Long,
    val modelName: String,
    val text: String,
    val reasoning: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
@Entity(
    tableName = "model_parameters",
    foreignKeys = [ForeignKey(
        entity = ModelEntity::class,
        parentColumns = ["id"],
        childColumns = ["modelId"],
        onDelete = ForeignKey.CASCADE
    )],
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
    foreignKeys = [ForeignKey(
        entity = ModelEntity::class,
        parentColumns = ["id"],
        childColumns = ["modelId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("modelId")]
)
data class TrainingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: Long,
    /** text | prolog | image | audio | video | document | tabular | geospatial | timeseries | mesh3d | other */
    val type: String,
    /** Подпись / текст / описание / извлечённое содержимое */
    val content: String,
    /** Путь к медиафайлу на устройстве (если есть) */
    val mediaPath: String? = null,
    /** Исходное имя файла в датасете */
    val originalName: String? = null,
    /** Краткий автоанализ (метаданные) */
    val analysisText: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_PROLOG = "prolog"
        const val TYPE_IMAGE = "image"
        const val TYPE_AUDIO = "audio"
        const val TYPE_VIDEO = "video"
        const val TYPE_DOCUMENT = "document"
        const val TYPE_TABULAR = "tabular"
        const val TYPE_GEOSPATIAL = "geospatial"
        const val TYPE_TIMESERIES = "timeseries"
        const val TYPE_MESH3D = "mesh3d"
        const val TYPE_OTHER = "other"
    }
}

@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val systemPrompt: String = "",
    val modelId: Long,
    val allowAttachments: Boolean = false,
    val allowedTypes: String = "",
    val lockedLanguage: String? = null,
    /** SOURCE_MODEL или SOURCE_RAG */
    val sourceType: String = SOURCE_MODEL,
    /** ID RAG-бота, если sourceType == SOURCE_RAG */
    val ragBotId: Long = 0,
    val languageSwitchAllowed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_MODEL = "model"
        const val SOURCE_RAG = "rag"
    }
}


@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = Chat::class,
        parentColumns = ["id"],
        childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("chatId")]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val isUser: Boolean,
    val text: String,
    val reasoning: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)