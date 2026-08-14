package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.TrainingItem
import ru.hyperplanet.lmodel.studio.databinding.ActivityCodingTrainBinding
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.util.CodingLangStore

/**
 * Обучение кодингу по выбранным языкам. Готовых уроков нет — только свои примеры.
 */
class CodingTrainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodingTrainBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId = -1L
    private var languages: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodingTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_coding_train)
        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)
        if (modelId == -1L) { finish(); return }

        val langObjs = CodingLangStore.get(this, modelId)
        languages = langObjs.map { it.display() }
        if (langObjs.isEmpty()) {
            Toast.makeText(this, R.string.error_language_need_one, Toast.LENGTH_LONG).show()
            startActivity(
                android.content.Intent(this, CodingLanguagesActivity::class.java)
                    .putExtra(MainActivity.EXTRA_MODEL_ID, modelId)
            )
            finish()
            return
        }

        binding.tvPreview.text = buildString {
            appendLine("Языки (${languages.size}):")
            languages.forEach { appendLine("• $it") }
            appendLine()
            append("Готовых уроков нет. Добавь свои примеры кода и пояснения ниже.")
        }
        binding.btnAddCodingLessons.visibility = android.view.View.GONE
        binding.btnAddCustomCode.setOnClickListener { addCustom() }
        binding.btnChangeLanguages.setOnClickListener {
            startActivity(
                android.content.Intent(this, CodingLanguagesActivity::class.java)
                    .putExtra(MainActivity.EXTRA_MODEL_ID, modelId)
            )
            finish()
        }
    }

    private fun addCustom() {
        val t = binding.etCustomCode.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) {
            Toast.makeText(this, "Вставь пример кода и пояснение", Toast.LENGTH_SHORT).show()
            return
        }
        val tagged = buildString {
            appendLine("Языки: ${languages.joinToString(" | ")}")
            append(t)
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.trainingItemDao().insert(
                    TrainingItem(modelId = modelId, type = TrainingItem.TYPE_TEXT, content = tagged)
                )
                ModelTrainingSync.sync(db, modelId)
            }
            binding.etCustomCode.setText("")
            Toast.makeText(this@CodingTrainActivity, "Пример добавлен в обучение", Toast.LENGTH_SHORT).show()
        }
    }
}
