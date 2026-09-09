package ru.hyperplanet.lmodel.studio

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.ParameterAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.data.ModelParameter
import ru.hyperplanet.lmodel.studio.data.update
import ru.hyperplanet.lmodel.studio.databinding.ActivityModelEditorBinding
import ru.hyperplanet.lmodel.studio.databinding.DialogParameterEditBinding
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.util.PersistentMediaStorage

class ModelEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelEditorBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId: Long = -1L
    private lateinit var paramAdapter: ParameterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1L)
        if (modelId <= 0L) modelId = SessionIds.getModel(this)
        if (modelId <= 0L) modelId = -1L
        if (modelId > 0L) SessionIds.setModel(this, modelId)

        paramAdapter = ParameterAdapter(onClick = { editParam(it) })
        binding.rvParameters.layoutManager = LinearLayoutManager(this)
        binding.rvParameters.adapter = paramAdapter

        if (modelId != -1L) {
            lifecycleScope.launch {
                val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: return@launch
                binding.etModelName.setText(m.name)
                refreshStatus()
            }
            db.modelParameterDao().observeForModel(modelId).observe(this) { paramAdapter.submitList(it) }
        }

        binding.btnSaveModel.setOnClickListener { saveModel() }
        binding.btnTrainModel.setOnClickListener {
            if (modelId == -1L) {
                Toast.makeText(this, R.string.error_save_model_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SessionIds.setModel(this, modelId)
            startActivity(
                Intent(this, TrainModelActivity::class.java)
                    .putExtra(MainActivity.EXTRA_MODEL_ID, modelId)
            )
        }
        wireOptional(R.id.btnTrainMarkdown) {
            startActivity(Intent(this, MarkdownTrainActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        wireOptional(R.id.btnTrainBash) {
            startActivity(Intent(this, BashTrainActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        wireOptional(R.id.btnTrainCoding) {
            startActivity(Intent(this, CodingLanguagesActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        wireOptional(R.id.btnTrainProlog) {
            startActivity(Intent(this, PrologTrainActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        wireOptional(R.id.btnDevMode) {
            startActivity(Intent(this, DevModeActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
    }

    private fun wireOptional(id: Int, block: () -> Unit) {
        try {
            findViewById<android.view.View>(id)?.setOnClickListener {
                if (modelId == -1L) {
                    Toast.makeText(this, R.string.error_save_model_first, Toast.LENGTH_SHORT).show()
                } else {
                    SessionIds.setModel(this, modelId)
                    block()
                }
            }
        } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        if (modelId > 0L) refreshStatus()
    }

    private fun refreshStatus() {
        val mid = modelId
        if (mid <= 0L) return
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    try {
                        db.trainingItemDao().countForModel(mid)
                    } catch (_: Throwable) {
                        0
                    }
                }
                val m = withContext(Dispatchers.IO) { db.modelDao().getById(mid) }
                binding.tvTrainedStatus.text = when {
                    count <= 0 -> getString(R.string.status_not_trained)
                    m?.isTrained == true -> getString(R.string.status_trained) + " ($count)"
                    else -> getString(R.string.status_not_trained) + " · данных: $count"
                }
            } catch (_: Throwable) { }
        }
    }

    private fun saveModel() {
        val name = binding.etModelName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.error_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (modelId == -1L) {
                    val newId = db.modelDao().insert(ModelEntity(name = name))
                    modelId = newId
                    SessionIds.setModel(this@ModelEditorActivity, newId)
                    listOf("temperature" to "0.9", "max_length" to "80", "top_k" to "5").forEach { (k, v) ->
                        db.modelParameterDao().insert(ModelParameter(modelId = newId, key = k, value = v))
                    }
                } else {
                    val cur = db.modelDao().getById(modelId) ?: return@launch
                    db.modelDao().update(cur.copy(name = name))
                    // Лёгкий sync (сэмпл), не getAll
                    try {
                        ModelTrainingSync.sync(db, modelId)
                    } catch (_: Throwable) { }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ModelEditorActivity, R.string.model_saved, Toast.LENGTH_SHORT).show()
                    refreshStatus()
                    if (modelId > 0L) {
                        db.modelParameterDao().observeForModel(modelId)
                            .observe(this@ModelEditorActivity) { paramAdapter.submitList(it) }
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ModelEditorActivity, "Save: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun editParam(p: ModelParameter) {
        val dbinding = DialogParameterEditBinding.inflate(layoutInflater)
        dbinding.etParamKey.setText(p.key)
        dbinding.etParamKey.isEnabled = false
        dbinding.etParamValue.setText(p.value)
        dbinding.etParamValue.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_parameter_title_edit)
            .setView(dbinding.root)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val v = dbinding.etParamValue.text.toString().trim()
                if (v.isEmpty() || v.toDoubleOrNull() == null) {
                    Toast.makeText(this, R.string.error_number_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        db.modelParameterDao().update(p.copy(value = v))
                    } catch (_: Throwable) { }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        try {
            PersistentMediaStorage.backupDatabase(this)
        } catch (_: Throwable) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(MainActivity.EXTRA_MODEL_ID, modelId)
        if (modelId > 0L) SessionIds.setModel(this, modelId)
    }
}