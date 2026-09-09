package ru.hyperplanet.lmodel.studio

import android.os.Bundle
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
import ru.hyperplanet.lmodel.studio.databinding.ActivityPrologTrainBinding
import ru.hyperplanet.lmodel.studio.ml.InferenceEngine
import ru.hyperplanet.lmodel.studio.ml.MarkovTrainer
import ru.hyperplanet.lmodel.studio.ml.PrologCompiler
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.ml.ModelRemoteSync

class PrologTrainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPrologTrainBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId = -1L
    private lateinit var adapter: TrainingItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrologTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_prolog_train)
        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)
        if (modelId == -1L) { finish(); return }

        adapter = TrainingItemAdapter(onDelete = {
            lifecycleScope.launch(Dispatchers.IO) {
                db.trainingItemDao().delete(it)
                ModelTrainingSync.clearIfEmpty(db, modelId)
            }
        })
        binding.rvPrologItems.layoutManager = LinearLayoutManager(this)
        binding.rvPrologItems.adapter = adapter
        db.trainingItemDao().observeForModel(modelId).observe(this) {
            adapter.submitList(it.filter { i -> i.type == TrainingItem.TYPE_PROLOG })
        }

        binding.btnAddProlog.setOnClickListener {
            val t = binding.etProlog.text.toString().trim()
            if (t.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                db.trainingItemDao().insert(
                    TrainingItem(modelId = modelId, type = TrainingItem.TYPE_PROLOG, content = t)
                )
                withContext(Dispatchers.Main) { binding.etProlog.setText("") }
            }
        }
        binding.btnTrainProlog.setOnClickListener { train() }
    }

    private fun train() {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { db.trainingItemDao().getAllForModel(modelId) }
            val texts = all.filter { it.type == TrainingItem.TYPE_TEXT }.map { it.content }
            val prologs = all.filter { it.type == TrainingItem.TYPE_PROLOG }.map { it.content }
            if (prologs.isEmpty()) {
                Toast.makeText(this@PrologTrainActivity, R.string.error_need_prolog, Toast.LENGTH_LONG).show()
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                // 1) Сохранить скрипт в facts.pl
                val factsPl = PrologCompiler.writeFactsPl(this@PrologTrainActivity, modelId, prologs)
                // 2) Скомпилировать facts.pl → структура для модели
                val compiled = PrologCompiler.compileFactsPl(factsPl)
                val compiledJson = PrologCompiler.toJson(compiled)
                val trained = MarkovTrainer.train(texts, prologs, compiledJson)
                val json = InferenceEngine.serializeTrained(trained)
                val cur = db.modelDao().getById(modelId)
                if (cur != null) {
                    db.modelDao().update(cur.copy(isTrained = true, trainedDataJson = json))
                }
                Triple(factsPl.absolutePath, compiled.facts.size, compiled.rules.size)
            }

            Toast.makeText(
                this@PrologTrainActivity,
                "facts.pl → ${result.first}\nФактов: ${result.second}, правил: ${result.third}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        PersistentMediaStorage.backupDatabase(this)
    }
}
