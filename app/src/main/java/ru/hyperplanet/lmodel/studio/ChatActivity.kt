package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.MessageAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.Message
import ru.hyperplanet.lmodel.studio.ml.InferenceEngine

class ChatActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getInstance(this) }
    private var chatId: Long = -1L
    private val adapter = MessageAdapter()
    private var cached: List<Message> = emptyList()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        chatId = intent.getLongExtra(MainActivity.EXTRA_CHAT_ID, -1L)
        if (chatId < 0) { finish(); return }

        val rv = findViewById<RecyclerView>(R.id.rvMessages)
        val et = findViewById<EditText>(R.id.etMessage)
        val btn = findViewById<Button>(R.id.btnSend)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        btn.setOnClickListener { send(et) }

        lifecycleScope.launch {
            val chat = withContext(Dispatchers.IO) { db.chatDao().getById(chatId) }
            if (chat == null) { finish(); return@launch }
            title = chat.name
            Toast.makeText(
                this@ChatActivity,
                "src=${chat.sourceType} model=${chat.modelId} rag=${chat.ragBotId}",
                Toast.LENGTH_LONG
            ).show()
            db.messageDao().observeForChat(chatId).observe(this@ChatActivity) { list ->
                cached = list
                adapter.submitList(list)
                if (list.isNotEmpty()) rv.scrollToPosition(list.size - 1)
            }
        }
    }

    private fun send(et: EditText) {
        val text = et.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        et.setText("")
        val id = chatId
        lifecycleScope.launch {
            val chat = withContext(Dispatchers.IO) { db.chatDao().getById(id) } ?: return@launch
            withContext(Dispatchers.IO) {
                db.messageDao().insert(Message(chatId = id, isUser = true, text = text))
            }
            val history = cached.filter { !it.isUser || it.text != text }.takeLast(12)
                .map { InferenceEngine.HistoryMessage(it.isUser, it.text) }

            val result = if (chat.ragBotId > 0L) {
                val bot = withContext(Dispatchers.IO) { db.ragBotDao().getById(chat.ragBotId) }
                if (bot == null) {
                    InferenceEngine.GenerationResult(
                        "RAG id=${chat.ragBotId} не найден.",
                        "getById null", 0, 0
                    )
                } else {
                    val chunks = parseKnowledge(bot.knowledgeJson)
                    if (chunks.isEmpty()) {
                        InferenceEngine.GenerationResult(
                            "База «${bot.name}» пуста. В редакторе RAG добавь абзацы через пустую строку → Сохранить.",
                            "knowledge empty len=${bot.knowledgeJson?.length ?: 0}", 0, 0
                        )
                    } else {
                        withContext(Dispatchers.Default) {
                            InferenceEngine.generateRagResponse(
                                chunks,
                                chat.systemPrompt.ifBlank { bot.description.orEmpty() },
                                text,
                                chat.lockedLanguage
                            )
                        }
                    }
                }
            } else {
                val model = withContext(Dispatchers.IO) { db.modelDao().getById(chat.modelId) }
                if (model == null) {
                    InferenceEngine.GenerationResult(
                        "Нет модели и нет RAG. Пересоздай чат.",
                        "model=${chat.modelId} rag=${chat.ragBotId} src=${chat.sourceType}", 0, 0
                    )
                } else {
                    val params = withContext(Dispatchers.IO) {
                        db.modelParameterDao().getForModel(model.id).associate { it.key to it.value }
                    }
                    withContext(Dispatchers.Default) {
                        InferenceEngine.generateResponse(
                            model.trainedDataJson, chat.systemPrompt, text, params, history, chat.lockedLanguage
                        )
                    }
                }
            }

            withContext(Dispatchers.IO) {
                db.messageDao().insert(
                    Message(
                        chatId = id, isUser = false, text = result.text,
                        reasoning = result.reasoning,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens
                    )
                )
            }
        }
    }

    private fun parseKnowledge(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        try {
            val type = object : TypeToken<List<String>>() {}.type
            val list: List<String>? = gson.fromJson(json, type)
            if (!list.isNullOrEmpty()) return list.map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) { }
        return json.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}