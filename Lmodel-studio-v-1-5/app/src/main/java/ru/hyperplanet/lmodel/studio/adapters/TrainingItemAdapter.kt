package ru.hyperplanet.lmodel.studio.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.hyperplanet.lmodel.studio.data.TrainingItem
import ru.hyperplanet.lmodel.studio.databinding.ItemTrainingItemBinding

class TrainingItemAdapter(
    private val onDelete: (TrainingItem) -> Unit
) : RecyclerView.Adapter<TrainingItemAdapter.ViewHolder>() {

    private val items = mutableListOf<TrainingItem>()

    fun submitList(list: List<TrainingItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrainingItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemTrainingItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TrainingItem) {
            binding.tvItemType.text = if (item.type == TrainingItem.TYPE_PROLOG) "PROLOG" else "ТЕКСТ"
            binding.tvItemPreview.text = item.content
            binding.btnDeleteItem.setOnClickListener { onDelete(item) }
        }
    }
}
