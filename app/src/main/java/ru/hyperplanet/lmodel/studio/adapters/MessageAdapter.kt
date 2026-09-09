package ru.hyperplanet.lmodel.studio.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.hyperplanet.lmodel.studio.data.Message
import ru.hyperplanet.lmodel.studio.databinding.ItemMessageBotBinding
import ru.hyperplanet.lmodel.studio.databinding.ItemMessageUserBinding
import ru.hyperplanet.lmodel.studio.util.MarkdownRenderer

private const val TYPE_USER = 0
private const val TYPE_BOT = 1

class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Message>()
    private val expanded = mutableSetOf<String>()

    private fun keyOf(message: Message, position: Int): String {
        return if (message.id > 0) "id_${message.id}" else "p${position}_${message.createdAt}_${message.isUser}"
    }

    fun submitList(messages: List<Message>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].isUser) TYPE_USER else TYPE_BOT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserViewHolder(ItemMessageUserBinding.inflate(inflater, parent, false))
        } else {
            BotViewHolder(ItemMessageBotBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder -> holder.bind(items[position])
            is BotViewHolder -> holder.bind(items[position], position)
        }
    }

    override fun getItemCount(): Int = items.size

    class UserViewHolder(private val binding: ItemMessageUserBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.tvMessageText.text = message.text
            binding.tvAttachment.visibility = View.GONE
        }
    }

    inner class BotViewHolder(private val binding: ItemMessageBotBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message, position: Int) {
            // Markdown → визуальное оформление (жирный, не «**текст**»)
            binding.tvMessageText.text = MarkdownRenderer.render(message.text)
            val reasoning = message.reasoning?.trim().orEmpty()
            val hasReasoning = reasoning.isNotEmpty()
            val key = keyOf(message, position)
            binding.btnShowReasoning.visibility = if (hasReasoning) View.VISIBLE else View.GONE
            val isExp = key in expanded
            binding.tvReasoning.visibility = if (hasReasoning && isExp) View.VISIBLE else View.GONE
            if (hasReasoning && isExp) {
                binding.tvReasoning.text = reasoning
            }
            binding.btnShowReasoning.text = if (isExp) "Скрыть рассуждение" else "Рассуждение"
            binding.btnShowReasoning.setOnClickListener {
                if (key in expanded) expanded.remove(key) else expanded.add(key)
                notifyItemChanged(position)
            }
        }
    }
}
