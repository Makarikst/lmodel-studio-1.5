package ru.hyperplanet.lmodel.studio

import android.content.Intent
import android.os.Bundle
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
import ru.hyperplanet.lmodel.studio.util.PersistentMediaStorage
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.databinding.ActivityModelEditorBinding
import ru.hyperplanet.lmodel.studio.databinding.DialogParameterEditBinding

class ModelEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityModelEditorBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId: Long = -1
    private lateinit var paramAdapter: ParameterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_model_editor)
        modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)

        // Read-only system prompt hint
        binding.etSystemPromptReadonly.apply {
            isFocusable = false
            isClickable = false
            isCursorVisible = false
            keyListener = null
            setText(R.string.model_prompt_readonly_hint)
        }

        paramAdapter = ParameterAdapter(
            onEdit = { _, p -> editParam(p) }
        )
        binding.rvParameters.layoutManager = LinearLayoutManager(this)
        binding.rvParameters.adapter = paramAdapter

        if (modelId != -1L) {
            lifecycleScope.launch {
                val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: return@launch
                binding.etModelName.setText(m.name)
                binding.tvTrainedStatus.text = if (m.isTrained) getString(R.string.status_trained) else getString(R.string.status_not_trained)
            }
            db.modelParameterDao().observeForModel(modelId).observe(this) { paramAdapter.submitList(it) }
        }

        try { binding.btnAddParameter.visibility = android.view.View.GONE } catch (_: Exception) {}
        binding.btnSaveModel.setOnClickListener { saveModel() }
        binding.btnTrainModel.setOnClickListener {
            if (modelId == -1L) { Toast.makeText(this, R.string.error_save_model_first, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startActivity(Intent(this, TrainModelActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        binding.btnTrainMarkdown.setOnClickListener {
            if (modelId == -1L) {
                Toast.makeText(this, R.string.error_save_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, MarkdownTrainActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        binding.btnTrainCoding.setOnClickListener {
            if (modelId == -1L) {
                Toast.makeText(this, R.string.error_save_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, CodingLanguagesActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        binding.btnTrainProlog.setOnClickListener {
            if (modelId == -1L) { Toast.makeText(this, R.string.error_save_model_first, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startActivity(Intent(this, PrologTrainActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
        binding.btnDevMode.setOnClickListener {
            if (modelId == -1L) { Toast.makeText(this, R.string.error_save_model_first, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startActivity(Intent(this, DevModeActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_ID, modelId))
        }
    }

    override fun onResume() {
        super.onResume()
        if (modelId != -1L) lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: return@launch
            binding.tvTrainedStatus.text = if (m.isTrained) getString(R.string.status_trained) else getString(R.string.status_not_trained)
        }
    }

    private fun saveModel() {
        val name = binding.etModelName.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this, R.string.error_name_required, Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            if (modelId == -1L) {
                modelId = db.modelDao().insert(ModelEntity(name = name))
                listOf("temperature" to "0.9", "max_length" to "80", "top_k" to "5").forEach { (k, v) ->
                    db.modelParameterDao().insert(ModelParameter(modelId = modelId, key = k, value = v))
                }
            } else {
                val cur = db.modelDao().getById(modelId) ?: return@launch
                db.modelDao().update(cur.copy(name = name))
                // Синхронизация: нет текстов → не обучена
                ModelTrainingSync.sync(db, modelId)
            }
            val m = db.modelDao().getById(modelId)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ModelEditorActivity, R.string.model_saved, Toast.LENGTH_SHORT).show()
                binding.tvTrainedStatus.text = if (m?.isTrained == true)
                    getString(R.string.status_trained) else getString(R.string.status_not_trained)
                db.modelParameterDao().observeForModel(modelId).observe(this@ModelEditorActivity) { paramAdapter.submitList(it) }
            }
        }
    }

    private fun editParam(p: ModelParameter) {
        val dbinding = DialogParameterEditBinding.inflate(layoutInflater)
        dbinding.etParamKey.setText(p.key)
        dbinding.etParamKey.isEnabled = false
        dbinding.etParamValue.setText(p.value)
        dbinding.etParamValue.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        AlertDialog.Builder(this).setTitle(R.string.dialog_parameter_title_edit).setView(dbinding.root)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val v = dbinding.etParamValue.text.toString().trim()
                if (v.isEmpty() || v.toDoubleOrNull() == null) {
                    Toast.makeText(this, R.string.error_number_required, Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) { db.modelParameterDao().update(p.copy(value = v)) }
            }
            .setNegativeButton(R.string.action_cancel, null).show()
    }

    override fun onPause() {
        super.onPause()
        PersistentMediaStorage.backupDatabase(this)
    }
}
