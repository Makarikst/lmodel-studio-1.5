package ru.hyperplanet.lmodel.studio

import android.content.Context

object SessionIds {
    @Volatile var modelId: Long = -1L

    fun setModel(ctx: Context, id: Long) {
        if (id > 0) {
            modelId = id
            ctx.applicationContext
                .getSharedPreferences("session_ids", Context.MODE_PRIVATE)
                .edit().putLong("model_id", id).apply()
        }
    }

    fun getModel(ctx: Context): Long {
        if (modelId > 0) return modelId
        val p = ctx.applicationContext
            .getSharedPreferences("session_ids", Context.MODE_PRIVATE)
            .getLong("model_id", -1L)
        if (p > 0) modelId = p
        return modelId
    }
}