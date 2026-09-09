package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hyperplanet.lmodel.studio.data.AppDatabase.*
import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.data.ModelUnion
import ru.hyperplanet.lmodel.studio.databinding.ActivityNewUnionBinding
import ru.hyperplanet.lmodel.studio.data.AppDatabase

class NewUnionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNewUnionBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var models: List<ModelEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewUnionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_new_union)

        lifecycleScope.launch {
            models = withContext(Dispatchers.IO) { db.modelDao().getAllOnce() }
            if (models.isEmpty()) {
                Toast.makeText(this@NewUnionActivity, R.string.error_no_models_yet, Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            val names = models.map { it.name }
            val adapter = ArrayAdapter(this@NewUnionActivity, android.R.layout.simple_spinner_dropdown_item, names)
            binding.spinnerModelA.adapter = adapter
            binding.spinnerModelB.adapter = adapter
        }

        binding.btnCreateUnion.setOnClickListener {
            val name = binding.etUnionName.text.toString().trim()
            val topic = binding.etTopic.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.error_name_required, Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (models.isEmpty()) return@setOnClickListener
            val a = models[binding.spinnerModelA.selectedItemPosition.coerceIn(0, models.lastIndex)]
            val b = models[binding.spinnerModelB.selectedItemPosition.coerceIn(0, models.lastIndex)]
            lifecycleScope.launch(Dispatchers.IO) {
                val id = db.modelUnionDao().insert(
                    ModelUnion(name = name, modelIdA = a.id, modelIdB = b.id, topic = topic)
                )
                withContext(Dispatchers.Main) {
                    startActivity(
                        android.content.Intent(this@NewUnionActivity, UnionChatActivity::class.java)
                            .putExtra(MainActivity.EXTRA_UNION_ID, id)
                    )
                    finish()
                }
            }
        }
    }
}
