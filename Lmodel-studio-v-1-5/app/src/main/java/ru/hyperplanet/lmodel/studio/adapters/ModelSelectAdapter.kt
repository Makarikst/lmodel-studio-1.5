package ru.hyperplanet.lmodel.studio.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.hyperplanet.lmodel.studio.data.ModelEntity
import ru.hyperplanet.lmodel.studio.databinding.ItemModelSelectBinding

class ModelSelectAdapter(
    private val models: List<ModelEntity>,
    private val onSelected: (ModelEntity) -> Unit
) : RecyclerView.Adapter<ModelSelectAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModelSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        holder.binding.tvModelSelectName.text = model.name
        holder.binding.root.setOnClickListener { onSelected(model) }
    }

    override fun getItemCount(): Int = models.size

    class ViewHolder(val binding: ItemModelSelectBinding) : RecyclerView.ViewHolder(binding.root)
}
