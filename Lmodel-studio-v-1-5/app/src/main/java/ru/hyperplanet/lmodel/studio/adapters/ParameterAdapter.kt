package ru.hyperplanet.lmodel.studio.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.hyperplanet.lmodel.studio.data.ModelParameter
import ru.hyperplanet.lmodel.studio.databinding.ItemParameterBinding

class ParameterAdapter(
    private val onEdit: (position: Int, parameter: ModelParameter) -> Unit
) : RecyclerView.Adapter<ParameterAdapter.ParamViewHolder>() {

    private val items = mutableListOf<ModelParameter>()

    fun submitList(parameters: List<ModelParameter>) {
        items.clear()
        items.addAll(parameters)
        notifyDataSetChanged()
    }

    fun currentList(): List<ModelParameter> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParamViewHolder {
        val binding = ItemParameterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ParamViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ParamViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ParamViewHolder(private val binding: ItemParameterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(param: ModelParameter) {
            binding.tvParamKey.text = param.key
            binding.tvParamValue.text = param.value
            binding.btnDeleteParam.visibility = android.view.View.GONE
            binding.btnEditParam.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEdit(pos, items[pos])
            }
        }
    }
}
