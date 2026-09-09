package ru.hyperplanet.lmodel.studio.ml

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * Prolog Script → facts.pl (файл) → компиляция в JSON, понятный InferenceEngine.
 */
object PrologCompiler {

    private val gson = Gson()

    data class CompiledProlog(
        val facts: List<CompiledFact>,
        val rules: List<CompiledRule>,
        val sourceLines: List<String>,
        val factsPlPath: String?
    )

    data class CompiledFact(val predicate: String, val args: List<String>)
    data class CompiledRule(
        val head: CompiledFact,
        val body: List<CompiledFact>
    )

    /** Папка модели: filesDir/models/<modelId>/ */
    fun modelDir(context: Context, modelId: Long): File =
        File(context.filesDir, "models/$modelId").also { it.mkdirs() }

    fun factsPlFile(context: Context, modelId: Long): File =
        File(modelDir(context, modelId), "facts.pl")

    /**
     * Сохранить исходный Prolog-скрипт в facts.pl
     * @return путь к файлу
     */
    fun writeFactsPl(context: Context, modelId: Long, prologBlocks: List<String>): File {
        val file = factsPlFile(context, modelId)
        val body = buildString {
            appendLine("% LModel Studio — auto-generated facts.pl")
            appendLine("% modelId=$modelId")
            appendLine()
            prologBlocks.forEach { block ->
                block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
                    appendLine(line)
                }
                appendLine()
            }
        }
        file.writeText(body, Charsets.UTF_8)
        return file
    }

    /**
     * Прочитать facts.pl и скомпилировать в структуру для модели.
     */
    fun compileFactsPl(factsPl: File): CompiledProlog {
        val text = if (factsPl.exists()) factsPl.readText(Charsets.UTF_8) else ""
        return compileSource(text, factsPl.absolutePath)
    }

    fun compileSource(source: String, path: String? = null): CompiledProlog {
        val kb = PrologEngine.parse(listOf(source))
        val lines = source.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%") }
        return CompiledProlog(
            facts = kb.facts.map { CompiledFact(it.predicate, it.args) },
            rules = kb.rules.map {
                CompiledRule(
                    head = CompiledFact(it.head.predicate, it.head.args),
                    body = it.body.map { b -> CompiledFact(b.predicate, b.args) }
                )
            },
            sourceLines = lines,
            factsPlPath = path
        )
    }

    fun toJson(compiled: CompiledProlog): String = gson.toJson(compiled)

    fun fromJson(json: String?): CompiledProlog? {
        if (json.isNullOrBlank()) return null
        return try { gson.fromJson(json, CompiledProlog::class.java) } catch (_: Exception) { null }
    }
}
