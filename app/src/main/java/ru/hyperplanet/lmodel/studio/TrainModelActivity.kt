package ru.hyperplanet.lmodel.studio

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.TrainingItemAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.TrainingItem
import ru.hyperplanet.lmodel.studio.databinding.ActivityTrainModelBinding
import ru.hyperplanet.lmodel.studio.ml.InferenceEngine
import ru.hyperplanet.lmodel.studio.ml.MarkovTrainer
import ru.hyperplanet.lmodel.studio.util.DatasetImporter

class TrainModelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrainModelBinding
    private val db by lazy { AppDatabase.getInstance(applicationContext) }
    private var modelId: Long = -1L
    private lateinit var trainingAdapter: TrainingItemAdapter

    private val openDataset = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        modelId = SessionIds.getModel(this)
        if (modelId <= 0L) {
            Toast.makeText(this, "Нет modelId", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // take persistable if possible
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) { }
        importDataset(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityTrainModelBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (t: Throwable) {
            Toast.makeText(this, "layout: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val fromIntent = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1L)
        if (fromIntent > 0L) SessionIds.setModel(this, fromIntent)
        modelId = SessionIds.getModel(this)
        if (modelId <= 0L) {
            Toast.makeText(this, "Сначала сохрани модель", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        trainingAdapter = TrainingItemAdapter(onDelete = { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                try { db.trainingItemDao().deleteById(item.id) } catch (_: Throwable) { }
                reloadPreview()
            }
        })
        binding.rvTrainingItems.layoutManager = LinearLayoutManager(this)
        binding.rvTrainingItems.adapter = trainingAdapter

        // НЕ подписываемся на LiveData всего датасета — только разовый preview
        reloadPreview()

        binding.btnAddText.setOnClickListener {
            val text = binding.etTrainingText.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            val mid = modelId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    db.trainingItemDao().insert(
                        TrainingItem(modelId = mid, type = TrainingItem.TYPE_TEXT, content = text.take(800))
                    )
                    withContext(Dispatchers.Main) {
                        binding.etTrainingText.setText("")
                        Toast.makeText(this@TrainModelActivity, "Добавлено", Toast.LENGTH_SHORT).show()
                    }
                    reloadPreview()
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TrainModelActivity, "add: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        try {
            binding.btnImportDataset.setOnClickListener {
                SessionIds.setModel(this, modelId)
                openDataset.launch("*/*")
            }
        } catch (_: Throwable) { }

        binding.btnStartTraining.setOnClickListener { startTraining() }
    }

    private fun reloadPreview() {
        val mid = modelId
        if (mid <= 0L) return
        lifecycleScope.launch(Dispatchers.IO) {
            val count = try { db.trainingItemDao().countForModel(mid) } catch (_: Throwable) { 0 }
            val preview = try {
                db.trainingItemDao().getSampleForModel(mid, 30)
            } catch (_: Throwable) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                try {
                    trainingAdapter.submitList(preview)
                    title = "Обучение · $count"
                } catch (_: Throwable) { }
            }
        }
    }

    private fun importDataset(uri: Uri) {
        val mid = modelId
        Toast.makeText(this, "Импорт…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            var saved = 0
            val note = try {
                DatasetImporter.importStreaming(
                    context = applicationContext,
                    uri = uri,
                    onBatch = { batch ->
                        // синхронно на IO-потоке, без runBlocking
                        for (e in batch) {
                            try {
                                // insert suspend — нужен runBlocking только здесь
                                kotlinx.coroutines.runBlocking {
                                    db.trainingItemDao().insert(
                                        TrainingItem(
                                            modelId = mid,
                                            type = e.type,
                                            content = e.content,
                                            mediaPath = null,
                                            originalName = e.originalName,
                                            analysisText = null
                                        )
                                    )
                                }
                                saved++
                            } catch (_: Throwable) { }
                        }
                    }
                )
            } catch (t: Throwable) {
                "crash: ${t.javaClass.simpleName}: ${t.message} saved=$saved"
            }

            // принудительно GC после крупного файла
            try { System.gc() } catch (_: Throwable) { }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@TrainModelActivity, "$note · saved=$saved", Toast.LENGTH_LONG).show()
            }
            reloadPreview()
        }
    }

    private fun startTraining() {
        val mid = modelId
        lifecycleScope.launch {
            try {
                val sample = withContext(Dispatchers.IO) {
                    try {
                        db.trainingItemDao().getSampleForModel(mid, 2000)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
                if (sample.isEmpty()) {
                    Toast.makeText(this@TrainModelActivity, "Нет данных", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val corpus = sample.map { it.content }.filter { it.isNotBlank() }
                val trained = withContext(Dispatchers.Default) {
                    MarkovTrainer.train(texts = corpus, prologTexts = emptyList())
                }
                val json = InferenceEngine.serializeTrained(trained)
                val safeJson = if (json.length > 1_000_000) json.take(1_000_000) else json
                withContext(Dispatchers.IO) {
                    db.modelDao().updateTrained(
                        id = mid,
                        isTrained = true,
                        trainedDataJson = safeJson,
                        supportsPhoto = false,
                        supportsAudio = false,
                        supportsVideo = false
                    )
                }
                Toast.makeText(
                    this@TrainModelActivity,
                    "Обучено ${sample.size} · vocab ${trained.vocabulary.size}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                Toast.makeText(this@TrainModelActivity, "Train: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}