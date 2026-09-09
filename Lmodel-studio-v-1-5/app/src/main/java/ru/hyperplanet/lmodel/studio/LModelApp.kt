package ru.hyperplanet.lmodel.studio

import android.app.Application
import ru.hyperplanet.lmodel.studio.util.PersistentMediaStorage

class LModelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Система уже создаёт Android/media/<package>/; ensureReady помечает и делает подпапки
        PersistentMediaStorage.ensureReady(this)
        PersistentMediaStorage.restoreDatabaseIfNeeded(this)
    }
}
