package ru.hyperplanet.lmodel.studio.ml

import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.TrainingItem
import ru.hyperplanet.lmodel.studio.data.update

/**
 * Синхронизация статуса «обучена» с реальными данными обучения.
 * Нет текстов и Prolog → isTrained=false, trainedDataJson=null.
 */
object ModelTrainingSync {

    data class Status(
        val isTrained: Boolean,
        val textCount: Int,
        val prologCount: Int,
        val sentenceCount: Int,
        val vocabSize: Int
    )

    suspend fun sync(db: AppDatabase, modelId: Long): Status {
        val items = db.trainingItemDao().getAllForModel(modelId)
        val texts = items.filter { it.type == TrainingItem.TYPE_TEXT }
        val prologs = items.filter { it.type == TrainingItem.TYPE_PROLOG }
        val model = db.modelDao().getById(modelId) ?: return Status(false, 0, 0, 0, 0)

        if (texts.isEmpty() && prologs.isEmpty()) {
            if (model.isTrained || !model.trainedDataJson.isNullOrBlank()) {
                db.modelDao().update(
                    model.copy(isTrained = false, trainedDataJson = null)
                )
            }
            return Status(false, 0, 0, 0, 0)
        }

        // Есть материалы — пересобираем индекс, чтобы словарь был актуальным
        val trained = MarkovTrainer.train(
            texts = texts.map { it.content },
            prologTexts = prologs.map { it.content },
            compiledPrologJson = InferenceEngine.deserializeTrained(model.trainedDataJson)?.compiledPrologJson
        )
        // если был prolog — не теряем compiled, пересоберём при необходимости
        val json = InferenceEngine.serializeTrained(trained)
        db.modelDao().update(model.copy(isTrained = true, trainedDataJson = json))
        return Status(
            isTrained = true,
            textCount = texts.size,
            prologCount = prologs.size,
            sentenceCount = trained.sentences.size,
            vocabSize = trained.vocabulary.size
        )
    }

    /** Только сброс, без переобучения (после удаления последнего элемента). */
    suspend fun clearIfEmpty(db: AppDatabase, modelId: Long): Boolean {
        val items = db.trainingItemDao().getAllForModel(modelId)
        if (items.isNotEmpty()) return false
        val model = db.modelDao().getById(modelId) ?: return false
        db.modelDao().update(model.copy(isTrained = false, trainedDataJson = null))
        return true
    }
}
