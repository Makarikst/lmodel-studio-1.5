package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.TrainingItemAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.TrainingItem
import ru.hyperplanet.lmodel.studio.util.PersistentMediaStorage
import ru.hyperplanet.lmodel.studio.databinding.ActivityTrainModelBinding
import ru.hyperplanet.lmodel.studio.ml.InferenceEngine
import ru.hyperplanet.lmodel.studio.ml.MarkovTrainer
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.ml.ModelRemoteSync

class TrainModelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrainModelBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId = -1L
    private lateinit var adapter: TrainingItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrainModelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_train_model)
        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)
        if (modelId == -1L) { finish(); return }

        adapter = TrainingItemAdapter(onDelete = { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                db.trainingItemDao().delete(item)
                val cleared = ModelTrainingSync.clearIfEmpty(db, modelId)
                if (!cleared) {
                    // остались тексты — пересобрать индекс
                    ModelTrainingSync.sync(db, modelId)
                }
                withContext(Dispatchers.Main) {
                    if (cleared) {
                        Toast.makeText(
                            this@TrainModelActivity,
                            "Все тексты удалены — модель не обучена",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
        binding.rvTrainingItems.layoutManager = LinearLayoutManager(this)
        binding.rvTrainingItems.adapter = adapter
        db.trainingItemDao().observeForModel(modelId).observe(this) {
            adapter.submitList(it.filter { i -> i.type == TrainingItem.TYPE_TEXT })
        }
        binding.btnAddText.setOnClickListener {
            val t = binding.etTrainingText.text.toString().trim()
            if (t.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                db.trainingItemDao().insert(
                    TrainingItem(modelId = modelId, type = TrainingItem.TYPE_TEXT, content = t)
                )
                withContext(Dispatchers.Main) { binding.etTrainingText.setText("") }
            }
        }
        binding.btnStartTraining.setOnClickListener { train() }
        listOf(binding.btnAddPhoto, binding.btnAddVideo, binding.btnAddAudio).forEach {
            it.visibility = View.GONE
        }
    }

    private fun train() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { ModelTrainingSync.sync(db, modelId) }
            if (!status.isTrained) {
                Toast.makeText(this@TrainModelActivity, R.string.error_need_text, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(
                this@TrainModelActivity,
                getString(R.string.trained_ok, status.textCount, status.sentenceCount, status.vocabSize),
                Toast.LENGTH_LONG
            ).show()
            try {
                val pkg = withContext(Dispatchers.IO) { ModelRemoteSync.buildForModel(this@TrainModelActivity, modelId) }
                if (pkg.error == null) {
                    Toast.makeText(
                        this@TrainModelActivity,
                        "PAW/API пакет обновлён: model.json для этой модели",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {}
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        PersistentMediaStorage.backupDatabase(this)
    }
}
