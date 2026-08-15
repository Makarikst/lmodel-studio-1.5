package ru.hyperplanet.lmodel.studio.ml

/**
 * Вытаскивает из system prompt, кем модель должна себя считать
 * (имя, роль, описание) — не только первая строка.
 */
object SystemPromptRole {

    data class Role(
        val raw: String,
        val name: String?,
        val description: String
    )

    fun parse(systemPrompt: String): Role {
        val raw = systemPrompt.trim()
        if (raw.isEmpty()) {
            return Role("", null, "")
        }
        val name = extractName(raw)
        return Role(raw = raw, name = name, description = raw)
    }

    private fun extractName(raw: String): String? {
        val patterns = listOf(
            Regex("""(?i)ты\s*[-—–:]?\s*чат-?бот\s+([A-Za-zА-Яа-яёЁ0-9_\-]+)"""),
            Regex("""(?i)ты\s*[-—–:]\s*([A-Za-zА-Яа-яёЁ0-9_\-]+)"""),
            Regex("""(?i)you are\s+(?:a\s+|an\s+)?(?:chatbot\s+)?([A-Za-z0-9_\-]+)"""),
            Regex("""(?i)меня зовут\s+([A-Za-zА-Яа-яёЁ0-9_\-]+)"""),
            Regex("""(?i)your name is\s+([A-Za-z0-9_\-]+)"""),
            Regex("""(?i)бот\s+([A-Za-zА-Яа-яёЁ0-9_\-]+)""")
        )
        for (p in patterns) {
            val m = p.find(raw) ?: continue
            val n = m.groupValues[1].trim()
            if (n.length in 2..40) return n
        }
        return null
    }

    /** Ответ на «кто ты» / «как тебя зовут» строго из system prompt. */
    fun identityAnswer(role: Role, en: Boolean): String {
        if (role.raw.isBlank()) {
            return if (en) "I am a local model trained on your data."
            else "Я локальная модель, обученная на ваших данных."
        }
        // Если промпт короткий — отдать целиком
        if (role.raw.length <= 280) return role.raw
        val name = role.name
        return if (name != null) {
            if (en) "I am $name. ${role.raw.take(200)}"
            else "Я — $name. ${role.raw.take(200)}"
        } else role.raw.take(280)
    }

    fun mentionsSelf(userMessage: String, role: Role): Boolean {
        val q = userMessage.lowercase()
        if (listOf("кто ты", "что ты", "как тебя зовут", "who are you", "your name", "представься").any { q.contains(it) })
            return true
        val n = role.name?.lowercase() ?: return false
        return q.contains(n) && listOf("ты ", "ты,", "who is", "кто так").any { q.contains(it) }
    }
}
