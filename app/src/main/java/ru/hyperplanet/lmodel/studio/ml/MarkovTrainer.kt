package ru.hyperplanet.lmodel.studio.ml

object MarkovTrainer {
    data class TrainedData(
        val sentences: List<String>,
        val invertedIndex: Map<String, List<Int>>,
        val vocabulary: Set<String>,
        val prologSource: List<String> = emptyList(),
        /** Скомпилированный Prolog (JSON PrologCompiler.CompiledProlog) */
        val compiledPrologJson: String? = null
    )

    fun train(
        texts: List<String>,
        prologTexts: List<String> = emptyList(),
        compiledPrologJson: String? = null
    ): TrainedData {
        val sentences = mutableListOf<String>()
        val inverted = mutableMapOf<String, MutableList<Int>>()
        val vocab = mutableSetOf<String>()
        for (text in texts) {
            val cleaned = text.trim()
            if (cleaned.isBlank()) continue
            val parts = cleaned.split(Regex("(?<=[.!?…])\\s+|[\\n\\r]+")).map { it.trim() }.filter { it.length >= 2 }
            val toAdd = if (parts.isEmpty()) listOf(cleaned) else parts
            for (part in toAdd) {
                val idx = sentences.size
                sentences.add(part)
                for (w in tokenizeText(part)) {
                    vocab.add(w)
                    inverted.getOrPut(w) { mutableListOf() }.let { if (it.lastOrNull() != idx) it.add(idx) }
                }
            }
        }
        val limited = sentences.take(1000)
        val limitedIndex = inverted.mapValues { (_, v) -> v.filter { it < limited.size } }.filter { it.value.isNotEmpty() }
        return TrainedData(
            sentences = limited,
            invertedIndex = limitedIndex,
            vocabulary = vocab,
            prologSource = prologTexts.map { it.trim() }.filter { it.isNotEmpty() },
            compiledPrologJson = compiledPrologJson
        )
    }

    fun tokenizeText(raw: String): List<String> =
        raw.split(Regex("\\s+")).map { tokenize(it) }.filter { it.length >= 2 }

    fun tokenize(raw: String): String =
        raw.lowercase().trim().trim { it in ".,!?;:«»\"'()[]{}…—–-" }
            .filter { it.isLetterOrDigit() || it == '-' || it == '\'' }
}
