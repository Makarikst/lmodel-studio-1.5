package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.UnionMessageAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.ModelUnion
import ru.hyperplanet.lmodel.studio.data.UnionMessage
import ru.hyperplanet.lmodel.studio.databinding.ActivityUnionChatBinding
import ru.hyperplanet.lmodel.studio.ml.InferenceEngine
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync

class UnionChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUnionChatBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var unionId = -1L
    private var union: ModelUnion? = null
    private lateinit var adapter: UnionMessageAdapter
    private var runJob: Job? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnionChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        unionId = intent.getLongExtra(MainActivity.EXTRA_UNION_ID, -1)
        if (unionId == -1L) { finish(); return }

        adapter = UnionMessageAdapter()
        binding.rvUnionMessages.layoutManager = LinearLayoutManager(this)
        binding.rvUnionMessages.adapter = adapter

        lifecycleScope.launch {
            union = withContext(Dispatchers.IO) { db.modelUnionDao().getById(unionId) }
            val u = union ?: run { finish(); return@launch }
            title = "${u.name} · Beta"
            binding.tvUnionSubtitle.text = buildString {
                append("Beta · ")
                if (u.topic.isNotBlank()) append("Тема: ${u.topic}")
                else append("Диалог двух моделей")
            }
            db.unionMessageDao().observeForUnion(unionId).observe(this@UnionChatActivity) { list ->
                adapter.submitList(list)
                if (list.isNotEmpty()) binding.rvUnionMessages.scrollToPosition(list.size - 1)
            }
        }

        binding.btnStartStop.setOnClickListener {
            if (running) stopRun() else startRun()
        }
    }

    override fun onDestroy() {
        stopRun()
        super.onDestroy()
    }

    private fun stopRun() {
        running = false
        runJob?.cancel()
        runJob = null
        binding.btnStartStop.text = getString(R.string.btn_union_start)
    }

    private fun startRun() {
        val u = union ?: return
        running = true
        binding.btnStartStop.text = getString(R.string.btn_union_stop)

        runJob = lifecycleScope.launch {
            val modelA = withContext(Dispatchers.IO) {
                ModelTrainingSync.sync(db, u.modelIdA)
                db.modelDao().getById(u.modelIdA)
            }
            val modelB = withContext(Dispatchers.IO) {
                ModelTrainingSync.sync(db, u.modelIdB)
                db.modelDao().getById(u.modelIdB)
            }
            if (modelA == null || modelB == null) {
                Toast.makeText(this@UnionChatActivity, "Модели не найдены", Toast.LENGTH_LONG).show()
                stopRun(); return@launch
            }
            if (!modelA.isTrained || !modelB.isTrained) {
                Toast.makeText(this@UnionChatActivity, "Обе модели должны быть обучены", Toast.LENGTH_LONG).show()
                stopRun(); return@launch
            }

            val paramsA = withContext(Dispatchers.IO) {
                db.modelParameterDao().getForModel(modelA.id).associate { it.key.lowercase() to it.value }
            }
            val paramsB = withContext(Dispatchers.IO) {
                db.modelParameterDao().getForModel(modelB.id).associate { it.key.lowercase() to it.value }
            }

            // seed: topic or greeting
            var lastText = u.topic.ifBlank { "Привет! Давай обсудим что-нибудь интересное." }
            var speaker = 0 // A first
            val maxTurns = 20

            for (turn in 0 until maxTurns) {
                if (!isActive || !running) break

                val model = if (speaker == 0) modelA else modelB
                val params = if (speaker == 0) paramsA else paramsB
                val name = model.name
                val rolePrompt = if (speaker == 0)
                    "Ты участник A («${modelA.name}») в диалоге с другой моделью. Отвечай коротко по делу."
                else
                    "Ты участник B («${modelB.name}») в диалоге с другой моделью. Отвечай коротко по делу."

                val history = withContext(Dispatchers.IO) {
                    db.unionMessageDao().getForUnionOnce(unionId).takeLast(10).map {
                        // for engine: alternate as user/assistant relative to current speaker
                        InferenceEngine.HistoryMessage(
                            isUser = it.speaker != speaker,
                            text = it.text
                        )
                    }
                }

                val result = withContext(Dispatchers.Default) {
                    InferenceEngine.generateResponse(
                        trainedDataJson = model.trainedDataJson,
                        systemPrompt = rolePrompt,
                        userMessage = lastText,
                        parameters = params,
                        history = history
                    )
                }

                if (!isActive || !running) break

                withContext(Dispatchers.IO) {
                    db.unionMessageDao().insert(
                        UnionMessage(
                            unionId = unionId,
                            speaker = speaker,
                            modelId = model.id,
                            modelName = name,
                            text = result.text,
                            reasoning = result.reasoning
                        )
                    )
                }
                lastText = result.text
                speaker = 1 - speaker
                delay(600)
            }
            stopRun()
        }
    }
}
