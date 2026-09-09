package ru.hyperplanet.lmodel.studio

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.hyperplanet.lmodel.studio.adapters.ChatAdapter
import ru.hyperplanet.lmodel.studio.adapters.ModelAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.Chat
import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.data.ModelUnion
import ru.hyperplanet.lmodel.studio.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var modelAdapter: ModelAdapter

    /** Имена моделей для подписи в списке чатов */
    private var modelNamesById: Map<Long, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatAdapter = ChatAdapter(
            onClick = { chat ->
                startActivity(
                    Intent(this, ChatActivity::class.java)
                        .putExtra(EXTRA_CHAT_ID, chat.id)
                )
            },
            onLongClick = { chat -> confirmDeleteChat(chat) }
        )
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = chatAdapter

        modelAdapter = ModelAdapter(
            onClick = { model ->
                startActivity(
                    Intent(this, ModelEditorActivity::class.java)
                        .putExtra(EXTRA_MODEL_ID, model.id)
                        .putExtra(EXTRA_MODEL_KIND, model.kind)
                )
            },
            onLongClick = { model -> confirmDeleteModel(model) }
        )
        binding.rvModels.layoutManager = LinearLayoutManager(this)
        binding.rvModels.adapter = modelAdapter

        // id из activity_main.xml
        binding.btnNewChat.setOnClickListener {
            startActivity(Intent(this, NewChatActivity::class.java))
        }
        binding.btnNewModel.setOnClickListener {
            startActivity(
                Intent(this, ModelEditorActivity::class.java)
                    .putExtra(EXTRA_MODEL_KIND, ModelEntity.KIND_MODEL)
            )
        }
        binding.btnNewRag.setOnClickListener {
            startActivity(Intent(this, RagBotEditorActivity::class.java))
        }
        binding.btnOpenRagList.setOnClickListener {
            startActivity(Intent(this, RagBotListActivity::class.java))
        }
        binding.btnNewUnion.setOnClickListener {
            startActivity(Intent(this, NewUnionActivity::class.java))
        }

        var lastChats: List<Chat> = emptyList()

        db.modelDao().observeAll().observe(this) { models: List<ModelEntity>? ->
            val list = models ?: emptyList()
            modelNamesById = list.associate { m -> m.id to m.name }
            modelAdapter.submitList(list)
            chatAdapter.submitList(lastChats, modelNamesById)
        }

        db.chatDao().observeAll().observe(this) { chats: List<Chat>? ->
            lastChats = chats ?: emptyList()
            chatAdapter.submitList(lastChats, modelNamesById)
        }

        db.ragBotDao().observeAll().observe(this) { bots ->
            val n = bots?.size ?: 0
            binding.tvRagCount.text = getString(R.string.rag_bots_count, n)
        }

        // Список объединений (простой текстовый refresh через count в subtitle при необходимости)
        db.modelUnionDao().observeAll().observe(this) { unions: List<ModelUnion>? ->
            // rvUnions без отдельного адаптера в проекте — можно оставить пустым
            // или показать Toast при длинном тапе позже
        }
    }

    private fun confirmDeleteChat(chat: Chat) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_chat_title)
            .setMessage(R.string.dialog_delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.chatDao().deleteById(chat.id)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDeleteModel(model: ModelEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_model_title)
            .setMessage(R.string.dialog_delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.modelDao().deleteById(model.id)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_KIND = "model_kind"
        const val EXTRA_RAG_BOT_ID = "rag_bot_id"
        const val EXTRA_UNION_ID = "union_id"
    }
}