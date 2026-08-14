package ru.hyperplanet.lmodel.studio.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

object FileUtils {

    /** Копирует файл, выбранный через SAF (ACTION_OPEN_DOCUMENT), во внутреннее хранилище приложения. */
    fun copyUriToAppStorage(context: Context, uri: Uri, subDir: String): String? {
        return try {
            val dir = File(context.filesDir, subDir).apply { mkdirs() }
            val originalName = sanitizeFileName(queryFileName(context, uri) ?: UUID.randomUUID().toString())
            val destFile = File(dir, "${System.currentTimeMillis()}_$originalName")

            // Если поток не открылся, файл не создан — раньше здесь по ошибке всё равно
            // возвращался путь к несуществующему файлу.
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { stream ->
                destFile.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun queryFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name
    }

    fun fileNameFromPath(path: String): String = File(path).name

    /** Убирает символы, недопустимые/опасные в имени файла (в т.ч. "/", чтобы нельзя было создать поддиректорию). */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { UUID.randomUUID().toString() }
}
