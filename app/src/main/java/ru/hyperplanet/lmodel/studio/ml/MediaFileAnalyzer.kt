package ru.hyperplanet.lmodel.studio.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * Реальный разбор файлов: чтение текста, метаданные фото/аудио/видео.
 * Не нейросетевой «vision», а фактический анализ содержимого и контейнера.
 */
object MediaFileAnalyzer {

    data class Result(
        val kind: Kind,
        val displayName: String,
        val savedFile: File,
        val analysis: String,
        /** Для текстовых — полный или усечённый текст */
        val extractedText: String?
    )

    enum class Kind { TEXT, PHOTO, AUDIO, VIDEO, OTHER }

    // Много текстовых форматов
    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm",
        "pl", "prolog", "py", "kt", "kts", "java", "js", "ts", "jsx", "tsx",
        "c", "cc", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql",
        "yaml", "yml", "toml", "ini", "log", "tex", "sh", "bash", "zsh",
        "properties", "gradle", "css", "scss", "r", "swift", "m", "mm",
        "bat", "ps1", "dockerfile", "makefile", "cmake", "lua", "dart"
    )
    private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "m4a", "aac")
    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp")

    fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.US)

    fun kindOf(name: String): Kind = when (extOf(name)) {
        in TEXT_EXT -> Kind.TEXT
        in PHOTO_EXT -> Kind.PHOTO
        in AUDIO_EXT -> Kind.AUDIO
        in VIDEO_EXT -> Kind.VIDEO
        else -> Kind.OTHER
    }

    fun mimeFilter(): Array<String> = arrayOf(
        "text/*",
        "application/json",
        "application/xml",
        "application/csv",
        "text/csv",
        "text/comma-separated-values",
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp",
        "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/ogg", "audio/aac",
        "video/mp4", "video/webm", "video/x-matroska", "video/3gpp",
        "application/octet-stream"
    )

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val n = c.getString(idx)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /**
     * Копирует URI во внутреннее хранилище и строит анализ.
     */
    fun ingest(context: Context, uri: Uri, subdir: String = "train_media"): Result {
        val name = queryDisplayName(context, uri)
        val kind = kindOf(name)
        val dir = File(context.filesDir, subdir).apply { mkdirs() }
        val safe = name.replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ]"), "_")
        val out = File(dir, "${System.currentTimeMillis()}_$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: error("Не удалось прочитать файл")

        val analysis: String
        val extracted: String?
        when (kind) {
            Kind.TEXT -> {
                val text = readTextCapped(out, maxChars = 200_000)
                extracted = text
                analysis = buildString {
                    append("Текстовый файл «$name», размер ${out.length()} байт, расширение .${extOf(name)}. ")
                    append("Символов прочитано: ${text.length}.")
                }
            }
            Kind.PHOTO -> {
                extracted = null
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(out.absolutePath, opts)
                analysis = buildString {
                    append("Фото «$name». ")
                    append("Разрешение: ${opts.outWidth}×${opts.outHeight}. ")
                    append("Формат: ${opts.outMimeType ?: extOf(name)}. ")
                    append("Размер файла: ${out.length()} байт.")
                }
            }
            Kind.AUDIO -> {
                extracted = null
                analysis = analyzeAv(out, name, isVideo = false)
            }
            Kind.VIDEO -> {
                extracted = null
                analysis = analyzeAv(out, name, isVideo = true)
            }
            Kind.OTHER -> {
                // попробуем как текст
                val text = runCatching { readTextCapped(out, 50_000) }.getOrNull()
                if (!text.isNullOrBlank() && text.count { it.isLetterOrDigit() } > 20) {
                    extracted = text
                    analysis = "Файл «$name» прочитан как текст, ${text.length} символов, ${out.length()} байт."
                } else {
                    extracted = null
                    analysis = "Файл «$name», ${out.length()} байт, тип .${extOf(name)} (бинарный/неизвестный)."
                }
            }
        }
        return Result(kind, name, out, analysis, extracted)
    }

    private fun analyzeAv(file: File, name: String, isVideo: Boolean): String {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            buildString {
                append(if (isVideo) "Видео" else "Аудио")
                append(" «$name». ")
                if (dur != null) append("Длительность: ${dur / 1000.0} с. ")
                if (mime != null) append("MIME: $mime. ")
                if (isVideo && w != null && h != null) append("Кадр: ${w}×${h}. ")
                if (title != null) append("Title: $title. ")
                if (bitrate != null) append("Битрейт: $bitrate. ")
                append("Размер: ${file.length()} байт.")
            }
        } catch (e: Exception) {
            "${if (isVideo) "Видео" else "Аудио"} «$name», ${file.length()} байт. Метаданные недоступны: ${e.message}"
        } finally {
            runCatching { r.release() }
        }
    }

    private fun readTextCapped(file: File, maxChars: Int): String {
        val bytes = file.readBytes()
        val raw = try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            bytes.toString(Charset.defaultCharset())
        }
        // убрать NULs
        val cleaned = raw.replace("\u0000", "")
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars) + "\n…[обрезано]"
    }

    fun trainingBlob(description: String, result: Result): String = buildString {
        appendLine("Тип: ${result.kind}")
        appendLine("Файл: ${result.displayName}")
        appendLine("Анализ: ${result.analysis}")
        if (description.isNotBlank()) {
            appendLine("Описание пользователя: $description")
        }
        val ext = result.extractedText
        if (!ext.isNullOrBlank()) {
            appendLine("Содержимое:")
            append(ext)
        }
    }
}
