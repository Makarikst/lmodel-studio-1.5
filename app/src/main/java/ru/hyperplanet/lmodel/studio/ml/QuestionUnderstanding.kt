package ru.hyperplanet.lmodel.studio.ml

/**
 * Понимание запроса: о чём спросили и как лучше ответить.
 * Не RAG — классификация намерения для генерации и Markdown.
 */
object QuestionUnderstanding {

    enum class Intent {
        GREETING,
        IDENTITY,
        DEFINITION,    // что такое
        HOW_TO,        // как сделать
        WHY,           // почему
        LIST,          // перечисли, какие
        COMPARE,       // чем отличается
        CODE,          // код, пример
        YES_NO,        // да/нет
        CHITCHAT,
        UNKNOWN
    }

    data class Analysis(
        val intent: Intent,
        val wantsMarkdown: Boolean,
        val wantsShort: Boolean,
        val wantsDetailed: Boolean,
        /** Какое имя языка предпочесть в ответе (из синонимов), если есть */
        val preferredLangName: String?,
        val summaryRu: String,
        val summaryEn: String
    )

    fun analyze(
        userMessage: String,
        languageNameGroups: List<List<String>> = emptyList()
    ): Analysis {
        val q = userMessage.lowercase().trim()
        val intent = when {
            isGreeting(q) -> Intent.GREETING
            isIdentity(q) -> Intent.IDENTITY
            listOf("чем отлича", "разница между", "vs ", " versus", "сравни").any { q.contains(it) } -> Intent.COMPARE
            listOf("как ", "how to", "how do", "каким образом", "сделать").any { q.contains(it) } -> Intent.HOW_TO
            listOf("почему", "зачем", "why ").any { q.contains(it) } -> Intent.WHY
            listOf("перечисл", "какие ", "список", "list ", "what are").any { q.contains(it) } -> Intent.LIST
            listOf("код", "пример", "function", "скрипт", "написать программу", "code ").any { q.contains(it) } -> Intent.CODE
            listOf("что такое", "что значит", "что означает", "what is", "what's", "who is").any { q.contains(it) } -> Intent.DEFINITION
            listOf("ли ", "да или нет", "yes or no", "?").any { q.contains(it) } && q.length < 40 -> Intent.YES_NO
            q.length < 20 -> Intent.CHITCHAT
            else -> Intent.UNKNOWN
        }

        val wantsShort = listOf("кратко", "коротко", "в двух словах", "short", "briefly").any { q.contains(it) }
        val wantsDetailed = listOf("подробно", "развёрнуто", "детально", "explain", "in detail").any { q.contains(it) }
        val wantsMd = intent in setOf(Intent.LIST, Intent.HOW_TO, Intent.COMPARE, Intent.CODE, Intent.DEFINITION) ||
            listOf("маркдаун", "markdown", "списком", "по пунктам").any { q.contains(it) } ||
            wantsDetailed

        val preferred = pickLanguageName(q, languageNameGroups)

        val summaryRu = when (intent) {
            Intent.GREETING -> "Это приветствие — ответить коротко и по-человечески."
            Intent.IDENTITY -> "Спрашивают, кто я — ответить по роли модели."
            Intent.DEFINITION -> "Нужно определение: коротко суть, затем детали."
            Intent.HOW_TO -> "Нужна инструкция: шаги по порядку."
            Intent.WHY -> "Нужно объяснение причины."
            Intent.LIST -> "Нужен перечень пунктов."
            Intent.COMPARE -> "Нужно сравнение сторон."
            Intent.CODE -> "Ждут пример кода и пояснение."
            Intent.YES_NO -> "Короткий ответ да/нет с пояснением."
            Intent.CHITCHAT -> "Свободный короткий ответ."
            Intent.UNKNOWN -> "Обычный вопрос — ответить по смыслу запроса."
        }
        val summaryEn = when (intent) {
            Intent.GREETING -> "Greeting — reply briefly."
            Intent.IDENTITY -> "Asking who I am."
            Intent.DEFINITION -> "Needs a definition."
            Intent.HOW_TO -> "Needs step-by-step how-to."
            Intent.WHY -> "Needs a reason/explanation."
            Intent.LIST -> "Needs a list."
            Intent.COMPARE -> "Needs a comparison."
            Intent.CODE -> "Needs code and explanation."
            Intent.YES_NO -> "Yes/no with a short reason."
            Intent.CHITCHAT -> "Casual short reply."
            Intent.UNKNOWN -> "General question — answer by meaning."
        }

        return Analysis(intent, wantsMd, wantsShort, wantsDetailed, preferred, summaryRu, summaryEn)
    }

    /**
     * Если у языка несколько названий (JS / JavaScript / ECMAScript):
     * — пользователь уже сказал одно → отвечаем тем же;
     * — иначе берём первое (основное) имя группы.
     */
    fun pickLanguageName(queryLower: String, groups: List<List<String>>): String? {
        if (groups.isEmpty()) return null
        for (group in groups) {
            val hit = group.firstOrNull { name ->
                queryLower.contains(name.lowercase())
            }
            if (hit != null) return hit
        }
        // вопрос не про конкретный алиас — если в вопросе общее «язык» и одна группа, primary
        return null
    }

    /** Все имена группы, в которой встретился токен (для генерации/замены). */
    fun matchingGroup(queryLower: String, groups: List<List<String>>): List<String>? {
        for (group in groups) {
            if (group.any { queryLower.contains(it.lowercase()) }) return group
        }
        return null
    }

    private fun isGreeting(q: String) =
        listOf("привет", "здравствуй", "hello", "hi ").any { q.contains(it) } || q in setOf("hi", "hello")

    private fun isIdentity(q: String) =
        listOf("кто ты", "что ты", "who are you", "what are you").any { q.contains(it) }
}
