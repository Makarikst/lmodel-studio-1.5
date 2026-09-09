package ru.hyperplanet.lmodel.studio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ModelEntity::class,
        ModelParameter::class,
        TrainingItem::class,
        Chat::class,
        Message::class,
        RagBotEntity::class,
        ModelUnion::class,
        UnionMessage::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun modelParameterDao(): ModelParameterDao
    abstract fun trainingItemDao(): TrainingItemDao
    abstract fun chatDao(): ChatDao
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
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}