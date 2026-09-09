package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.RagBotEntity
import ru.hyperplanet.lmodel.studio.util.PersistentMediaStorage
import ru.hyperplanet.lmodel.studio.databinding.ActivityRagBotEditorBinding

class RagBotEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRagBotEditorBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private val gson = Gson()
    private var botId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRagBotEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_rag_editor)
        botId = intent.getLongExtra(MainActivity.EXTRA_RAG_BOT_ID, -1)
        if (botId != -1L) lifecycleScope.launch {
            val bot = withContext(Dispatchers.IO) { db.ragBotDao().getById(botId) } ?: return@launch
            binding.etName.setText(bot.name)
            binding.etDescription.setText(bot.description)
            val chunks = try { gson.fromJson(bot.knowledgeJson, Array<String>::class.java)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
            binding.etKnowledge.setText(chunks.joinToString("\n\n"))
        }
        binding.btnSaveRag.setOnClickListener { save() }
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this, R.string.error_name_required, Toast.LENGTH_SHORT).show(); return }
        val chunks = binding.etKnowledge.text.toString().split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val json = gson.toJson(chunks)
        val desc = binding.etDescription.text.toString().trim()
        lifecycleScope.launch(Dispatchers.IO) {
            if (botId == -1L) db.ragBotDao().insert(RagBotEntity(name = name, description = desc, knowledgeJson = json))
            else {
                val old = db.ragBotDao().getById(botId) ?: return@launch
                db.ragBotDao().update(old.copy(name = name, description = desc, knowledgeJson = json))
            }
            withContext(Dispatchers.Main) { Toast.makeText(this@RagBotEditorActivity, R.string.rag_saved, Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    override fun onPause() {
        super.onPause()
        PersistentMediaStorage.backupDatabase(this)
    }
}
