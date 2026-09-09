package ru.hyperplanet.lmodel.studio.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Работа с системной папкой Android/media/ru.hyperplanet.lmodel.studio
 * Система создаёт её при установке (getExternalMediaDirs).
 * Здесь храним бэкап БД и файлов моделей для восстановления после переустановки.
 */
object PersistentMediaStorage {

    private const val BACKUP_DB = "lmodel_studio_backup.db"
    private const val MARKER = ".lmodel_media_ok"

    /** Папка Android/media/<package>/ — создаётся системой */
    fun mediaDir(context: Context): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val dirs = context.externalMediaDirs
                dirs?.firstOrNull()?.also { it.mkdirs() }
            } else {
                val legacy = File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Android/media/${context.packageName}"
                )
                legacy.mkdirs()
                legacy
            }
        } catch (_: Exception) {
            null
        }
    }

    fun ensureReady(context: Context): File? {
        val dir = mediaDir(context) ?: return null
        try {
            File(dir, MARKER).writeText("ok ${System.currentTimeMillis()}")
        } catch (_: Exception) {}
        File(dir, "models").mkdirs()
        File(dir, "exports").mkdirs()
        return dir
    }

    /** Путь к бэкапу БД в media */
    fun backupDbFile(context: Context): File? {
        val dir = mediaDir(context) ?: return null
        return File(dir, BACKUP_DB)
    }

    /** Скопировать внутреннюю БД → media (вызывать после изменений) */
    fun backupDatabase(context: Context) {
        try {
            val dir = ensureReady(context) ?: return
            val dbName = "lmodel_studio.db"
            val src = context.getDatabasePath(dbName)
            if (!src.exists()) return
            // checkpoint WAL
            try {
                val db = ru.hyperplanet.lmodel.studio.data.AppDatabase.getInstance(context)
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            } catch (_: Exception) {}
            val dst = File(dir, BACKUP_DB)
            copyFile(src, dst)
            // wal/shm если есть
            listOf("-wal", "-shm").forEach { suffix ->
                val s = File(src.path + suffix)
                if (s.exists()) copyFile(s, File(dir, BACKUP_DB + suffix))
            }
        } catch (_: Exception) {}
    }

    /**
     * Если внутренняя БД пустая/нет, а в media есть бэкап — восстановить.
     * @return true если восстановили
     */
    fun restoreDatabaseIfNeeded(context: Context): Boolean {
        return try {
            val backup = backupDbFile(context) ?: return false
            if (!backup.exists() || backup.length() < 100) return false

            val dbName = "lmodel_studio.db"
            val internal = context.getDatabasePath(dbName)
            // уже есть нормальная БД — не затираем
            if (internal.exists() && internal.length() > 100) {
                // проверим, есть ли хоть модели — если БД «свежая пустая» после reinstall
                // Room создаёт файл сразу, поэтому смотрим SharedPreferences-флаг
            }
            val prefs = context.getSharedPreferences("lmodel_persist", Context.MODE_PRIVATE)
            val restoredOnce = prefs.getBoolean("restored_from_media", false)
            val firstRun = prefs.getBoolean("first_run_done", false)

            if (!firstRun && backup.exists()) {
                // чистая установка / переустановка
                internal.parentFile?.mkdirs()
                // закрыть Room перед копированием
                try {
                    ru.hyperplanet.lmodel.studio.data.AppDatabase.getInstance(context).close()
                    val f = ru.hyperplanet.lmodel.studio.data.AppDatabase::class.java.getDeclaredField("INSTANCE")
                    f.isAccessible = true
                    f.set(null, null)
                } catch (_: Exception) {}
                copyFile(backup, internal)
                listOf("-wal", "-shm").forEach { suffix ->
                    val s = File(backup.path + suffix)
                    if (s.exists()) copyFile(s, File(internal.path + suffix))
                }
                prefs.edit()
                    .putBoolean("restored_from_media", true)
                    .putBoolean("first_run_done", true)
                    .apply()
                return true
            }
            if (!firstRun) {
                prefs.edit().putBoolean("first_run_done", true).apply()
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun modelsMediaDir(context: Context, modelId: Long): File? {
        val dir = mediaDir(context) ?: return null
        return File(dir, "models/$modelId").also { it.mkdirs() }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
    }
}
