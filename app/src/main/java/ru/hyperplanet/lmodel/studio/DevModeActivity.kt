package ru.hyperplanet.lmodel.studio

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.databinding.ActivityDevModeBinding
import ru.hyperplanet.lmodel.studio.ml.LmsExporter
import ru.hyperplanet.lmodel.studio.ml.LocalApiServer
import ru.hyperplanet.lmodel.studio.ml.ModelExporter
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.ml.ModelRemoteSync
import java.io.File

class DevModeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDevModeBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var modelId = -1L
    private var localApi: LocalApiServer? = null
    private var probeTapCount = 0
    private var lastProbeTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityDevModeBinding.inflate(layoutInflater)
            setContentView(binding.root)
            title = getString(R.string.title_dev_mode)
            modelId = intent.getLongExtra(MainActivity.EXTRA_MODEL_ID, -1)
            if (modelId == -1L) { finish(); return }

            binding.btnExportOnnx.setOnClickListener { exportAll() }
            binding.btnGenerateApiKey.setOnClickListener {
                binding.etApiKey.setText(LocalApiServer.generateApiKey())
            }
            binding.btnSaveApiKey.setOnClickListener { saveApiKey() }
            binding.btnStartApi.setOnClickListener { toggleLocalApi() }
            binding.btnDeployRemoteApi.setOnClickListener { deployRemoteApi() }
            binding.btnProbe.setOnClickListener { onProbe() }
            binding.btnCopyClient.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("api", binding.tvApiClient.text?.toString().orEmpty())
                )
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            refreshModelInfo()
        } catch (e: Exception) {
            Toast.makeText(this, "DevMode: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (modelId != -1L) refreshModelInfo()
    }

    override fun onDestroy() {
        try { localApi?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun refreshModelInfo() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { ModelTrainingSync.sync(db, modelId) }
            val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: run { finish(); return@launch }
            if (!m.apiKey.isNullOrBlank() && binding.etApiKey.text.isNullOrBlank()) {
                binding.etApiKey.setText(m.apiKey)
            }
            binding.tvModelInfo.text = buildString {
                appendLine("Модель: ${m.name}")
                appendLine("ID: ${m.id}")
                appendLine("Обучена: ${if (m.isTrained && status.isTrained) "да" else "нет"}")
                appendLine("Текстов: ${status.textCount}, Prolog: ${status.prologCount}")
                appendLine("Предложений: ${status.sentenceCount}")
                appendLine("Словарь: ${status.vocabSize}")
                appendLine("API-ключ: ${if (m.apiKey.isNullOrBlank()) "не задан" else "сохранён"}")
            }
        }
    }

    private fun currentApiKey(): String = binding.etApiKey.text?.toString()?.trim().orEmpty()

    private fun saveApiKey() {
        val key = currentApiKey()
        if (key.isEmpty()) {
            Toast.makeText(this, "Введите или сгенерируйте API-ключ", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val m = db.modelDao().getById(modelId) ?: return@launch
            db.modelDao().update(m.copy(apiKey = key))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DevModeActivity, "API-ключ сохранён", Toast.LENGTH_SHORT).show()
                refreshModelInfo()
            }
        }
    }

    private fun ensureKey(): String {
        var key = currentApiKey()
        if (key.isEmpty()) {
            key = LocalApiServer.generateApiKey()
            binding.etApiKey.setText(key)
        }
        return key
    }

    /** Локальный API — 127.0.0.1, только на телефоне */
    private fun toggleLocalApi() {
        lifecycleScope.launch {
            try {
                if (localApi != null) {
                    localApi?.stop()
                    localApi = null
                    binding.tvApiStatus.text = getString(R.string.api_stopped)
                    binding.btnStartApi.text = getString(R.string.btn_start_local_api)
                    return@launch
                }
                val key = ensureKey()
                withContext(Dispatchers.IO) {
                    val m = db.modelDao().getById(modelId) ?: return@withContext
                    db.modelDao().update(m.copy(apiKey = key))
                    ModelTrainingSync.sync(db, modelId)
                }
                val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: return@launch
                if (!m.isTrained) {
                    Toast.makeText(this@DevModeActivity, R.string.error_not_trained, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val server = LocalApiServer(this@DevModeActivity, modelId, apiKey = key, port = 8765)
                if (!withContext(Dispatchers.IO) { server.start() }) {
                    Toast.makeText(this@DevModeActivity, R.string.api_start_fail, Toast.LENGTH_LONG).show()
                    return@launch
                }
                localApi = server
                binding.tvApiStatus.text = "Локальный API: ${server.baseUrl}"
                binding.btnStartApi.text = getString(R.string.btn_stop_local_api)
                binding.tvApiClient.text = buildString {
                    appendLine("# Локальный API (только на этом устройстве)")
                    appendLine("curl -X POST ${server.baseUrl}/v1/chat/completions \\")
                    appendLine("  -H \"Content-Type: application/json\" \\")
                    appendLine("  -H \"Authorization: Bearer $key\" \\")
                    appendLine("  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"Привет\"}]}'")
                }
            } catch (e: Exception) {
                Toast.makeText(this@DevModeActivity, e.message ?: "API", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Удалённый API 24/7: ZIP с Python-сервером для VPS/Docker.
     * Телефон не может держать глобальный API круглосуточно — пакет деплоится на сервер.
     */
    private fun deployRemoteApi() {
        lifecycleScope.launch {
            try {
                val key = ensureKey()
                withContext(Dispatchers.IO) {
                    val m = db.modelDao().getById(modelId) ?: return@withContext
                    db.modelDao().update(m.copy(apiKey = key))
                    ModelTrainingSync.sync(db, modelId)
                }
                val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: return@launch
                if (!m.isTrained || m.trainedDataJson.isNullOrBlank()) {
                    Toast.makeText(this@DevModeActivity, R.string.error_not_trained, Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(this@DevModeActivity, "Сборка пакета для этой модели…", Toast.LENGTH_SHORT).show()
                val pkg = withContext(Dispatchers.IO) {
                    ModelRemoteSync.buildForModel(this@DevModeActivity, modelId)
                }
                if (pkg.error != null) {
                    binding.tvApiStatus.text = "Ошибка: ${pkg.error}"
                    Toast.makeText(this@DevModeActivity, pkg.error, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val zipPath = pkg.zipFile?.absolutePath ?: "(zip)"
                val keyNow = withContext(Dispatchers.IO) { db.modelDao().getById(modelId)?.apiKey } ?: key
                binding.tvApiStatus.text = buildString {
                    appendLine("Пакет модели #${pkg.modelId} готов (авто model.json)")
                    appendLine("Папка: ${pkg.dir.absolutePath}")
                    appendLine("model.json: ${pkg.modelJson.absolutePath}")
                    appendLine("ZIP: $zipPath")
                }
                binding.tvApiClient.text = buildString {
                    appendLine("# Эта модель → свой model.json (уже внутри ZIP)")
                    appendLine("# PythonAnywhere:")
                    appendLine("# 1) Залей весь ZIP или папку remote_api/model_${pkg.modelId}/")
                    appendLine("# 2) Web App → wsgi.py из пакета")
                    appendLine("# 3) Reload")
                    appendLine()
                    appendLine("curl https://USERNAME.pythonanywhere.com/v1/health")
                    appendLine()
                    appendLine("curl -X POST https://USERNAME.pythonanywhere.com/v1/chat/completions \\")
                    appendLine("  -H \"Authorization: Bearer $keyNow\" \\")
                    appendLine("  -H \"Content-Type: application/json\" \\")
                    appendLine("  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"Привет\"}]}'")
                }
                Toast.makeText(this@DevModeActivity, "model.json этой модели в пакете", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@DevModeActivity, e.message ?: "Remote", Toast.LENGTH_LONG).show()
            }
        }
    }

private fun onProbe() {
        val now = SystemClock.uptimeMillis()
        if (now - lastProbeTap > 900) probeTapCount = 0
        lastProbeTap = now
        probeTapCount++
        if (probeTapCount >= 3) {
            probeTapCount = 0
            startActivity(Intent(this, SecretEasterActivity::class.java))
            return
        }
        lifecycleScope.launch {
            try {
                val q = binding.etProbe.text?.toString()?.trim().orEmpty()
                if (q.isEmpty()) return@launch
                withContext(Dispatchers.IO) { ModelTrainingSync.sync(db, modelId) }
                val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: return@launch
                if (!m.isTrained || m.trainedDataJson.isNullOrBlank()) {
                    binding.tvProbeAnswer.text = "Модель не обучена."
                    return@launch
                }
                val params = withContext(Dispatchers.IO) {
                    db.modelParameterDao().getForModel(modelId).associate { it.key.lowercase() to it.value }
                }
                val r = withContext(Dispatchers.Default) {
                    InferenceEngine.generateResponse(m.trainedDataJson, "", q, params)
                }
                binding.tvProbeAnswer.text = r.text
                binding.tvProbeReasoning.text = r.reasoning
            } catch (e: Exception) {
                binding.tvProbeAnswer.text = e.message
            }
        }
    }

    private fun exportAll() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ModelTrainingSync.sync(db, modelId) }
                val m = withContext(Dispatchers.IO) { db.modelDao().getById(modelId) } ?: return@launch
                if (!m.isTrained) {
                    Toast.makeText(this@DevModeActivity, R.string.error_not_trained, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val dir = File(getExternalFilesDir(null) ?: filesDir, "exports").apply { mkdirs() }
                val exp = withContext(Dispatchers.IO) { ModelExporter.export(m.trainedDataJson, dir, "model_${m.id}") }
                val params = withContext(Dispatchers.IO) { db.modelParameterDao().getForModel(modelId) }
                val lms = withContext(Dispatchers.IO) { LmsExporter.export(m, params, dir, "model_${m.id}_${m.name}") }
                binding.tvExportStatus.text = buildString {
                    appendLine(if (exp.error != null) "ONNX: ${exp.error}" else "ONNX/TFLite OK")
                    appendLine(if (lms.error != null) "LMS: ${lms.error}" else "LMS: ${lms.file?.absolutePath}")
                }
            } catch (e: Exception) {
                binding.tvExportStatus.text = e.message
            }
        }
    }
}
