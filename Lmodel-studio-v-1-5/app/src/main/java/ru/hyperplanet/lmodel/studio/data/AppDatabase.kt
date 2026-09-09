package ru.hyperplanet.lmodel.studio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Chat::class, Message::class, ModelEntity::class, ModelParameter::class,
        TrainingItem::class, RagBotEntity::class, ModelUnion::class, UnionMessage::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun modelDao(): ModelDao
    abstract fun modelParameterDao(): ModelParameterDao
    abstract fun trainingItemDao(): TrainingItemDao
    abstract fun messageDao(): MessageDao
    abstract fun ragBotDao(): RagBotDao
    abstract fun modelUnionDao(): ModelUnionDao
    abstract fun unionMessageDao(): UnionMessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lmodel_studio.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
