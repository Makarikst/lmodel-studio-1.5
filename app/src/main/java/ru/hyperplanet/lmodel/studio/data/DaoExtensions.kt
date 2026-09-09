package ru.hyperplanet.lmodel.studio.data

suspend fun ModelDao.update(model: ModelEntity): Int = updateFields(
    id = model.id,
    name = model.name,
    kind = model.kind,
    supportsText = model.supportsText,
    supportsPhoto = model.supportsPhoto,
    supportsVideo = model.supportsVideo,
    supportsAudio = model.supportsAudio,
    isTrained = model.isTrained,
    trainedDataJson = model.trainedDataJson,
    prologJson = model.prologJson,
    apiKey = model.apiKey,
    createdAt = model.createdAt
)

suspend fun ModelDao.delete(model: ModelEntity): Int = deleteById(model.id)

suspend fun ModelParameterDao.update(param: ModelParameter): Int = updateFields(
    id = param.id,
    modelId = param.modelId,
    key = param.key,
    value = param.value
)

suspend fun ModelParameterDao.delete(param: ModelParameter): Int = deleteById(param.id)

suspend fun TrainingItemDao.delete(item: TrainingItem): Int = deleteById(item.id)

suspend fun ChatDao.update(chat: Chat): Int = updateFields(
    id = chat.id,
    name = chat.name,
    systemPrompt = chat.systemPrompt,
    modelId = chat.modelId,
    allowAttachments = chat.allowAttachments,
    allowedTypes = chat.allowedTypes,
    lockedLanguage = chat.lockedLanguage,
    sourceType = chat.sourceType,
    ragBotId = chat.ragBotId,
    languageSwitchAllowed = chat.languageSwitchAllowed,
    createdAt = chat.createdAt
)

suspend fun ChatDao.delete(chat: Chat): Int = deleteById(chat.id)

suspend fun MessageDao.delete(message: Message): Int = deleteById(message.id)

suspend fun RagBotDao.update(bot: RagBotEntity): Int = updateFields(
    id = bot.id,
    name = bot.name,
    description = bot.description,
    knowledgeJson = bot.knowledgeJson
)

suspend fun RagBotDao.delete(bot: RagBotEntity): Int = deleteById(bot.id)

suspend fun ModelUnionDao.delete(union: ModelUnion): Int = deleteById(union.id)

suspend fun UnionMessageDao.delete(msg: UnionMessage): Int = deleteById(msg.id)