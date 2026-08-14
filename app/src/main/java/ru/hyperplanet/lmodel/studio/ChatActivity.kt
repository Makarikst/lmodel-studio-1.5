package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.MessageAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.Chat
import ru.hyperplanet.lmodel.studio.data.Message
import ru.hyperplanet.lmodel.studio.databinding.ActivityChatBinding
import ru.hyperplanet.lmodel.studio.ml.LanguageLock
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync

class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var chatId = -1L
    private var chat: Chat? = null
    private lateinit var messageAdapter: MessageAdapter
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        chatId = intent.getLongExtra(MainActivity.EXTRA_CHAT_ID, -1)
        if (chatId == -1L) { finish(); return }
        messageAdapter = MessageAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = messageAdapter
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { db.chatDao().getById(chatId) } ?: run { finish(); return@launch }
            chat = loaded
            title = loaded.name
            db.messageDao().observeForChat(chatId).observe(this@ChatActivity) { list ->
                messageAdapter.submitList(list)
                if (list.isNotEmpty()) binding.rvMessages.scrollToPosition(list.size - 1)
            }
        }
        binding.btnSend.setOnClickListener { send() }
    }

    private fun send() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        val loadedChat = chat ?: return
        binding.etMessage.setText("")
        lifecycleScope.launch(Dispatchers.IO) {
            db.messageDao().insert(Message(chatId = chatId, isUser = true, text = text))
            var lock = loadedChat.lockedLanguage
            val det = LanguageLock.detect(text)
            if (lock == null && det != LanguageLock.Lang.OTHER) {
                lock = LanguageLock.code(det)
                db.chatDao().update(loadedChat.copy(lockedLanguage = lock)).also { chat = loadedChat.copy(lockedLanguage = lock) }
            } else if (lock != null && LanguageLock.isLanguageSwitchAllowed(text)) {
                lock = LanguageLock.code(det)
                db.chatDao().update(loadedChat.copy(lockedLanguage = lock))
                chat = loadedChat.copy(lockedLanguage = lock)
            }
            val recent = db.messageDao().getForChatOnce(chatId).takeLast(12)
            val history = recent.dropLast(1).map { InferenceEngine.HistoryMessage(it.isUser, it.text) }
            if (loadedChat.sourceType != Chat.SOURCE_RAG) {
                ModelTrainingSync.sync(db, loadedChat.modelId)
            }
            val result = if (loadedChat.sourceType == Chat.SOURCE_RAG) {
                val bot = db.ragBotDao().getById(loadedChat.ragBotId)
                val chunks: List<String> = try {
                    gson.fromJson(bot?.knowledgeJson ?: "[]", object : TypeToken<List<String>>() {}.type) ?: emptyList()
                } catch (_: Exception) { emptyList() }
                InferenceEngine.generateRagResponse(chunks, loadedChat.systemPrompt, text, lock)
            } else {
                val model = db.modelDao().getById(loadedChat.modelId)
                val params = model?.let {
                    db.modelParameterDao().getForModel(it.id).associate { p -> p.key.lowercase() to p.value }
                } ?: emptyMap()
                InferenceEngine.generateResponse(model?.trainedDataJson, loadedChat.systemPrompt, text, params, history, lock)
            }
            db.messageDao().insert(Message(chatId = chatId, isUser = false, text = result.text,
                reasoning = result.reasoning, promptTokens = result.promptTokens, completionTokens = result.completionTokens))
        }
    }
}
