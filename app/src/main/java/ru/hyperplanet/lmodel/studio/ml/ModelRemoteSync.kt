package ru.hyperplanet.lmodel.studio.ml

import android.content.Context
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.update
import java.io.File

/**
 * У каждой модели свой model.json и полный PAW/VPS-пакет.
 * Собирается автоматически после обучения и по кнопке в Dev Mode.
 */
object ModelRemoteSync {

    data class PackageInfo(
        val modelId: Long,
        val dir: File,
        val modelJson: File,
        val configJson: File,
        val zipFile: File?,
        val error: String? = null
    )

    fun packageDir(context: Context, modelId: Long): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "remote_api/model_$modelId").also { it.mkdirs() }

    /**
     * Полная сборка для модели: model.json + config + server.py + wsgi + ZIP.
     */
    suspend fun buildForModel(context: Context, modelId: Long): PackageInfo {
        val db = AppDatabase.getInstance(context)
        val model = db.modelDao().getById(modelId)
            ?: return PackageInfo(modelId, packageDir(context, modelId), File(""), File(""), null, "Модель не найдена")
        if (!model.isTrained || model.trainedDataJson.isNullOrBlank()) {
            return PackageInfo(modelId, packageDir(context, modelId), File(""), File(""), null, "Модель не обучена")
        }
        var apiKey = model.apiKey?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            apiKey = LocalApiServer.generateApiKey()
            db.modelDao().update(model.copy(apiKey = apiKey))
        }
        val params = db.modelParameterDao().getForModel(modelId)
            .associate { it.key.lowercase() to it.value }
        val dir = packageDir(context, modelId)
        // очистить старое
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()

        val modelJson = File(dir, "model.json")
        modelJson.writeText(model.trainedDataJson!!, Charsets.UTF_8)

        val configJson = File(dir, "config.json")
        val cfg = buildString {
            append("{")
            append("\"model_id\":").append(modelId).append(",")
            append("\"model_name\":").append(jsonStr(model.name)).append(",")
            append("\"api_key\":").append(jsonStr(apiKey)).append(",")
            append("\"port\":8765,")
            append("\"parameters\":{")
            append(params.entries.joinToString(",") { (k, v) -> "${jsonStr(k)}:${jsonStr(v)}" })
            append("}}")
        }
        configJson.writeText(cfg, Charsets.UTF_8)

        val zipResult = RemoteApiPackager.packageServer(
            outDir = dir,
            modelId = modelId,
            modelName = model.name,
            apiKey = apiKey,
            trainedDataJson = model.trainedDataJson!!,
            parameters = params
        )
        // также положить server.py / wsgi рядом (из zip или packager пишет только zip —
        // RemoteApiPackager пишет zip; дополнительно распакуем ключевые файлы через packager side write)
        RemoteApiPackager.writeUnpackedFiles(dir)

        return PackageInfo(
            modelId = modelId,
            dir = dir,
            modelJson = modelJson,
            configJson = configJson,
            zipFile = zipResult.zipFile,
            error = zipResult.error
        )
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
