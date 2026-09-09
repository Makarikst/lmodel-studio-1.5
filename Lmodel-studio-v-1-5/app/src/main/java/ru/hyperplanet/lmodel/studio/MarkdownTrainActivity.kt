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
import ru.hyperplanet.lmodel.studio.databinding.ActivityMarkdownTrainBinding
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync

/**
 * Раздел обучения Markdown: эталонные тексты с правильной разметкой.
 * После добавления нужно нажать обучение модели (Train), чтобы индекс обновился.
 */
class MarkdownTrainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMarkdownTrainBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId = -1L

    private val lessons = listOf(
        """# Заголовок
**Жирный текст** выделяет важное.
*Курсив* — для мягкого акцента.
- пункт списка
- второй пункт

Код: `print("hello")`.""".trimIndent(),
        """Когда нужен **короткий** ответ — пиши мало.
Когда просят **подробно** — пиши развёрнуто и структурируй:

1. Сначала суть
2. Потом детали
3. В конце вывод""".trimIndent(),
        """## Оформление
Не пиши звёздочки как обычный текст.
Если нужен жирный — используй Markdown **так**, чтобы в чате это стало жирным, а не символами.
Список:
- ясно
- кратко
- по делу""".trimIndent(),
        """**Правило длины**
Короткий вопрос → короткий ответ.
Сложный вопрос или «объясни подробно» → длинный ответ с абзацами и списками.""".trimIndent()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarkdownTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_markdown_train)
        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)
        if (modelId == -1L) { finish(); return }

        binding.tvPreview.text = lessons.joinToString("\n\n———\n\n")
        binding.btnAddMarkdownLessons.setOnClickListener { addLessons() }
        binding.btnAddCustomMarkdown.setOnClickListener { addCustom() }
    }

    private fun addLessons() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                lessons.forEach { text ->
                    db.trainingItemDao().insert(
                        TrainingItem(modelId = modelId, type = TrainingItem.TYPE_TEXT, content = text)
                    )
                }
                ModelTrainingSync.sync(db, modelId)
            }
            Toast.makeText(
                this@MarkdownTrainActivity,
                "Добавлено ${lessons.size} Markdown-уроков. Модель переобучена по текстам.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun addCustom() {
        val t = binding.etCustomMarkdown.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) {
            Toast.makeText(this, "Вставь пример с **жирным**, списками и т.д.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.trainingItemDao().insert(
                    TrainingItem(modelId = modelId, type = TrainingItem.TYPE_TEXT, content = t)
                )
                ModelTrainingSync.sync(db, modelId)
            }
            binding.etCustomMarkdown.setText("")
            Toast.makeText(this@MarkdownTrainActivity, "Markdown-пример добавлен", Toast.LENGTH_SHORT).show()
        }
    }
}
