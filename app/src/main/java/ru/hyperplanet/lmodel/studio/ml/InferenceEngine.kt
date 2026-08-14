package ru.hyperplanet.lmodel.studio.ml

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.min

/**
 * Рассуждение ≠ ответ.
 * Рассуждение — как модель «думает»: цель, что знает, черновик, план.
 * Ответ — уже готовый текст пользователю (другая сборка).
 */
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
        val promptTokens = countTokens(systemPrompt) + countTokens(userMessage) +
                history.takeLast(8).sumOf { countTokens(it.text) }

        val temperature = parameters["temperature"]?.toFloatOrNull()?.coerceIn(0.1f, 2.0f) ?: 0.9f
        val maxWords = adaptiveMaxWords(userMessage, parameters)
        val topK = parameters["top_k"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5

        var active = LanguageLock.fromCode(lockedLanguage)
        val detected = LanguageLock.detect(userMessage)
        if (lockedLanguage == null && detected != LanguageLock.Lang.OTHER) {
            active = detected
        } else if (lockedLanguage != null) {
            if (detected != active && detected != LanguageLock.Lang.OTHER &&
                LanguageLock.isLanguageSwitchAllowed(userMessage)
            ) active = detected
        }
        val en = active == LanguageLock.Lang.EN

        val data = deserializeTrained(trainedDataJson)
        if (data == null || (data.sentences.isEmpty() && data.prologSource.isEmpty() && data.compiledPrologJson.isNullOrBlank())) {
            val t = if (en) "Model is not trained yet. Add texts and train."
            else "Модель не обучена: нет текстов. Добавьте тексты и нажмите «Начать обучение»."
            val r = if (en)
                "Goal: answer the user.\nI have no training data, so I cannot build a real line of thought from knowledge."
            else
                "Цель: ответить на вопрос.\nДанных обучения нет — выстроить ход мысли по знаниям не могу."
            return GenerationResult(t, r, promptTokens, countTokens(t))
        }

        // Prolog
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
            val looks = userMessage.contains("?-") ||
                    userMessage.lowercase().let { q ->
                        listOf("верно ли", "является ли", "ли это", "fact", "rule", "prolog").any { q.contains(it) }
                    }
            if (looks) {
                val prologResult = PrologEngine.query(kb, userMessage)
                val ans: String = prologResult.first
                if (ans.isNotBlank()) {
                    val reason = buildMetaReasoning(
                        userMessage = userMessage,
                        en = en,
                        knowledgeNotes = kb.facts.take(6).map { "${it.predicate}(${it.args.joinToString(", ")})" },
                        plan = if (en) listOf("Apply rules", "State the conclusion") else listOf("Применить правила", "Сформулировать вывод")
                    )
                    return GenerationResult(ans.trim(), reason, promptTokens, countTokens(ans))
                }
            }
        }

        val lower = userMessage.lowercase()
        if (isGreeting(lower)) {
            val ans = if (en) "Hello! How can I help?" else "Привет! Чем могу помочь?"
            val reason = buildMetaReasoning(
                userMessage, en,
                knowledgeNotes = emptyList(),
                plan = if (en) listOf("Recognize greeting", "Reply briefly and openly")
                else listOf("Это приветствие", "Ответить коротко и дружелюбно")
            )
            return GenerationResult(ans, reason, promptTokens, countTokens(ans))
        }
        if (isIdentity(lower)) {
            val ans = systemPrompt.split(Regex("[.\\n]")).firstOrNull()?.trim()?.take(200)
                ?: if (en) "Local model trained on your data." else "Локальная модель по вашим текстам."
            val reason = buildMetaReasoning(
                userMessage, en,
                knowledgeNotes = listOf(ans),
                plan = if (en) listOf("Say who I am from the system role") else listOf("Ответить, кто я, по роли модели")
            )
            return GenerationResult(ans, reason, promptTokens, countTokens(ans))
        }

        val terms = MarkovTrainer.tokenizeText(userMessage).filter { it.length >= 2 && it !in STOP }
        val facts = retrieve(data, terms, maxOf(topK, 5), temperature)
        if (facts.isEmpty()) {
            val ans = if (en) "No relevant knowledge found."
            else "В знаниях нет близких фрагментов."
            val reason = buildMetaReasoning(
                userMessage, en,
                knowledgeNotes = emptyList(),
                plan = if (en) listOf("Search knowledge", "Admit nothing relevant")
                else listOf("Искать в знаниях", "Честно сказать, что близкого нет")
            )
            return GenerationResult(ans, reason, promptTokens, countTokens(ans))
        }

        // --- Reasoning: meta + knowledge as NOTES (not final prose) ---
        val notes = facts.take(min(5, facts.size)).map { it.first.trim() }
        val brief = listOf("кратко", "короче", "brief", "short").any { lower.contains(it) }
        val detailed = listOf("подробно", "развёрнуто", "детально", "explain", "подробн").any { lower.contains(it) }
        val plan = when {
            brief && en -> listOf("Give a short definition", "One example max", "Stop")
            brief -> listOf("Короткое определение", "Максимум один пример", "Без воды")
            detailed && en -> listOf("Clear definition", "Metaphor or example", "Key details", "Short wrap-up")
            detailed -> listOf("Ясное определение", "Метафора или пример", "Важные детали", "Краткий итог")
            en -> listOf("Definition first", "Main points from knowledge", "Simple language")
            else -> listOf("Сначала определение", "Главное из знаний", "Простым языком")
        }
        val reasoning = buildMetaReasoning(userMessage, en, notes, plan)

        // --- Answer: different assembly — user-facing, not a copy of reasoning ---
        val takeN = when {
            brief -> 1
            detailed || temperature >= 1.2f -> min(topK.coerceAtLeast(3), facts.size)
            temperature >= 0.8f -> min(3, facts.size)
            else -> min(2, facts.size)
        }
        val selected = facts.take(takeN)
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
        return GenerationResult(answer, reasoning, promptTokens, countTokens(answer))
    }

    fun generateRagResponse(
        knowledgeChunks: List<String>,
        systemPrompt: String,
        userMessage: String,
        lockedLanguage: String? = null
    ): GenerationResult {
        var lock = LanguageLock.fromCode(lockedLanguage)
        val det = LanguageLock.detect(userMessage)
        if (lockedLanguage == null && det != LanguageLock.Lang.OTHER) lock = det
        val en = lock == LanguageLock.Lang.EN

        val terms = MarkovTrainer.tokenizeText(userMessage).filter { it.length >= 2 && it !in STOP }
        data class Hit(val text: String, val score: Double)
        val hits = knowledgeChunks.mapNotNull { chunk ->
            val words = MarkovTrainer.tokenizeText(chunk).toSet()
            if (words.isEmpty()) return@mapNotNull null
            val ov = terms.count { it in words }.toDouble()
            if (ov <= 0) return@mapNotNull null
            Hit(chunk, ov + ov / words.size.coerceAtLeast(1))
        }.sortedByDescending { it.score }.take(4)

        val answer = if (hits.isEmpty()) {
            if (en) "No matching documents in RAG knowledge base."
            else "В базе знаний RAG-бота нет подходящих фрагментов."
        } else hits.joinToString("\n\n") { it.text.trim() }

        val reasoning = buildMetaReasoning(
            userMessage, en,
            knowledgeNotes = hits.map { it.text.trim().take(200) },
            plan = if (en) listOf("Search RAG base", "Pick closest chunks", "Answer from them")
            else listOf("Искать в базе RAG", "Взять ближайшие фрагменты", "Ответить по ним")
        )
        return GenerationResult(answer, reasoning, countTokens(userMessage), countTokens(answer))
    }

    /**
     * Ход мысли в стиле «размышления»: цель → заметки из знаний → план ответа.
     * Это не копия финального ответа.
     */
    private fun buildMetaReasoning(
        userMessage: String,
        en: Boolean,
        knowledgeNotes: List<String>,
        plan: List<String>
    ): String = buildString {
        if (en) {
            appendLine("What is the user asking?")
            appendLine("«${userMessage.take(200)}»")
            appendLine()
            if (knowledgeNotes.isNotEmpty()) {
                appendLine("What I can use from my knowledge (draft notes, not the final answer):")
                knowledgeNotes.forEach { appendLine("• ${it.take(180)}") }
                appendLine()
            } else {
                appendLine("No solid knowledge notes for this.")
                appendLine()
            }
            appendLine("How I will answer:")
            plan.forEach { appendLine("• $it") }
        } else {
            appendLine("Что спрашивают?")
            appendLine("«${userMessage.take(200)}»")
            appendLine()
            if (knowledgeNotes.isNotEmpty()) {
                appendLine("Что могу опереться на из своих знаний (черновые заметки, не финальный ответ):")
                knowledgeNotes.forEach { appendLine("• ${it.take(180)}") }
                appendLine()
            } else {
                appendLine("Плотных заметок по знаниям нет.")
                appendLine()
            }
            appendLine("Как отвечу:")
            plan.forEach { appendLine("• $it") }
        }
    }.trim()

    private fun isGreeting(lower: String) =
        listOf("привет", "здравствуй", "hello", "hi ").any { lower.contains(it) } ||
                lower.trim() in setOf("hi", "hello")

    private fun isIdentity(lower: String) =
        listOf("кто ты", "что ты", "who are you", "what are you").any { lower.contains(it) }

    private fun retrieve(
        data: MarkovTrainer.TrainedData,
        terms: List<String>,
        topK: Int,
        temperature: Float = 0.9f
    ): List<Pair<String, Double>> {
        if (data.sentences.isEmpty()) return emptyList()
        val scored = data.sentences.mapIndexed { idx, sent ->
            val words = MarkovTrainer.tokenizeText(sent).toSet()
            val ov = terms.count { it in words }.toDouble()
            val score = if (terms.isEmpty()) 0.1 else ov + ov / words.size.coerceAtLeast(1)
            idx to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        val pool = scored.take((topK * 3).coerceAtLeast(topK).coerceAtMost(scored.size))
        if (pool.isEmpty()) return emptyList()
        if (temperature <= 0.35f) {
            return pool.take(topK).map { data.sentences[it.first] to it.second }
        }
        val scaled = pool.map { it to Math.exp(it.second / temperature.coerceAtLeast(0.15f).toDouble()) }
        val sum = scaled.sumOf { it.second }.coerceAtLeast(1e-9)
        val picked = mutableListOf<Pair<String, Double>>()
        val used = mutableSetOf<Int>()
        repeat(topK.coerceAtMost(pool.size)) {
            var r = kotlin.random.Random.nextDouble() * sum
            for ((entry, w) in scaled) {
                if (entry.first in used) continue
                r -= w
                if (r <= 0) {
                    used.add(entry.first)
                    picked.add(data.sentences[entry.first] to entry.second)
                    break
                }
            }
            if (picked.size <= it) {
                val left = pool.firstOrNull { e -> e.first !in used } ?: return@repeat
                used.add(left.first)
                picked.add(data.sentences[left.first] to left.second)
            }
        }
        return picked
    }

    private val STOP = setOf(
        "и", "в", "не", "что", "на", "я", "с", "как", "а", "то", "это", "the", "a", "an", "is", "to", "of", "and", "in"
    )

    private fun styleResponse(raw: String, userMessage: String, maxWords: Int): String {
        if (raw.isBlank()) return raw
        var text = raw.trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > maxWords) {
            text = words.take(maxWords).joinToString(" ")
            if (!text.endsWith(".") && !text.endsWith("!") && !text.endsWith("?")) text += "…"
        }
        return text
    }

    private fun adaptiveMaxWords(userMessage: String, parameters: Map<String, String>): Int {
        val cap = parameters["max_length"]?.toIntOrNull()?.coerceIn(10, 500) ?: 120
        val q = userMessage.lowercase()
        return when {
            listOf("кратко", "коротко", "short", "briefly").any { q.contains(it) } -> minOf(cap, 28)
            listOf("подробно", "развёрнуто", "explain", "in detail").any { q.contains(it) } ->
                maxOf(cap, minOf(400, cap * 2))
            q.length <= 12 || q.split(Regex("\\s+")).size <= 2 -> minOf(cap, 40)
            else -> cap
        }
    }
}
