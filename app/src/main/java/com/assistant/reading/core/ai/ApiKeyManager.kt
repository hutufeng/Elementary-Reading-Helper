package com.assistant.reading.core.ai

import android.content.Context

object ApiKeyManager {
    fun getApiKey(context: Context): String = AppSettingsManager.getApiKey(context)
    fun saveApiKey(context: Context, key: String) = AppSettingsManager.saveApiKey(context, key)
    fun isKeyConfigured(context: Context): Boolean = AppSettingsManager.isKeyConfigured(context)
}
