package ru.hyperplanet.lmodel.studio

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.RagBotEntity
import ru.hyperplanet.lmodel.studio.databinding.ActivityRagBotListBinding

class RagBotListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRagBotListBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var bots: List<RagBotEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRagBotListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_rag_list)
        binding.btnCreateRag.setOnClickListener { startActivity(Intent(this, RagBotEditorActivity::class.java)) }
        db.ragBotDao().observeAll().observe(this) { list ->
            bots = list
            binding.listRag.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list.map { "${it.name}\n${it.description}" })
        }
        binding.listRag.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, RagBotEditorActivity::class.java).putExtra(MainActivity.EXTRA_RAG_BOT_ID, bots[pos].id))
        }
        binding.listRag.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this).setMessage(R.string.dialog_delete_message)
                .setPositiveButton(R.string.action_delete) { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.ragBotDao().delete(bots[pos]) } }
                .setNegativeButton(R.string.action_cancel, null).show()
            true
        }
    }
}
