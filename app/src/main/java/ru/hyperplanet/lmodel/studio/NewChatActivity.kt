package ru.hyperplanet.lmodel.studio

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.ModelSelectAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.Chat
import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.data.RagBotEntity
import ru.hyperplanet.lmodel.studio.databinding.ActivityNewChatBinding
import ru.hyperplanet.lmodel.studio.databinding.DialogSelectModelBinding

class NewChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNewChatBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var selectedModel: ModelEntity? = null
    private var selectedRag: RagBotEntity? = null
    private var useModel = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_new_chat)
        binding.cbAllowAttachments.visibility = View.GONE
        binding.layoutAttachmentTypes.visibility = View.GONE
        binding.switchSource.isChecked = true
        binding.tvSourceLabel.text = getString(R.string.source_model)
        binding.switchSource.setOnCheckedChangeListener { _, checked ->
            useModel = checked
            selectedModel = null; selectedRag = null
            binding.btnSelectModel.text = getString(if (checked) R.string.btn_select_model else R.string.btn_select_rag)
            binding.tvSourceLabel.text = getString(if (checked) R.string.source_model else R.string.source_rag)
        }
        binding.btnSelectModel.setOnClickListener { if (useModel) selectModel() else selectRag() }
        binding.btnCreateChat.setOnClickListener { create() }
    }

    private fun selectModel() {
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) { db.modelDao().getAllOnce() }
            if (models.isEmpty()) { Toast.makeText(this@NewChatActivity, R.string.error_no_models_yet, Toast.LENGTH_LONG).show(); return@launch }
            val dbinding = DialogSelectModelBinding.inflate(layoutInflater)
            val dialog = Dialog(this@NewChatActivity)
            dialog.setContentView(dbinding.root)
            dbinding.rvModelSelect.layoutManager = LinearLayoutManager(this@NewChatActivity)
            dbinding.rvModelSelect.adapter = ModelSelectAdapter(models) {
                selectedModel = it; selectedRag = null
                binding.btnSelectModel.text = it.name; dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun selectRag() {
        lifecycleScope.launch {
            val bots = withContext(Dispatchers.IO) { db.ragBotDao().getAllOnce() }
            if (bots.isEmpty()) { Toast.makeText(this@NewChatActivity, R.string.error_no_rag_yet, Toast.LENGTH_LONG).show(); return@launch }
            AlertDialog.Builder(this@NewChatActivity).setTitle(R.string.btn_select_rag)
                .setItems(bots.map { it.name }.toTypedArray()) { _, w ->
                    selectedRag = bots[w]; selectedModel = null; binding.btnSelectModel.text = bots[w].name
                }.show()
        }
    }

    private fun create() {
        val name = binding.etChatName.text.toString().trim()
        val systemPrompt = binding.etSystemPrompt.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this, R.string.error_name_required, Toast.LENGTH_SHORT).show(); return }
        if (useModel && selectedModel == null) { Toast.makeText(this, R.string.error_model_required, Toast.LENGTH_SHORT).show(); return }
        if (!useModel && selectedRag == null) { Toast.makeText(this, R.string.error_rag_required, Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val id = db.chatDao().insert(Chat(
                name = name, systemPrompt = systemPrompt,
                sourceType = if (useModel) Chat.SOURCE_MODEL else Chat.SOURCE_RAG,
                modelId = selectedModel?.id ?: 0, ragBotId = selectedRag?.id ?: 0
            ))
            withContext(Dispatchers.Main) {
                startActivity(android.content.Intent(this@NewChatActivity, ChatActivity::class.java).putExtra(MainActivity.EXTRA_CHAT_ID, id))
                finish()
            }
        }
    }
}
