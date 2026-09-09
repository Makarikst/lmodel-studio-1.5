package ru.hyperplanet.lmodel.studio.ml

import ru.hyperplanet.lmodel.studio.data.AppDatabase
import ru.hyperplanet.lmodel.studio.data.TrainingItem
import ru.hyperplanet.lmodel.studio.data.update

/**
 * Лёгкая синхронизация: НЕ грузит весь датасет в память.
 */
object ModelTrainingSync {

    data class Status(
        val isTrained: Boolean,
        val textCount: Int,
        val prologCount: Int,
        val mediaCount: Int,
        val sentenceCount: Int,
        val vocabSize: Int
    )

    private const val SAMPLE = 3000

    suspend fun sync(db: AppDatabase, modelId: Long): Status {
        val count = try {
            db.trainingItemDao().countForModel(modelId)
        } catch (_: Throwable) {
            0
        }
        val model = db.modelDao().getById(modelId) ?: return Status(false, 0, 0, 0, 0, 0)

        if (count <= 0) {
            if (model.isTrained || !model.trainedDataJson.isNullOrBlank()) {
                try {
                    db.modelDao().update(model.copy(isTrained = false, trainedDataJson = null))
                } catch (_: Throwable) { }
            }
            return Status(false, 0, 0, 0, 0, 0)
        }

        // Только сэмпл — иначе OOM и вылет при входе в редактор
        val items = try {
            db.trainingItemDao().getSampleForModel(modelId, SAMPLE)
        } catch (_: Throwable) {
            try {
                db.trainingItemDao().getAllForModel(modelId).take(SAMPLE)
            } catch (_: Throwable) {
                emptyList()
            }
        }

        val prologs = items.filter { it.type == TrainingItem.TYPE_PROLOG }
        val texts = items.filter { it.type != TrainingItem.TYPE_PROLOG }
        if (texts.isEmpty() && prologs.isEmpty()) {
            return Status(model.isTrained, count, 0, 0, 0, 0)
        }

        val corpus = texts.map { item ->
            listOfNotNull(item.analysisText, item.content, item.originalName)
                .joinToString("\n")
                .take(2000)
        }.filter { it.isNotBlank() }

        val trained = MarkovTrainer.train(
            texts = corpus,
            prologTexts = prologs.map { it.content.take(1000) },
            compiledPrologJson = null
        )
        val json = InferenceEngine.serializeTrained(trained).let {
            if (it.length > 1_500_000) it.take(1_500_000) else it
        }
        try {
            db.modelDao().update(model.copy(isTrained = true, trainedDataJson = json))
        } catch (_: Throwable) {
            try {
                db.modelDao().updateTrained(
                    id = modelId,
                    isTrained = true,
                    trainedDataJson = json,
                    supportsPhoto = model.supportsPhoto,
                    supportsAudio = model.supportsAudio,
                    supportsVideo = model.supportsVideo
                )
            } catch (_: Throwable) { }
        }
        return Status(
            isTrained = true,
            textCount = count,
            prologCount = prologs.size,
            mediaCount = 0,
            sentenceCount = trained.sentences.size,
            vocabSize = trained.vocabulary.size
        )
    }

    suspend fun clearIfEmpty(db: AppDatabase, modelId: Long): Boolean {
        val count = try {
            db.trainingItemDao().countForModel(modelId)
        } catch (_: Throwable) {
            return false
        }
        if (count > 0) return false
        val model = db.modelDao().getById(modelId) ?: return false
        if (model.isTrained || !model.trainedDataJson.isNullOrBlank()) {
            db.modelDao().update(model.copy(isTrained = false, trainedDataJson = null))
        }
        return true
    }
}