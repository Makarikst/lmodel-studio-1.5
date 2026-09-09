package ru.hyperplanet.lmodel.studio.ml

object PrologEngine {
    data class Fact(val predicate: String, val args: List<String>)
    data class Rule(val head: Fact, val body: List<Fact>)
    data class KnowledgeBase(val facts: List<Fact>, val rules: List<Rule>)

    fun parse(texts: List<String>): KnowledgeBase {
        val facts = mutableListOf<Fact>()
        val rules = mutableListOf<Rule>()
        for (text in texts) {
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%") }.forEach { line ->
                val cleaned = line.trimEnd('.')
                if (":-" in cleaned) {
                    val parts = cleaned.split(":-", limit = 2)
                    val head = parseAtom(parts[0].trim()) ?: return@forEach
                    val body = parts[1].split(",").map { it.trim() }.mapNotNull { parseAtom(it) }
                    rules.add(Rule(head, body))
                } else parseAtom(cleaned)?.let { facts.add(it) }
            }
        }
        return KnowledgeBase(facts, rules)
    }

    private fun parseAtom(s: String): Fact? {
        val m = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*)\((.*)\)$""").find(s.trim()) ?: return null
        val args = m.groupValues[2].split(",").map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
        return Fact(m.groupValues[1].lowercase(), args)
    }

    fun query(kb: KnowledgeBase, question: String): Pair<String, String> {
        val log = StringBuilder()
        log.appendLine("═══ PROLOG ═══")
        log.appendLine("Фактов: ${kb.facts.size}, правил: ${kb.rules.size}")
        val q = question.trim().removePrefix("?-").trim().trimEnd('.')
        val atom = parseAtom(q)
        if (atom != null) {
            log.appendLine("Запрос: ${atom.predicate}(${atom.args.joinToString(", ")})")
            val results = resolve(kb, atom, emptyMap())
            if (results.isEmpty()) {
                log.appendLine("Результат: false")
                return "false." to log.toString()
            }
            val lines = results.map { sub ->
                atom.args.joinToString(", ") { a -> if (isVar(a)) sub[a] ?: a else a }
                    .let { "${atom.predicate}($it)" }
            }
            lines.forEach { log.appendLine("  ✓ $it") }
            return lines.joinToString("\n") { "$it." } to log.toString()
        }
        val lower = question.lowercase()
        val related = kb.facts.filter { f -> lower.contains(f.predicate) || f.args.any { lower.contains(it.lowercase()) } }
        log.appendLine("Поиск фактов по тексту вопроса")
        if (related.isEmpty()) return "По базе Prolog ничего не найдено." to log.toString()
        related.take(8).forEach { log.appendLine("  ${it.predicate}(${it.args.joinToString(", ")})") }
        return related.take(8).joinToString("\n") { "${it.predicate}(${it.args.joinToString(", ")})." } to log.toString()
    }

    private fun isVar(s: String) = s.firstOrNull()?.isUpperCase() == true || s.startsWith("_")

    private fun resolve(kb: KnowledgeBase, goal: Fact, sub: Map<String, String>): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        for (fact in kb.facts) {
            if (fact.predicate != goal.predicate || fact.args.size != goal.args.size) continue
            unify(goal.args, fact.args, sub)?.let { out.add(it) }
        }
        for (rule in kb.rules) {
            if (rule.head.predicate != goal.predicate || rule.head.args.size != goal.args.size) continue
            val headSub = unify(goal.args, rule.head.args, sub) ?: continue
            var frames = listOf(headSub)
            for (bg in rule.body) {
                val next = mutableListOf<Map<String, String>>()
                for (fr in frames) {
                    val g = Fact(bg.predicate, bg.args.map { fr[it] ?: it })
                    next.addAll(resolve(kb, g, fr))
                }
                frames = next
                if (frames.isEmpty()) break
            }
            out.addAll(frames)
        }
        return out
    }

    private fun unify(pattern: List<String>, value: List<String>, sub: Map<String, String>): Map<String, String>? {
        if (pattern.size != value.size) return null
        val result = sub.toMutableMap()
        for (i in pattern.indices) {
            val p = result[pattern[i]] ?: pattern[i]
            val v = result[value[i]] ?: value[i]
            when {
                isVar(p) -> result[p] = v
                isVar(v) -> result[v] = p
                p.equals(v, true) -> {}
                else -> return null
            }
        }
        return result
    }
}
