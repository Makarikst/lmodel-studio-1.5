package ru.hyperplanet.lmodel.studio.ml

object LanguageLock {
    enum class Lang { RU, EN, OTHER }
    fun detect(text: String): Lang {
        val t = text.lowercase()
        val cyr = t.count { it in '\u0400'..'\u04FF' }
        val lat = t.count { it in 'a'..'z' }
        return when {
            cyr > lat && cyr >= 3 -> Lang.RU
            lat > cyr && lat >= 3 -> Lang.EN
            cyr >= lat && cyr > 0 -> Lang.RU
            lat > 0 -> Lang.EN
            else -> Lang.OTHER
        }
    }
    fun code(lang: Lang) = when (lang) { Lang.RU -> "ru"; Lang.EN -> "en"; else -> "other" }
    fun fromCode(code: String?) = when (code) { "ru" -> Lang.RU; "en" -> Lang.EN; else -> Lang.OTHER }
    fun isLanguageSwitchAllowed(userMessage: String): Boolean {
        val l = userMessage.lowercase()
        return listOf(
            "можешь говорить на", "можно на английском", "можно на русском",
            "speak english", "speak russian", "answer in english", "answer in russian",
            "отвечай на английском", "отвечай на русском", "переключись на",
            "switch to english", "switch to russian", "you can speak"
        ).any { l.contains(it) }
    }
    fun systemRule(locked: Lang) = when (locked) {
        Lang.RU -> "LANGUAGE LOCK: Отвечай ТОЛЬКО на русском, пока пользователь явно не разрешит сменить язык."
        Lang.EN -> "LANGUAGE LOCK: Reply ONLY in English until the user explicitly allows switching."
        else -> ""
    }
    fun name(lang: Lang) = when (lang) { Lang.RU -> "русский"; Lang.EN -> "English"; else -> "other" }
}
