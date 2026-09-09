package ru.hyperplanet.lmodel.studio.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.hyperplanet.lmodel.studio.data.Chat
import ru.hyperplanet.lmodel.studio.databinding.ItemChatBinding

class ChatAdapter(
    private val onClick: (Chat) -> Unit,
    private val onLongClick: (Chat) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val items = mutableListOf<Chat>()
    private var modelNames: Map<Long, String> = emptyMap()

    fun submitList(chats: List<Chat>, modelNamesById: Map<Long, String>) {
        items.clear()
        items.addAll(chats)
        modelNames = modelNamesById
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = items[position]
        holder.bind(chat, modelNames[chat.modelId] ?: "—")
    }

    override fun getItemCount(): Int = items.size

    inner class ChatViewHolder(private val binding: ItemChatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: Chat, modelName: String) {
            binding.tvChatName.text = chat.name
            binding.tvChatSubtitle.text = "Модель: $modelName"
            binding.root.setOnClickListener { onClick(chat) }
            binding.root.setOnLongClickListener {
                onLongClick(chat)
                true
            }
        }
    }
}
