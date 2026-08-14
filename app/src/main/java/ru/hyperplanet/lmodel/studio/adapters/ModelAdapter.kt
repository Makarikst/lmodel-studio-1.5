package ru.hyperplanet.lmodel.studio.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.databinding.ItemModelBinding

class ModelAdapter(
    private val onClick: (ModelEntity) -> Unit,
    private val onLongClick: (ModelEntity) -> Unit
) : RecyclerView.Adapter<ModelAdapter.ModelViewHolder>() {

    private val items = mutableListOf<ModelEntity>()

    fun submitList(models: List<ModelEntity>) {
        items.clear()
        items.addAll(models)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val binding = ItemModelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ModelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ModelViewHolder(private val binding: ItemModelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(model: ModelEntity) {
            binding.tvModelName.text = model.name
            binding.tvModelStatus.text = if (model.isTrained) "Обучена" else "Не обучена"

            val supports = mutableListOf<String>()
            if (model.supportsText) supports.add("Текст")
            if (model.supportsPhoto) supports.add("Фото")
            if (model.supportsVideo) supports.add("Видео")
            if (model.supportsAudio) supports.add("Аудио")
            binding.tvModelSupports.text = supports.joinToString(", ")

            binding.root.setOnClickListener { onClick(model) }
            binding.root.setOnLongClickListener {
                onLongClick(model)
                true
            }
        }
    }
}
