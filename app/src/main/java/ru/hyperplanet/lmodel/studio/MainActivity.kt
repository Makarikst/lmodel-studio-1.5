package ru.hyperplanet.lmodel.studio

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.adapters.ChatAdapter
import ru.hyperplanet.lmodel.studio.adapters.ModelAdapter
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.Chat
import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.data.delete
import ru.hyperplanet.lmodel.studio.databinding.ActivityMainBinding
import ru.hyperplanet.lmodel.studio.util.PersistentMediaStorage

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_RAG_BOT_ID = "rag_bot_id"
        const val EXTRA_UNION_ID = "union_id"
    }
    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.app_name)

        val modelAdapter = ModelAdapter(
            onClick = { startActivity(Intent(this, ModelEditorActivity::class.java).putExtra(EXTRA_MODEL_ID, it.id)) },
            onLongClick = { m -> AlertDialog.Builder(this).setTitle(R.string.dialog_delete_model_title)
                .setMessage(R.string.dialog_delete_message)
                .setPositiveButton(R.string.action_delete) { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.modelDao().delete(m) } }
                .setNegativeButton(R.string.action_cancel, null).show() }
        )
        binding.rvModels.layoutManager = LinearLayoutManager(this)
        binding.rvModels.adapter = modelAdapter
        db.modelDao().observeAll().observe(this) { modelAdapter.submitList(it) }

        val chatAdapter = ChatAdapter(
            onClick = { startActivity(Intent(this, ChatActivity::class.java).putExtra(EXTRA_CHAT_ID, it.id)) },
            onLongClick = { c -> AlertDialog.Builder(this).setTitle(R.string.dialog_delete_chat_title)
                .setMessage(R.string.dialog_delete_message)
                .setPositiveButton(R.string.action_delete) { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.chatDao().delete(c) } }
                .setNegativeButton(R.string.action_cancel, null).show() }
        )
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = chatAdapter
        db.chatDao().observeAll().observe(this) { chats ->
            lifecycleScope.launch {
                val models = withContext(Dispatchers.IO) { db.modelDao().getAllOnce() }
                val names = models.associate { it.id to it.name }
                chatAdapter.submitList(chats, names)
            }
        }
        db.ragBotDao().observeAll().observe(this) { binding.tvRagCount.text = getString(R.string.rag_bots_count, it.size) }

        binding.btnNewModel.setOnClickListener { startActivity(Intent(this, ModelEditorActivity::class.java)) }
        binding.btnNewChat.setOnClickListener { startActivity(Intent(this, NewChatActivity::class.java)) }
        binding.btnNewRag.setOnClickListener { startActivity(Intent(this, RagBotEditorActivity::class.java)) }
        binding.btnNewUnion.setOnClickListener {
            startActivity(Intent(this, NewUnionActivity::class.java))
        }
        db.modelUnionDao().observeAll().observe(this) { list ->
            // simple text rows in recycler via ChatAdapter-like: use map to open
            binding.rvUnions.layoutManager = LinearLayoutManager(this)
            binding.rvUnions.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                override fun getItemCount() = list.size
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                    val tv = android.widget.TextView(parent.context)
                    tv.setPadding(24, 28, 24, 28)
                    tv.setTextColor(0xFFFFFFFF.toInt())
                    tv.textSize = 15f
                    return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv) {}
                }
                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    val u = list[position]
                    (holder.itemView as android.widget.TextView).text = "${u.name}  ·  Beta"
                    holder.itemView.setOnClickListener {
                        startActivity(Intent(this@MainActivity, UnionChatActivity::class.java).putExtra(EXTRA_UNION_ID, u.id))
                    }
                    holder.itemView.setOnLongClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.dialog_delete_message)
                            .setPositiveButton(R.string.action_delete) { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) { db.modelUnionDao().deleteById(u.id) }
                            }
                            .setNegativeButton(R.string.action_cancel, null).show()
                        true
                    }
                }
            }
        }
        binding.btnOpenRagList.setOnClickListener { startActivity(Intent(this, RagBotListActivity::class.java)) }
    }

    override fun onPause() {
        super.onPause()
        PersistentMediaStorage.backupDatabase(this)
    }

    override fun onResume() {
        super.onResume()
        PersistentMediaStorage.ensureReady(this)
    }
}
