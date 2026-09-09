package ru.hyperplanet.lmodel.studio.ml

object LanguageLock {
    enum class Lang { RU, EN, OTHER }

    fun fromCode(code: String?): Lang = when (code?.lowercase()?.trim()) {
        "ru", "rus", "russian", "ru-ru" -> Lang.RU
        "en", "eng", "english", "en-us", "en-gb" -> Lang.EN
        else -> Lang.OTHER
    }

    fun detect(text: String): Lang {
        val t = text.trim()
        if (t.isEmpty()) return Lang.OTHER
        var cyr = 0
        var lat = 0
        for (ch in t) {
            when {
                ch in '\u0400'..'\u04FF' -> cyr++
                ch.isLetter() -> lat++
            }
        }
        return when {
            cyr > lat * 0.5 -> Lang.RU
            lat > 0 -> Lang.EN
            else -> Lang.OTHER
        }
    }

    fun name(lang: Lang): String = when (lang) {
        Lang.RU -> "русский"
        Lang.EN -> "English"
        Lang.OTHER -> "other"
    }

    fun isLanguageSwitchAllowed(text: String): Boolean {
        val l = text.lowercase()
        return l.contains("можно на английском") ||
                l.contains("говори по-английски") ||
                l.contains("switch to english") ||
                l.contains("you can speak english") ||
                l.contains("ответь на английском") ||
                l.contains("можно по-английски") ||
                l.contains("можно на русском") ||
                l.contains("говори по-русски") ||
                l.contains("switch to russian") ||
                l.contains("ответь на русском") ||
                l.contains("можно по-русски") ||
                l.contains("разрешаю сменить язык") ||
                l.contains("можно говорить на") ||
                l.contains("speak in ")
    }

    fun toCodeSafe(lang: Lang): String? = when (lang) {
        Lang.RU -> "ru"
        Lang.EN -> "en"
        Lang.OTHER -> null
    }
}
