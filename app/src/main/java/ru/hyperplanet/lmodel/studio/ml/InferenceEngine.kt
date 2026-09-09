package ru.hyperplanet.lmodel.studio.ml

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.ln
import kotlin.math.min

object InferenceEngine {
    private val gson = Gson()

    data class GenerationResult(val text: String, val reasoning: String, val promptTokens: Int, val completionTokens: Int)
    data class HistoryMessage(val isUser: Boolean, val text: String)

    fun serializeTrained(data: MarkovTrainer.TrainedData): String = gson.toJson(data)
    fun deserializeTrained(json: String?): MarkovTrainer.TrainedData? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, object : TypeToken<MarkovTrainer.TrainedData>() {}.type)
        } catch (_: Exception) { null }
    }

    fun countTokens(text: String): Int =
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    fun generateResponse(
        trainedDataJson: String?,
        systemPrompt: String,
        userMessage: String,
        parameters: Map<String, String> = emptyMap(),
        history: List<HistoryMessage> = emptyList(),
        lockedLanguage: String? = null
    ): GenerationResult {
        val log = StringBuilder()
        fun step(t: String) { log.appendLine(); log.appendLine("▸ $t") }
        fun line(s: String) { log.appendLine("  $s") }

        val promptTokens = countTokens(systemPrompt) + countTokens(userMessage) +
                history.takeLast(8).sumOf { countTokens(it.text) }

        step("1. Вход")
        line("Вопрос: «${userMessage.take(200)}»")
        line("История: ${history.size} реплик")
        val temperature = parameters["temperature"]?.toFloatOrNull()?.coerceIn(0.1f, 2.0f) ?: 0.9f
        val maxWords = adaptiveMaxWords(userMessage, parameters)
        val topK = parameters["top_k"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val creativity = parameters["creativity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: (temperature / 2f).coerceIn(0f, 1f)


        step("2. Языковой замок")
        var active = LanguageLock.fromCode(lockedLanguage)
        val detected = LanguageLock.detect(userMessage)
        line("Язык сообщения: ${LanguageLock.name(detected)}")
        if (lockedLanguage == null && detected != LanguageLock.Lang.OTHER) {
            active = detected
            line("Фиксация: ${LanguageLock.name(active)}")
        } else if (lockedLanguage != null) {
            line("Зафиксирован: ${LanguageLock.name(active)}")
            if (detected != active && detected != LanguageLock.Lang.OTHER) {
                if (LanguageLock.isLanguageSwitchAllowed(userMessage)) {
                    active = detected
                    line("Смена разрешена → ${LanguageLock.name(active)}")
                } else line("Смена запрещена, отвечаем на ${LanguageLock.name(active)}")
            }
        }

        val data = deserializeTrained(trainedDataJson)
        if (data == null || (data.sentences.isEmpty() && data.prologSource.isEmpty() && data.compiledPrologJson.isNullOrBlank())) {
            step("3. Корпус пуст")
            line("trainedDataJson пуст или без предложений — модель не обучена")
            val t = if (active == LanguageLock.Lang.EN)
                "Model is not trained yet. Add texts and train."
            else "Модель не обучена: нет текстов. Добавьте тексты и нажмите «Начать обучение»."
            return GenerationResult(t, log.toString().trim(), promptTokens, countTokens(t))
        }
        if (data.vocabulary.isEmpty() && data.sentences.isEmpty()) {
            step("3. Словарь пуст")
            val t = if (active == LanguageLock.Lang.EN) "Empty vocabulary. Retrain the model."
            else "Словарь пуст. Удалите мусорные данные и переобучите модель."
            return GenerationResult(t, log.toString().trim(), promptTokens, countTokens(t))
        }

        step("3. Корпус")
        line("Предложений: ${data.sentences.size}, словарь: ${data.vocabulary.size}, Prolog: ${data.prologSource.size}")

        val compiled = PrologCompiler.fromJson(data.compiledPrologJson)
        if (compiled != null || data.prologSource.isNotEmpty()) {
            val kb = if (compiled != null) {
                PrologEngine.KnowledgeBase(
                    facts = compiled.facts.map { PrologEngine.Fact(it.predicate, it.args) },
                    rules = compiled.rules.map {
                        PrologEngine.Rule(
                            head = PrologEngine.Fact(it.head.predicate, it.head.args),
                            body = it.body.map { b -> PrologEngine.Fact(b.predicate, b.args) }
                        )
                    }
                )
            } else PrologEngine.parse(data.prologSource)
            line("Prolog скомпилирован: фактов=${kb.facts.size}, правил=${kb.rules.size}" +
                    (compiled?.factsPlPath?.let { ", facts.pl=$it" } ?: ""))
            val looks = userMessage.contains("?-") ||
                    Regex("""[a-z_]+\([^)]*\)""").containsMatchIn(userMessage) ||
                    userMessage.lowercase().let { "prolog" in it || "факт" in it }
            if (looks || kb.facts.isNotEmpty() && userMessage.contains("(")) {
                step("4. Prolog-вывод (из скомпилированного facts.pl)")
                val (ans, r) = PrologEngine.query(kb, userMessage)
                log.appendLine(r)
                return GenerationResult(ans, log.toString().trim(), promptTokens, countTokens(ans))
            }
        }

        step("4. Намерение")
        val lower = userMessage.lowercase().trim()
        val intent = when {
            lower in listOf("привет", "hello", "hi", "здравствуй") -> "greeting"
            "кто ты" in lower || "who are you" in lower -> "identity"
            else -> "general"
        }
        line(intent)

        // greeting: без захардкоженного «Привет!»
        if (intent == "identity") {
            step("5. Ответ")
            val ans = systemPrompt.split(Regex("[.\\n]")).firstOrNull()?.trim()?.take(200)
                ?: if (active == LanguageLock.Lang.EN) "Local model trained on your data."
                else "Локальная модель по вашим текстам."
            return GenerationResult(ans, log.toString().trim(), promptTokens, countTokens(ans))
        }

        step("5. Поиск по корпусу")
        val terms = MarkovTrainer.tokenizeText(userMessage).filter { it.length >= 2 && it !in STOP }
        line("Ключевые слова: [${terms.joinToString(", ")}]")
        val facts = retrieve(data, terms, topK, temperature)
        facts.forEachIndexed { i, f -> line("[$i] ${"%.2f".format(f.second)} «${f.first.take(90)}»") }

        step("6. Сборка ответа")
        if (facts.isEmpty()) {
            val ans = if (active == LanguageLock.Lang.EN) "No relevant knowledge found."
            else "В знаниях нет близких фрагментов."
            return GenerationResult(ans, log.toString().trim(), promptTokens, countTokens(ans))
        }
        // maxWords from parameters above
        val brief = listOf("кратко", "короче", "brief").any { lower.contains(it) }
        val takeN = when {
            brief -> 1
            temperature >= 1.3f -> min(topK, facts.size)
            temperature >= 0.8f -> min(3, facts.size)
            else -> min(2, facts.size)
        }
        val selected = facts.take(takeN)
        line("Берём ${selected.size} фрагмент(ов)")
        val sb = StringBuilder()
        var words = 0
        selected.forEachIndexed { i, (text, _) ->
            val piece = text.trim().trimEnd('.', '!', '?')
            val add = countTokens(piece)
            if (words + add > maxWords && sb.isNotEmpty()) return@forEachIndexed
            if (i == 0) sb.append(piece.replaceFirstChar { it.titlecase() })
            else sb.append(' ').append(piece.replaceFirstChar { it.lowercase() })
            if (!piece.endsWith('.')) sb.append('.')
            words += add
        }
        val answer = styleResponse(sb.toString().trim(), userMessage, maxWords)
        step("7. Итог")
        line("Слов: ${countTokens(answer)}, язык: ${LanguageLock.name(active)}, лимит слов: $maxWords")
        return GenerationResult(answer, log.toString().trim(), promptTokens, countTokens(answer))
    }

    fun generateRagResponse(
        knowledgeChunks: List<String>,
        systemPrompt: String,
        userMessage: String,
        lockedLanguage: String? = null
    ): GenerationResult {
        val log = StringBuilder()
        fun step(t: String) { log.appendLine(); log.appendLine("▸ $t") }
        fun line(s: String) { log.appendLine("  $s") }

        step("1. Режим RAG-бота")
        line("Чанков в базе: ${knowledgeChunks.size}")
        line("Вопрос: «${userMessage.take(200)}»")
        if (systemPrompt.isNotBlank()) line("System prompt: есть")

        step("2. Языковой замок")
        var lock = LanguageLock.fromCode(lockedLanguage)
        val det = LanguageLock.detect(userMessage)
        line("Язык сообщения: ${LanguageLock.name(det)}")
        if (lockedLanguage == null && det != LanguageLock.Lang.OTHER) {
            lock = det
            line("Фиксация языка: ${LanguageLock.name(lock)}")
        } else {
            line("Зафиксирован: ${LanguageLock.name(lock)}")
        }

        step("3. Токенизация запроса")
        val terms = MarkovTrainer.tokenizeText(userMessage).filter { it.length >= 2 && it !in STOP }
        line("Ключевые слова: [${terms.joinToString(", ")}]")

        step("4. Поиск по базе знаний")
        data class Hit(val text: String, val score: Double, val index: Int)
        val hits = knowledgeChunks.mapIndexedNotNull { index, chunk ->
            val words = MarkovTrainer.tokenizeText(chunk).toSet()
            if (words.isEmpty()) return@mapIndexedNotNull null
            val ov = terms.count { it in words }.toDouble()
            if (ov <= 0) return@mapIndexedNotNull null
            Hit(chunk, ov + ov / words.size.coerceAtLeast(1), index)
        }.sortedByDescending { it.score }.take(4)

        if (hits.isEmpty()) {
            line("Совпадений нет")
        } else {
            hits.forEach { h ->
                line("#${h.index} score=${"%.2f".format(h.score)} «${h.text.take(100)}»")
            }
        }

        step("5. Сборка ответа")
        val answer = if (hits.isEmpty()) {
            if (lock == LanguageLock.Lang.EN) "No matching documents in RAG knowledge base."
            else "В базе знаний RAG-бота нет подходящих фрагментов."
        } else {
            hits.joinToString("\n\n") { it.text.trim() }
        }
        line("Фрагментов в ответе: ${hits.size}")
        line("Слов: ${countTokens(answer)}")

        // Всегда непустой reasoning
        val reasoning = log.toString().trim().ifBlank { "▸ RAG: пустой лог (баг)" }
        return GenerationResult(answer, reasoning, countTokens(userMessage), countTokens(answer))
    }

    private fun retrieve(
        data: MarkovTrainer.TrainedData,
        terms: List<String>,
        topK: Int,
        temperature: Float = 0.9f
    ): List<Pair<String, Double>> {
        if (terms.isEmpty() || data.sentences.isEmpty()) return emptyList()
        val scores = mutableMapOf<Int, Double>()
        val n = data.sentences.size.toDouble()
        val termSet = terms.toSet()
        for (term in terms) {
            val idxs = data.invertedIndex[term] ?: continue
            val idf = 1.0 + ln((n + 1) / (idxs.size + 1))
            for (idx in idxs) scores[idx] = (scores[idx] ?: 0.0) + idf
        }
        for ((idx, base) in scores.toList()) {
            val sw = MarkovTrainer.tokenizeText(data.sentences[idx]).toSet()
            val ov = sw.intersect(termSet).size.toDouble()
            if (ov > 0) scores[idx] = base + ov / sw.size.coerceAtLeast(1) * 2.0
        }
        // temperature: выше → больше случайности при выборе среди кандидатов
        val pool = scores.entries.sortedByDescending { it.value }.take((topK * 3).coerceAtLeast(topK))
        if (pool.isEmpty()) return emptyList()
        if (temperature <= 0.35f) {
            return pool.take(topK).map { data.sentences[it.key] to it.value }
        }
        // softmax sampling by score/temperature
        val scaled = pool.map { it to Math.exp(it.value / temperature.coerceAtLeast(0.15f).toDouble()) }
        val sum = scaled.sumOf { it.second }.coerceAtLeast(1e-9)
        val picked = mutableListOf<Pair<String, Double>>()
        val used = mutableSetOf<Int>()
        repeat(topK.coerceAtMost(pool.size)) {
            var r = kotlin.random.Random.nextDouble() * sum
            for ((entry, w) in scaled) {
                if (entry.key in used) continue
                r -= w
                if (r <= 0) {
                    used.add(entry.key)
                    picked.add(data.sentences[entry.key] to entry.value)
                    break
                }
            }
            if (picked.size <= it) {
                val left = pool.firstOrNull { e -> e.key !in used } ?: return@repeat
                used.add(left.key)
                picked.add(data.sentences[left.key] to left.value)
            }
        }
        return picked
    }

    private val STOP = setOf(
        "и","в","не","что","на","я","с","как","а","то","это","the","a","an","is","to","of","and","in"
    )


    /**
     * Структурирует ответ: коротко оставляет как есть; длинный — с заголовками/списками Markdown,
     * которые в чате рендерятся (жирный ≠ сырые звёздочки).
     */
    private fun styleResponse(raw: String, userMessage: String, maxWords: Int): String {
        if (raw.isBlank()) return raw
        var text = raw.trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        // укоротить если превысили адаптивный лимит
        if (words.size > maxWords) {
            text = words.take(maxWords).joinToString(" ")
            if (!text.endsWith(".") && !text.endsWith("!") && !text.endsWith("?")) text += "…"
        }
        val q = userMessage.lowercase()
        val wantsMd = listOf("markdown", "маркдаун", "оформи", "список", "по пунктам", "заголовок")
            .any { q.contains(it) } || text.length > 180 || text.contains('\n')
        // уже есть md — не ломаем
        if (text.contains("**") || text.contains("## ") || text.lines().any { it.trim().startsWith("- ") }) {
            return text
        }
        if (!wantsMd || words.size < 25) return text
        // лёгкая структура длинного ответа
        val paras = text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.size >= 2) {
            return paras.mapIndexed { i, p ->
                if (i == 0) "**Кратко**\n$p" else p
            }.joinToString("\n\n")
        }
        // разбить длинный абзац на пункты по предложениям
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size >= 3) {
            return buildString {
                appendLine("**Ответ**")
                sentences.take(8).forEach { appendLine("- $it") }
            }.trim()
        }
        return text
    }

    /**
     * Длина ответа выбирается по смыслу запроса (не всегда max_length).
     * «кратко» → коротко; «подробно»/сложный вопрос → длиннее.
     */
    private fun adaptiveMaxWords(userMessage: String, parameters: Map<String, String>): Int {
        val cap = parameters["max_length"]?.toIntOrNull()?.coerceIn(10, 500) ?: 120
        val q = userMessage.lowercase()
        val shortHints = listOf(
            "кратко", "коротко", "в двух словах", "одной фразой", "yes/no", "да или нет",
            "только ответ", "без воды", "short", "briefly", "one word"
        )
        val longHints = listOf(
            "подробно", "развёрнуто", "детально", "объясни", "расскажи", "why", "how",
            "пошагово", "с примерами", "максимально", "fully", "in detail", "explain"
        )
        when {
            shortHints.any { q.contains(it) } -> return minOf(cap, 28)
            longHints.any { q.contains(it) } -> return maxOf(cap, minOf(400, cap * 2))
            q.length <= 12 || q.split(Regex("\\s+")).size <= 2 -> return minOf(cap, 40)
            q.contains("?") && q.length > 80 -> return minOf(400, maxOf(cap, 100))
            else -> return cap
        }
    }

}
