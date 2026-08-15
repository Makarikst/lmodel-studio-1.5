package ru.hyperplanet.lmodel.studio.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Реальная выдача файлов: FileProvider content:// URI + системный Share.
 * Не имитация — файл с диска, как обычный Android share.
 */
object FileShareHelper {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    fun uriFor(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    fun shareFile(context: Context, file: File, title: String = "LModel Studio") {
        if (!file.exists()) throw IllegalArgumentException("File not found: ${file.absolutePath}")
        val uri = uriFor(context, file)
        val mime = mimeOf(file) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun viewOrShare(context: Context, file: File) {
        if (!file.exists()) throw IllegalArgumentException("File not found")
        val uri = uriFor(context, file)
        val mime = mimeOf(file) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(view)
        } catch (_: Exception) {
            shareFile(context, file)
        }
    }

    fun mimeOf(file: File): String? {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "lms" -> "application/octet-stream"
                "onnx" -> "application/octet-stream"
                "tflite" -> "application/octet-stream"
                "json" -> "application/json"
                "py" -> "text/x-python"
                "zip" -> "application/zip"
                else -> null
            }
    }
}