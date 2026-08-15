package ru.hyperplanet.lmodel.studio.data

fun ModelDao.update(model: ModelEntity): Int =
    updateFields(
        model.id, model.name, model.supportsText, model.supportsPhoto,
        model.supportsVideo, model.supportsAudio, model.isTrained,
        model.trainedDataJson, model.apiKey, model.createdAt
    )

fun ModelDao.delete(model: ModelEntity): Int = deleteById(model.id)

fun ModelParameterDao.update(param: ModelParameter): Int =
    updateFields(param.id, param.modelId, param.key, param.value)

fun ModelParameterDao.delete(param: ModelParameter): Int = deleteById(param.id)

fun TrainingItemDao.delete(item: TrainingItem): Int = deleteById(item.id)

fun RagBotDao.update(bot: RagBotEntity): Int =
    updateFields(bot.id, bot.name, bot.description, bot.knowledgeJson, bot.createdAt)

fun RagBotDao.delete(bot: RagBotEntity): Int = deleteById(bot.id)

fun ChatDao.update(chat: Chat): Int =
    updateFields(
        chat.id, chat.name, chat.systemPrompt, chat.sourceType,
        chat.modelId, chat.ragBotId, chat.allowAttachments, chat.allowedTypes,
        chat.lockedLanguage, chat.createdAt
    )

fun ChatDao.delete(chat: Chat): Int = deleteById(chat.id)