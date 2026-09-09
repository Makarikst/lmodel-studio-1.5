package ru.hyperplanet.lmodel.studio.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Язык программирования = до 10 названий (синонимы).
 * Языков на модель — до 60.
 */
object CodingLangStore {
    const val MAX_LANGUAGES = 60
    const val MAX_NAMES_PER_LANGUAGE = 10
    private const val PREFS = "coding_langs_v2"

    data class Language(val names: List<String>) {
        fun primary(): String = names.firstOrNull().orEmpty()
        fun display(): String = names.joinToString(" / ")
    }

    fun get(context: Context, modelId: Long): List<Language> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(modelId), "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val namesArr = obj.optJSONArray("names") ?: return@mapNotNull null
                val names = (0 until namesArr.length())
                    .map { namesArr.getString(it).trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_NAMES_PER_LANGUAGE)
                if (names.isEmpty()) null else Language(names)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, modelId: Long, languages: List<Language>): String? {
        if (languages.size > MAX_LANGUAGES) return "Максимум $MAX_LANGUAGES языков"
        for (lang in languages) {
            if (lang.names.isEmpty()) return "У языка должно быть хотя бы одно название"
            if (lang.names.size > MAX_NAMES_PER_LANGUAGE) {
                return "У одного языка максимум $MAX_NAMES_PER_LANGUAGE названий"
            }
        }
        val arr = JSONArray()
        languages.forEach { lang ->
            arr.put(JSONObject().put("names", JSONArray(lang.names)))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(modelId), arr.toString())
            .apply()
        return null
    }

    private fun key(modelId: Long) = "model_$modelId"
}
