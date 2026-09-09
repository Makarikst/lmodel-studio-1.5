package ru.hyperplanet.lmodel.studio.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import ru.hyperplanet.lmodel.studio.data.TrainingItem
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Импорт датасетов: JSON/CSV → нормальные фразы для обучения,
 * а не сырой JSON JSON. Модель не должна «отвечать JSON-кашей».
 */
object DatasetImporter {

    data class Entry(
        val type: String,
        val content: String,
        val mediaPath: String? = null,
        val originalName: String? = null,
        val analysisText: String? = null
    )

    private const val MAX_ENTRIES = 3000
    private const val MAX_CONTENT = 1200
    private const val BATCH = 30

    fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i)?.takeIf { it.isNotBlank() } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "dataset"
        } catch (_: Throwable) {
            "dataset"
        }
    }

    fun importStreaming(
        context: Context,
        uri: Uri,
        onBatch: (List<Entry>) -> Unit
    ): String {
        val name = displayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        var emitted = 0
        var scanned = 0
        val batch = ArrayList<Entry>(BATCH)

        fun flush() {
            if (batch.isEmpty()) return
            try { onBatch(ArrayList(batch)) } catch (_: Throwable) { }
            batch.clear()
        }

        fun emit(text: String, type: String = TrainingItem.TYPE_TEXT, original: String? = name) {
            if (emitted >= MAX_ENTRIES) return
            val c = text.trim().take(MAX_CONTENT)
            if (c.length < 3) return
            // не пускаем «кашу» из сырого JSON
            if (looksLikeRawJsonDump(c)) return
            batch.add(Entry(type = type, content = c, originalName = original))
            emitted++
            if (batch.size >= BATCH) flush()
        }

        return try {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                when (ext) {
                    "jsonl" -> streamJsonl(input, name, { scanned = it }, ::emit)
                    "json" -> streamJson(input, name, ::emit)
                    "csv" -> streamCsv(input, ',', name, { scanned = it }, ::emit)
                    "tsv" -> streamCsv(input, '\t', name, { scanned = it }, ::emit)
                    "zip" -> streamZip(input, ::emit)
                    "parquet", "arrow", "feather" -> emit(
                        "Этот файл в формате $ext. Экспортируй датасет в CSV или JSONL (колонки text/translation или source/target).",
                        TrainingItem.TYPE_TABULAR
                    )
                    else -> streamPlainText(input, name, { scanned = it }, ::emit)
                }
                flush()
                "OK $name · фраз=$emitted · скан≈$scanned"
            } ?: "Не открыть файл"
        } catch (oom: OutOfMemoryError) {
            System.gc()
            flush()
            "OOM · сохранено≈$emitted"
        } catch (t: Throwable) {
            flush()
            "Ошибка ${t.javaClass.simpleName}: ${t.message} · $emitted"
        }
    }

    // ─── JSONL ───────────────────────────────────────────

    private fun streamJsonl(
        input: InputStream,
        name: String,
        setScanned: (Int) -> Unit,
        emit: (String, String, String?) -> Unit
    ) {
        var scanned = 0
        openReader(input).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                scanned++
                val step = when {
                    scanned > 80_000 -> 25
                    scanned > 20_000 -> 8
                    scanned > 5_000 -> 3
                    else -> 1
                }
                if (scanned % step != 0) continue
                val t = line?.trim() ?: continue
                if (!t.startsWith("{")) continue
                try {
                    val phrase = phraseFromJsonObject(JSONObject(t))
                    if (phrase != null) emit(phrase, TrainingItem.TYPE_TEXT, name)
                } catch (_: Throwable) { }
            }
        }
        setScanned(scanned)
    }

    // ─── JSON (массив / объект с data) ────────────────────

    private fun streamJson(
        input: InputStream,
        name: String,
        emit: (String, String, String?) -> Unit
    ) {
        val prefix = readPrefix(input, 400_000).trim()
        if (prefix.isEmpty()) return
        try {
            when {
                prefix.startsWith("[") -> {
                    val arr = JSONArray(prefix)
                    val n = minOf(arr.length(), MAX_ENTRIES)
                    for (i in 0 until n) {
                        val v = arr.opt(i) ?: continue
                        when (v) {
                            is JSONObject -> phraseFromJsonObject(v)?.let {
                                emit(it, TrainingItem.TYPE_TEXT, name)
                            }
                            is String -> if (v.length >= 3) emit(v, TrainingItem.TYPE_TEXT, name)
                        }
                    }
                }
                prefix.startsWith("{") -> {
                    val root = JSONObject(prefix)
                    // HF datasets often: { "data": [ ... ] } or { "translation": {...} }
                    var done = false
                    for (key in listOf("data", "rows", "items", "examples", "messages", "train")) {
                        val arr = root.optJSONArray(key) ?: continue
                        val n = minOf(arr.length(), MAX_ENTRIES)
                        for (i in 0 until n) {
                            val v = arr.opt(i) ?: continue
                            if (v is JSONObject) phraseFromJsonObject(v)?.let {
                                emit(it, TrainingItem.TYPE_TEXT, name)
                            }
                        }
                        done = true
                        break
                    }
                    if (!done) {
                        phraseFromJsonObject(root)?.let { emit(it, TrainingItem.TYPE_TEXT, name) }
                    }
                }
            }
        } catch (_: Throwable) {
            // fallback: вытаскиваем объекты потоковым сканом
            scanJsonObjects(prefix, name, emit)
        }
    }

    private fun scanJsonObjects(
        text: String,
        name: String,
        emit: (String, String, String?) -> Unit
    ) {
        var depth = 0
        var inStr = false
        var esc = false
        val obj = StringBuilder()
        var started = false
        var count = 0
        for (ch in text) {
            if (count >= MAX_ENTRIES) break
            if (inStr) {
                obj.append(ch)
                if (esc) esc = false
                else when (ch) {
                    '\\' -> esc = true
                    '"' -> inStr = false
                }
                continue
            }
            when (ch) {
                '"' -> { if (started) obj.append(ch); inStr = true }
                '{' -> {
                    if (depth == 0) { started = true; obj.clear() }
                    if (started) obj.append(ch)
                    depth++
                }
                '}' -> {
                    if (started) obj.append(ch)
                    depth--
                    if (depth == 0 && started) {
                        try {
                            phraseFromJsonObject(JSONObject(obj.toString()))?.let {
                                emit(it, TrainingItem.TYPE_TEXT, name)
                                count++
                            }
                        } catch (_: Throwable) { }
                        obj.clear()
                        started = false
                    }
                }
                else -> if (started) obj.append(ch)
            }
            if (obj.length > 40_000) {
                obj.clear(); started = false; depth = 0
            }
        }
    }

    /**
     * Главное: из JSON-объекта сделать ЧЕЛОВЕЧЕСКУЮ фразу для обучения.
     * Примеры:
     *  {en:"hello", ru:"привет"} → «hello» по-русски: привет
     *  {instruction:"...", output:"..."} → Вопрос: ...\nОтвет: ...
     *  {translation: {en:.., ru:..}} → ...
     */
    private fun phraseFromJsonObject(obj: JSONObject): String? {
        // вложенный translation: { "translation": { "en": "...", "ru": "..." } }
        val nested = obj.optJSONObject("translation")
        if (nested != null) {
            phraseFromLangMap(nested)?.let { return it }
        }

        // пары языков на верхнем уровне
        phraseFromLangMap(obj)?.let { return it }

        // source / target
        val source = firstString(obj, listOf("source", "src", "original", "en", "english", "input_text"))
        val target = firstString(obj, listOf("target", "tgt", "translation", "ru", "russian", "output_text"))
        if (source != null && target != null) {
            return formatTranslation(source, target)
        }

        // instruction / input / output (Alpaca, ShareGPT-ish)
        val instruction = firstString(obj, listOf("instruction", "prompt", "query", "question", "user"))
        val input = firstString(obj, listOf("input", "context"))
        val output = firstString(obj, listOf("output", "response", "answer", "completion", "assistant", "text"))
        if (instruction != null && output != null) {
            return buildString {
                append("Запрос: ").append(instruction.trim())
                if (!input.isNullOrBlank()) append("\nКонтекст: ").append(input.trim())
                append("\nОтвет: ").append(output.trim())
            }
        }
        if (instruction != null && input != null) {
            return "Запрос: ${instruction.trim()}\nВвод: ${input.trim()}"
        }

        // messages: [{role, content}, ...]
        val messages = obj.optJSONArray("messages")
        if (messages != null && messages.length() > 0) {
            val parts = ArrayList<String>()
            for (i in 0 until minOf(messages.length(), 6)) {
                val m = messages.optJSONObject(i) ?: continue
                val role = m.optString("role", "")
                val content = m.optString("content", "").trim()
                if (content.isEmpty()) continue
                val label = when (role.lowercase(Locale.US)) {
                    "user", "human" -> "Пользователь"
                    "assistant", "gpt", "bot" -> "Ассистент"
                    "system" -> "Система"
                    else -> role.ifBlank { "Текст" }
                }
                parts.add("$label: $content")
            }
            if (parts.isNotEmpty()) return parts.joinToString("\n")
        }

        // простой text / content / caption
        firstString(obj, listOf("text", "content", "caption", "sentence", "utterance", "label", "title", "description"))
            ?.let { return it }

        // последняя попытка: склеить все строковые поля осмысленно
        val keys = obj.keys().asSequence().toList()
        val stringFields = keys.mapNotNull { k ->
            val v = obj.opt(k)
            when (v) {
                is String -> if (v.isNotBlank() && v.length < 500 && !v.trimStart().startsWith("{")) k to v.trim() else null
                is Number, is Boolean -> k to v.toString()
                else -> null
            }
        }
        if (stringFields.size >= 2) {
            // если похоже на табличную строку
            return stringFields.joinToString(". ") { (k, v) -> "$k: $v" }
        }
        if (stringFields.size == 1) return stringFields[0].second

        return null // сырой JSON не сохраняем
    }

    private fun phraseFromLangMap(obj: JSONObject): String? {
        val en = firstString(obj, listOf("en", "eng", "english", "en_us", "en-us"))
        val ru = firstString(obj, listOf("ru", "rus", "russian", "ru_ru", "ru-ru"))
        val de = firstString(obj, listOf("de", "ger", "german"))
        val fr = firstString(obj, listOf("fr", "fra", "french"))
        val es = firstString(obj, listOf("es", "spa", "spanish"))
        val pairs = listOfNotNull(
            en?.let { "en" to it },
            ru?.let { "ru" to it },
            de?.let { "de" to it },
            fr?.let { "fr" to it },
            es?.let { "es" to it }
        )
        if (pairs.size >= 2) {
            // предпочитаем en→ru
            val src = pairs.firstOrNull { it.first == "en" }?.second ?: pairs[0].second
            val tgt = pairs.firstOrNull { it.first == "ru" }?.second
                ?: pairs.firstOrNull { it.first != pairs[0].first }?.second
            if (src != null && tgt != null) return formatTranslation(src, tgt)
        }
        return null
    }

    private fun formatTranslation(source: String, target: String): String {
        val s = source.trim()
        val t = target.trim()
        // несколько формулировок — модель учит связь, а не JSON
        return """
            Перевод на русский: $s → $t
            Как по-русски «$s»? — $t
            $s по-русски: $t
        """.trimIndent()
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String? {
        for (k in keys) {
            if (!obj.has(k)) continue
            val v = obj.opt(k)
            when (v) {
                is String -> if (v.isNotBlank()) return v
                is JSONArray -> {
                    // иногда ["hello"]
                    if (v.length() > 0) {
                        val s = v.optString(0, "")
                        if (s.isNotBlank()) return s
                    }
                }
                is Number, is Boolean -> return v.toString()
            }
        }
        return null
    }

    private fun looksLikeRawJsonDump(s: String): Boolean {
        val t = s.trim()
        if (t.startsWith("{") && t.contains("\":")) return true
        if (t.startsWith("[") && t.contains("{")) return true
        return false
    }

    // ─── CSV / TSV ───────────────────────────────────────

    private fun streamCsv(
        input: InputStream,
        sep: Char,
        name: String,
        setScanned: (Int) -> Unit,
        emit: (String, String, String?) -> Unit
    ) {
        var scanned = 0
        openReader(input).use { br ->
            val headerLine = br.readLine() ?: return
            val headers = splitCsvLine(headerLine, sep).map { it.trim().trim('"').lowercase(Locale.US) }
            var line: String?
            while (br.readLine().also { line = it } != null) {
                scanned++
                val step = when {
                    scanned > 80_000 -> 25
                    scanned > 20_000 -> 8
                    else -> 1
                }
                if (scanned % step != 0) continue
                val cols = splitCsvLine(line ?: continue, sep).map { it.trim().trim('"') }
                if (cols.all { it.isBlank() }) continue
                val map = HashMap<String, String>()
                for (i in headers.indices) {
                    if (i < cols.size) map[headers[i]] = cols[i]
                }
                phraseFromCsvRow(headers, cols, map)?.let {
                    emit(it, TrainingItem.TYPE_TABULAR, name)
                }
            }
        }
        setScanned(scanned)
    }

    private fun phraseFromCsvRow(
        headers: List<String>,
        cols: List<String>,
        map: Map<String, String>
    ): String? {
        fun col(vararg names: String): String? {
            for (n in names) {
                val v = map[n]
                if (!v.isNullOrBlank()) return v
            }
            // частичное совпадение имени колонки
            for ((k, v) in map) {
                if (v.isBlank()) continue
                for (n in names) if (k.contains(n)) return v
            }
            return null
        }

        val en = col("en", "eng", "english", "source", "src", "input", "text_en")
        val ru = col("ru", "rus", "russian", "target", "tgt", "output", "text_ru", "translation")
        if (en != null && ru != null) return formatTranslation(en, ru)

        val instruction = col("instruction", "prompt", "question", "query")
        val output = col("output", "response", "answer", "completion")
        if (instruction != null && output != null) {
            return "Запрос: $instruction\nОтвет: $output"
        }

        val text = col("text", "content", "sentence", "utterance", "caption", "label")
        if (text != null) return text

        // общая склейка: "col1: val1. col2: val2"
        if (headers.isNotEmpty() && cols.isNotEmpty()) {
            val parts = ArrayList<String>()
            for (i in headers.indices) {
                if (i >= cols.size) break
                val h = headers[i]
                val v = cols[i]
                if (v.isBlank() || h.isBlank()) continue
                if (v.length > 300) continue
                parts.add("$h: $v")
            }
            if (parts.isNotEmpty()) return parts.joinToString(". ")
        }
        return cols.firstOrNull { it.length >= 3 }
    }

    private fun splitCsvLine(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i++
                    } else inQuotes = !inQuotes
                }
                c == sep && !inQuotes -> {
                    out.add(cur.toString()); cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    // ─── plain / zip ─────────────────────────────────────

    private fun streamPlainText(
        input: InputStream,
        name: String,
        setScanned: (Int) -> Unit,
        emit: (String, String, String?) -> Unit
    ) {
        var scanned = 0
        val buf = StringBuilder()
        openReader(input).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                scanned++
                val l = line ?: break
                if (l.isBlank()) {
                    if (buf.length >= 3) {
                        emit(buf.toString(), TrainingItem.TYPE_TEXT, name)
                        buf.clear()
                    }
                } else {
                    if (buf.length > MAX_CONTENT) {
                        emit(buf.toString(), TrainingItem.TYPE_TEXT, name)
                        buf.clear()
                    }
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(l.take(400))
                }
            }
        }
        if (buf.length >= 3) emit(buf.toString(), TrainingItem.TYPE_TEXT, name)
        setScanned(scanned)
    }

    private fun streamZip(input: InputStream, emit: (String, String, String?) -> Unit) {
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            var files = 0
            while (e != null && files < 40) {
                if (!e.isDirectory) {
                    files++
                    val en = e.name.substringAfterLast('/')
                    val ext = en.substringAfterLast('.', "").lowercase(Locale.US)
                    var scanned = 0
                    when (ext) {
                        "jsonl" -> streamJsonl(zis, en, { scanned = it }, emit)
                        "json" -> streamJson(zis, en, emit)
                        "csv" -> streamCsv(zis, ',', en, { scanned = it }, emit)
                        "tsv" -> streamCsv(zis, '\t', en, { scanned = it }, emit)
                        "txt", "md", "log" -> streamPlainText(zis, en, { scanned = it }, emit)
                        else -> emit("Файл в архиве: $en", TrainingItem.TYPE_OTHER, en)
                    }
                }
                try { zis.closeEntry() } catch (_: Throwable) { }
                e = try { zis.nextEntry } catch (_: Throwable) { null }
            }
        }
    }

    private fun openReader(input: InputStream): BufferedReader =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 8 * 1024)

    private fun readPrefix(input: InputStream, maxChars: Int): String {
        val br = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 8 * 1024)
        val sb = StringBuilder()
        val buf = CharArray(4 * 1024)
        var left = maxChars
        while (left > 0) {
            val n = br.read(buf, 0, minOf(buf.size, left))
            if (n <= 0) break
            sb.append(buf, 0, n)
            left -= n
        }
        return sb.toString()
    }
}