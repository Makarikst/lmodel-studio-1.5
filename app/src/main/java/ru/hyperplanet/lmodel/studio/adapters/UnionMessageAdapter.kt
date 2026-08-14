package ru.hyperplanet.lmodel.studio.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.hyperplanet.lmodel.studio.data.UnionMessage
import ru.hyperplanet.lmodel.studio.databinding.ItemUnionMessageBinding
import ru.hyperplanet.lmodel.studio.util.MarkdownRenderer

class UnionMessageAdapter : RecyclerView.Adapter<UnionMessageAdapter.VH>() {
    private val items = mutableListOf<UnionMessage>()

    fun submitList(list: List<UnionMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemUnionMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    class VH(private val b: ItemUnionMessageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: UnionMessage) {
            val side = if (m.speaker == 0) "A" else "B"
            b.tvSpeaker.text = "[$side] ${m.modelName}"
            b.tvText.text = MarkdownRenderer.render(m.text)
        }
    }
}
