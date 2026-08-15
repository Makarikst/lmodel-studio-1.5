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
import ru.hyperplanet.lmodel.studio.ml.ModelRemoteSync.buildForModel
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.util.MediaFileAnalyzer

class TrainModelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrainModelBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId = -1L
    private lateinit var adapter: TrainingItemAdapter

    private var pendingUri: Uri? = null
    private var pendingResult: MediaFileAnalyzer.Result? = null
    private var pickMode = MediaFileAnalyzer.Kind.TEXT

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrainModelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_train_model)
        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)
        if (modelId == -1L) { finish(); return }

        adapter = TrainingItemAdapter(onDelete = { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                db.trainingItemDao().deleteById(item.id)
                val cleared = ModelTrainingSync.clearIfEmpty(db, modelId)
                if (!cleared) ModelTrainingSync.sync(db, modelId)
                withContext(Dispatchers.Main) {
                    if (cleared) Toast.makeText(this@TrainModelActivity, "Данных нет — модель не обучена", Toast.LENGTH_LONG).show()
                }
            }
        })
        binding.rvTrainingItems.layoutManager = LinearLayoutManager(this)
        binding.rvTrainingItems.adapter = adapter
        db.trainingItemDao().observeForModel(modelId).observe(this) { adapter.submitList(it) }

        binding.btnAddText.setOnClickListener { addPlainText() }
        binding.btnStartTraining.setOnClickListener { startTraining() }

        binding.btnAddFile.setOnClickListener {
            pickMode = MediaFileAnalyzer.Kind.TEXT
            openDoc.launch(arrayOf("*/*"))
        }
        binding.btnAddPhoto.setOnClickListener {
            pickMode = MediaFileAnalyzer.Kind.PHOTO
            openDoc.launch(arrayOf("image/*"))
        }
        binding.btnAddAudio.setOnClickListener {
            pickMode = MediaFileAnalyzer.Kind.AUDIO
            openDoc.launch(arrayOf("audio/*"))
        }
        binding.btnAddVideo.setOnClickListener {
            pickMode = MediaFileAnalyzer.Kind.VIDEO
            openDoc.launch(arrayOf("video/*"))
        }
        binding.btnSaveMedia.setOnClickListener { savePendingMedia() }
    }

    private fun onPicked(uri: Uri) {
        lifecycleScope.launch {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            try {
                val result = withContext(Dispatchers.IO) {
                    MediaFileAnalyzer.ingest(this@TrainModelActivity, uri)
                }
                pendingUri = uri
                pendingResult = result
                binding.tvPendingFile.text = "Выбрано: ${result.displayName} (${result.kind})\n${result.analysis}"
                Toast.makeText(this@TrainModelActivity, "Добавь описание и нажми «Сохранить»", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@TrainModelActivity, e.message ?: "Ошибка файла", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun savePendingMedia() {
        val result = pendingResult
        if (result == null) {
            Toast.makeText(this, "Сначала выбери файл", Toast.LENGTH_SHORT).show()
            return
        }
        val desc = binding.etMediaDescription.text?.toString()?.trim().orEmpty()
        if (desc.isEmpty() && result.kind != MediaFileAnalyzer.Kind.TEXT) {
            Toast.makeText(this, "Опиши файл — так модель поймёт, что это", Toast.LENGTH_LONG).show()
            return
        }
        val type = when (result.kind) {
            MediaFileAnalyzer.Kind.PHOTO -> TrainingItem.TYPE_PHOTO
            MediaFileAnalyzer.Kind.VIDEO -> TrainingItem.TYPE_VIDEO
            MediaFileAnalyzer.Kind.AUDIO -> TrainingItem.TYPE_AUDIO
            MediaFileAnalyzer.Kind.TEXT -> TrainingItem.TYPE_FILE
            MediaFileAnalyzer.Kind.OTHER -> TrainingItem.TYPE_FILE
        }
        val content = if (result.kind == MediaFileAnalyzer.Kind.TEXT || result.kind == MediaFileAnalyzer.Kind.OTHER) {
            MediaFileAnalyzer.trainingBlob(desc, result)
        } else {
            desc
        }
        lifecycleScope.launch(Dispatchers.IO) {
            db.trainingItemDao().insert(
                TrainingItem(
                    modelId = modelId,
                    type = type,
                    content = content,
                    mediaPath = result.savedFile.absolutePath,
                    analysisText = result.analysis,
                    originalName = result.displayName
                )
            )
            ModelTrainingSync.sync(db, modelId)
            withContext(Dispatchers.Main) {
                pendingResult = null
                pendingUri = null
                binding.tvPendingFile.text = ""
                binding.etMediaDescription.setText("")
                Toast.makeText(this@TrainModelActivity, "Сохранено в обучение", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addPlainText() {
        val t = binding.etTrainingText.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_text, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            db.trainingItemDao().insert(TrainingItem(modelId = modelId, type = TrainingItem.TYPE_TEXT, content = t))
            ModelTrainingSync.sync(db, modelId)
            withContext(Dispatchers.Main) {
                binding.etTrainingText.setText("")
                Toast.makeText(this@TrainModelActivity, R.string.toast_text_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startTraining() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { ModelTrainingSync.sync(db, modelId) }
            if (!status.isTrained) {
                Toast.makeText(this@TrainModelActivity, "Нет данных для обучения", Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(
                this@TrainModelActivity,
                "Обучено: текстов/файлов ${status.textCount},  prolog ${status.prologCount}, слов ${status.vocabSize}",
                Toast.LENGTH_LONG
            ).show()
            withContext(Dispatchers.IO) { buildForModel(this@TrainModelActivity, modelId = modelId)
             }
        }
    }
}
