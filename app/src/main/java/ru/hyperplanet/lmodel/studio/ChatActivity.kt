package ru.hyperplanet.lmodel.studio

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import ru.hyperplanet.lmodel.studio.ml.InferenceEngine
import ru.hyperplanet.lmodel.studio.ml.LanguageLock
import ru.hyperplanet.lmodel.studio.ml.ModelTrainingSync
import ru.hyperplanet.lmodel.studio.util.CodingLangStore
import ru.hyperplanet.lmodel.studio.util.MediaFileAnalyzer

class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var chatId = -1L
    private var chat: Chat? = null
    private lateinit var messageAdapter: MessageAdapter
    private val gson = Gson()

    private var pendingPath: String? = null
    private var pendingAnalysis: String? = null
    private var pendingName: String? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onAttach(uri)
    }

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
            // Вложения всегда доступны
            binding.btnAttach.visibility = android.view.View.VISIBLE
            db.messageDao().observeForChat(chatId).observe(this@ChatActivity) { list ->
                messageAdapter.submitList(list)
                if (list.isNotEmpty()) binding.rvMessages.scrollToPosition(list.size - 1)
            }
        }
        binding.btnSend.setOnClickListener { send() }
        binding.btnAttach.setOnClickListener {
            openDoc.launch(arrayOf("*/*"))
        }
    }

    private fun onAttach(uri: Uri) {
        lifecycleScope.launch {
            try {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { }
                val result = withContext(Dispatchers.IO) {
                    MediaFileAnalyzer.ingest(this@ChatActivity, uri, subdir = "chat_media")
                }
                pendingPath = result.savedFile.absolutePath
                pendingAnalysis = result.analysis
                pendingName = result.displayName
                val extra = result.extractedText?.take(500)
                Toast.makeText(
                    this@ChatActivity,
                    "Прикреплено: ${result.displayName}. Можно дописать сообщение и отправить.",
                    Toast.LENGTH_LONG
                ).show()
                if (binding.etMessage.text.isNullOrBlank() && !extra.isNullOrBlank()) {
                    // не подставляем весь текст — только пометка
                }
                binding.etMessage.hint = "Файл: ${result.displayName}"
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, e.message ?: "Ошибка", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun send() {
        val text = binding.etMessage.text.toString().trim()
        val path = pendingPath
        val analysis = pendingAnalysis
        if (text.isEmpty() && path == null) return
        val loadedChat = chat ?: return
        binding.etMessage.setText("")
        binding.etMessage.hint = getString(R.string.hint_message)
        val attachPath = path
        val attachAnalysis = analysis
        val attachName = pendingName
        pendingPath = null
        pendingAnalysis = null
        pendingName = null

        lifecycleScope.launch(Dispatchers.IO) {
            val userVisible = buildString {
                if (attachName != null) appendLine("[Файл: $attachName]")
                if (attachAnalysis != null) appendLine(attachAnalysis)
                if (text.isNotEmpty()) append(text)
            }.trim()
            db.messageDao().insert(
                Message(
                    chatId = chatId,
                    isUser = true,
                    text = userVisible,
                    attachmentPath = attachPath
                )
            )
            val history = db.messageDao().getForChatOnce(chatId)
                .map { InferenceEngine.HistoryMessage(it.isUser, it.text) }

            // Для модели: вопрос + анализ вложения
            val queryForModel = buildString {
                if (attachAnalysis != null) {
                    appendLine("Пользователь прикрепил файл.")
                    appendLine(attachAnalysis)
                }
                if (text.isNotEmpty()) append(text)
                else append("Опиши или учти прикреплённый файл.")
            }.trim()

            val result = if (loadedChat.sourceType == Chat.SOURCE_RAG) {
                val rag = db.ragBotDao().getById(loadedChat.ragBotId)
                val chunks = try {
                    gson.fromJson<List<String>>(rag?.knowledgeJson ?: "[]", object : TypeToken<List<String>>() {}.type)
                } catch (_: Exception) { emptyList() }
                InferenceEngine.generateRagResponse(chunks, loadedChat.systemPrompt, queryForModel, loadedChat.lockedLanguage)
            } else {
                ModelTrainingSync.sync(db, loadedChat.modelId)
                val model = db.modelDao().getById(loadedChat.modelId)
                val params = db.modelParameterDao().getForModel(loadedChat.modelId)
                    .associate { it.key to it.value }
                val lock = loadedChat.lockedLanguage
                    ?: LanguageLock.detect(queryForModel).takeIf { it != LanguageLock.Lang.OTHER }?.name
                if (lock != null && loadedChat.lockedLanguage == null) {
                    db.chatDao().updateFields(
                        loadedChat.id, loadedChat.name, loadedChat.systemPrompt, loadedChat.sourceType,
                        loadedChat.modelId, loadedChat.ragBotId, true, loadedChat.allowedTypes,
                        lock, loadedChat.createdAt
                    )
                }
                InferenceEngine.generateResponse(
                    model?.trainedDataJson,
                    loadedChat.systemPrompt,
                    queryForModel,
                    params,
                    history,
                    lock ?: loadedChat.lockedLanguage,
                    CodingLangStore.get(this@ChatActivity, loadedChat.modelId).map { it.names }
                )
            }
            db.messageDao().insert(
                Message(
                    chatId = chatId,
                    isUser = false,
                    text = result.text,
                    reasoning = result.reasoning,
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens
                )
            )
        }
    }
}
