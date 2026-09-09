package ru.hyperplanet.lmodel.studio

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.hyperplanet.lmodel.studio.databinding.ActivityCodingLanguagesBinding
import ru.hyperplanet.lmodel.studio.util.CodingLangStore
import ru.hyperplanet.lmodel.studio.util.CodingLangStore.Language

/**
 * Список языков (до 60).
 * «Добавить язык» → диалог: до 10 названий этого языка → сохранить в список.
 * «Сохранить и открыть обучение» → CodingTrainActivity.
 */
class CodingLanguagesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodingLanguagesBinding
    private var modelId = -1L
    private val languages = mutableListOf<Language>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodingLanguagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_coding_languages)
        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)
        if (modelId == -1L) { finish(); return }

        languages.clear()
        languages.addAll(CodingLangStore.get(this, modelId))
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.listLanguages.adapter = adapter
        refreshList()

        binding.listLanguages.setOnItemLongClickListener { _, _, position, _ ->
            languages.removeAt(position)
            refreshList()
            true
        }

        // Поле сверху больше не для «пачки языков», а подсказка — кнопка открывает диалог
        binding.etLanguageName.hint = "Нажми «Добавить язык»"
        binding.etLanguageName.isEnabled = false

        binding.btnAddLanguage.setOnClickListener { showAddLanguageDialog() }
        binding.btnSaveLanguages.setOnClickListener { saveAndOpenTrain() }
        updateCount()
    }

    private fun refreshList() {
        adapter.clear()
        languages.forEachIndexed { i, lang ->
            adapter.add("${i + 1}. ${lang.display()}")
        }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        binding.tvLangCount.text = getString(
            R.string.coding_lang_count,
            languages.size,
            CodingLangStore.MAX_LANGUAGES
        )
    }

    private fun showAddLanguageDialog() {
        if (languages.size >= CodingLangStore.MAX_LANGUAGES) {
            Toast.makeText(
                this,
                getString(R.string.error_language_max, CodingLangStore.MAX_LANGUAGES),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val input = android.widget.EditText(this).apply {
            hint = "До 10 названий через запятую\nНапр: JS, JavaScript, ECMAScript"
            minLines = 3
            setPadding(48, 32, 48, 32)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8A8790.toInt())
        }

        AlertDialog.Builder(this)
            .setTitle("Добавить язык")
            .setMessage("Один язык — до ${CodingLangStore.MAX_NAMES_PER_LANGUAGE} разных названий.")
            .setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val raw = input.text?.toString().orEmpty()
                val names = raw
                    .split(",", ";", "\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(CodingLangStore.MAX_NAMES_PER_LANGUAGE)
                if (names.isEmpty()) {
                    Toast.makeText(this, R.string.error_language_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                languages.add(Language(names))
                refreshList()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveAndOpenTrain() {
        if (languages.isEmpty()) {
            Toast.makeText(this, R.string.error_language_need_one, Toast.LENGTH_LONG).show()
            return
        }
        val err = CodingLangStore.save(this, modelId, languages)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, CodingTrainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MODEL_ID, modelId)
        )
        finish()
    }
}
