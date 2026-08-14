package ru.hyperplanet.lmodel.studio.ml

import kotlin.math.exp
import kotlin.random.Random

/**
 * Оформляет сгенерированный текст Markdown'ом, когда это уместно
 * (как в ответах нормальных ИИ), без превращения всего в кашу из * *.
 */
object AnswerFormatter {

    fun format(
        raw: String,
        analysis: QuestionUnderstanding.Analysis,
        preferredLangName: String?
    ): String {
        var text = raw.trim()
        if (text.isEmpty()) return text

        // Если пользователь сказал «JS», а в тексте всплыло другое имя из той же группы — оставляем его выбор
        if (preferredLangName != null && preferredLangName.isNotBlank()) {
            // не форсируем замену всего текста — генерация уже подсеяна preferred именем
        }

        if (!analysis.wantsMarkdown) {
            return ensureSentence(text)
        }

        return when (analysis.intent) {
            QuestionUnderstanding.Intent.LIST,
            QuestionUnderstanding.Intent.HOW_TO -> asBullets(text)
            QuestionUnderstanding.Intent.DEFINITION -> {
                val parts = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                if (parts.isEmpty()) ensureSentence(text)
                else buildString {
                    append("**")
                    append(parts.first().trim().removeSuffix(".").trim())
                    append("**")
                    append(".\n\n")
                    parts.drop(1).take(6).forEach { appendLine("- ${it.trim()}") }
                }.trim()
            }
            QuestionUnderstanding.Intent.CODE -> {
                // Если уже есть код-подобные куски — обернём
                if ("`" in text) text
                else {
                    val parts = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                    buildString {
                        if (parts.isNotEmpty()) {
                            appendLine(parts.first())
                            appendLine()
                        }
                        val codeish = parts.drop(1).joinToString(" ")
                        if (codeish.isNotBlank()) {
                            appendLine("```")
                            appendLine(codeish)
                            append("```")
                        }
                    }.trim().ifBlank { ensureSentence(text) }
                }
            }
            QuestionUnderstanding.Intent.COMPARE -> asBullets(text)
            else -> {
                // лёгкий акцент на первом предложении
                val parts = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    "**${parts.first().removeSuffix(".")}.**\n\n" + parts.drop(1).joinToString(" ")
                } else ensureSentence(text)
            }
        }
    }

    private fun asBullets(text: String): String {
        val parts = text.split(Regex("(?<=[.!?])\\s+|;\\s+")).map { it.trim() }.filter { it.length > 2 }
        if (parts.size < 2) return ensureSentence(text)
        return parts.take(8).joinToString("\n") { "- ${it.removeSuffix(".")}." }
    }

    private fun ensureSentence(text: String): String {
        var t = text.trim()
        if (t.isNotEmpty() && t.last() !in ".!?…") t += "."
        return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

/**
 * Генерация текста: по словам запроса выбирается старт,
 * дальше слова сэмплируются из bigram/trigram модели (не RAG-склейка предложений).
 */
object TextGenerator {

    fun generate(
        data: MarkovTrainer.TrainedData,
        userMessage: String,
        maxWords: Int,
        temperature: Float
    ): String {
        if (data.vocabulary.isEmpty() && data.bigrams.isEmpty()) return ""

        val queryTokens = MarkovTrainer.tokenizeText(userMessage).filter { it in data.vocabulary }
        val seed = when {
            queryTokens.size >= 2 -> queryTokens.takeLast(2)
            queryTokens.size == 1 -> listOf(queryTokens[0])
            else -> {
                // старт с частого слова
                val top = data.unigrams.entries.sortedByDescending { it.value }.take(20).map { it.key }
                if (top.isEmpty()) return ""
                listOf(top[Random.nextInt(top.size)])
            }
        }

        val out = seed.toMutableList()
        val temp = temperature.coerceIn(0.15f, 2.0f)
        val limit = maxWords.coerceIn(5, 400)

        var guard = 0
        while (out.size < limit && guard < limit * 3) {
            guard++
            val next = nextWord(data, out, temp) ?: break
            out.add(next)
            // мягкая остановка на конце предложения после минимума слов
            if (out.size >= (limit / 3).coerceAtLeast(8) && next in ENDISH && Random.nextFloat() < 0.35f) break
        }

        return detokenize(out)
    }

    private val ENDISH = setOf("это", "так", "все", "всё", "end", "done")

    private fun nextWord(data: MarkovTrainer.TrainedData, context: List<String>, temperature: Float): String? {
        if (context.size >= 2) {
            val key = context[context.size - 2] + "\t" + context[context.size - 1]
            val tri = data.trigrams[key]
            if (tri != null && tri.isNotEmpty()) {
                return sample(tri, temperature)
            }
        }
        if (context.isNotEmpty()) {
            val bi = data.bigrams[context.last()]
            if (bi != null && bi.isNotEmpty()) {
                return sample(bi, temperature)
            }
        }
        // fallback: unigram
        if (data.unigrams.isEmpty()) return null
        return sample(data.unigrams, temperature)
    }

    private fun sample(counts: Map<String, Int>, temperature: Float): String {
        if (counts.isEmpty()) return counts.keys.firstOrNull() ?: ""
        if (temperature <= 0.25f) {
            return counts.maxByOrNull { it.value }?.key ?: counts.keys.first()
        }
        val scaled = counts.map { (w, c) ->
            w to exp(lnSafe(c.toDouble()) / temperature)
        }
        val sum = scaled.sumOf { it.second }.coerceAtLeast(1e-12)
        var r = Random.nextDouble() * sum
        for ((w, weight) in scaled) {
            r -= weight
            if (r <= 0) return w
        }
        return scaled.last().first
    }

    private fun lnSafe(x: Double): Double = if (x <= 0) -20.0 else kotlin.math.ln(x)

    private fun detokenize(tokens: List<String>): String {
        if (tokens.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, t) in tokens.withIndex()) {
            if (i > 0) sb.append(' ')
            if (i == 0) sb.append(t.replaceFirstChar { it.titlecase() })
            else sb.append(t)
        }
        var s = sb.toString().trim()
        if (s.isNotEmpty() && s.last() !in ".!?…") s += "."
        return s
    }
}