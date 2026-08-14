package ru.hyperplanet.lmodel.studio.ml

/**
 * Обучение: словарь + биграммы/триграммы для **генерации** следующего слова.
 * Ответ — сэмплирование цепочки слов, а не выдача готового предложения из базы.
 */
object MarkovTrainer {
    data class TrainedData(
        val sentences: List<String>,
        val invertedIndex: Map<String, List<Int>>,
        val vocabulary: Set<String>,
        val prologSource: List<String> = emptyList(),
        val compiledPrologJson: String? = null,
        /** word -> (nextWord -> count) */
        val bigrams: Map<String, Map<String, Int>> = emptyMap(),
        /** "w1\tw2" -> (nextWord -> count) */
        val trigrams: Map<String, Map<String, Int>> = emptyMap(),
        val unigrams: Map<String, Int> = emptyMap()
    )

    fun train(
        texts: List<String>,
        prologTexts: List<String> = emptyList(),
        compiledPrologJson: String? = null
    ): TrainedData {
        val sentences = mutableListOf<String>()
        val inverted = mutableMapOf<String, MutableList<Int>>()
        val vocab = mutableSetOf<String>()
        val bigrams = mutableMapOf<String, MutableMap<String, Int>>()
        val trigrams = mutableMapOf<String, MutableMap<String, Int>>()
        val unigrams = mutableMapOf<String, Int>()

        for (text in texts) {
            val cleaned = text.trim()
            if (cleaned.isBlank()) continue
            val parts = cleaned.split(Regex("(?<=[.!?…])\\s+|[\\n\\r]+")).map { it.trim() }.filter { it.length >= 2 }
            val toAdd = if (parts.isEmpty()) listOf(cleaned) else parts
            for (part in toAdd) {
                val idx = sentences.size
                sentences.add(part)
                val tokens = tokenizeText(part)
                if (tokens.isEmpty()) continue
                for (w in tokens) {
                    vocab.add(w)
                    unigrams[w] = (unigrams[w] ?: 0) + 1
                    inverted.getOrPut(w) { mutableListOf() }.let { if (it.lastOrNull() != idx) it.add(idx) }
                }
                for (i in 0 until tokens.size - 1) {
                    val a = tokens[i]
                    val b = tokens[i + 1]
                    val m = bigrams.getOrPut(a) { mutableMapOf() }
                    m[b] = (m[b] ?: 0) + 1
                }
                for (i in 0 until tokens.size - 2) {
                    val key = tokens[i] + "\t" + tokens[i + 1]
                    val c = tokens[i + 2]
                    val m = trigrams.getOrPut(key) { mutableMapOf() }
                    m[c] = (m[c] ?: 0) + 1
                }
            }
        }
        val limited = sentences.take(2000)
        val limitedIndex = inverted.mapValues { (_, v) -> v.filter { it < limited.size } }.filter { it.value.isNotEmpty() }
        return TrainedData(
            sentences = limited,
            invertedIndex = limitedIndex,
            vocabulary = vocab,
            prologSource = prologTexts.map { it.trim() }.filter { it.isNotEmpty() },
            compiledPrologJson = compiledPrologJson,
            bigrams = bigrams.mapValues { it.value.toMap() },
            trigrams = trigrams.mapValues { it.value.toMap() },
            unigrams = unigrams.toMap()
        )
    }

    fun tokenizeText(raw: String): List<String> =
        raw.split(Regex("\\s+")).map { tokenize(it) }.filter { it.length >= 2 }

    fun tokenize(raw: String): String =
        raw.lowercase().trim().trim { it in ".,!?;:«»\"'()[]{}…—–-" }
            .filter { it.isLetterOrDigit() || it == '-' || it == '\'' }
}
