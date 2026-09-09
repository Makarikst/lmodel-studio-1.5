package ru.hyperplanet.lmodel.studio.ml

import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.data.ModelParameter
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

object LmsExporter {
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'M'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    data class ExportResult(val file: File?, val error: String?)

    fun export(model: ModelEntity, parameters: List<ModelParameter>, outputDir: File, fileBaseName: String): ExportResult {
        if (!model.isTrained || model.trainedDataJson.isNullOrBlank()) return ExportResult(null, "Не обучена")
        return try {
            outputDir.mkdirs()
            val safe = fileBaseName.replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ]+"), "_")
            val out = File(outputDir, "$safe.lms")
            DataOutputStream(out.outputStream().buffered()).use { dos ->
                dos.write(MAGIC); dos.writeInt(1)
                dos.writeLong(System.currentTimeMillis()); dos.writeLong(model.createdAt)
                dos.writeByte(1); dos.writeByte(1); dos.writeByte(0); dos.writeByte(0); dos.writeByte(0)
                val nb = model.name.toByteArray(StandardCharsets.UTF_8)
                dos.writeInt(nb.size); dos.write(nb)
                dos.writeInt(parameters.size)
                for (p in parameters) {
                    val kb = p.key.toByteArray(StandardCharsets.UTF_8); dos.writeInt(kb.size); dos.write(kb)
                    val vb = p.value.toByteArray(StandardCharsets.UTF_8); dos.writeInt(vb.size); dos.write(vb)
                }
                val tb = model.trainedDataJson.toByteArray(StandardCharsets.UTF_8)
                dos.writeInt(tb.size); dos.write(tb)
            }
            ExportResult(out, null)
        } catch (e: Exception) { ExportResult(null, e.message) }
    }
}
