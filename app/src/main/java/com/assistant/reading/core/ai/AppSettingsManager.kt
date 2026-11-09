package com.assistant.reading.core.ai

import android.content.Context
import android.content.SharedPreferences
import com.assistant.reading.BuildConfig

object AppSettingsManager {

    private const val PREF_NAME = "reading_assistant_prefs"
    private const val KEY_ZHIPU_API_KEY = "custom_zhipu_api_key"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_DETAILED_MODE = "detailed_sentence_mode"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取当前生效的 API Key
     */
    fun getApiKey(context: Context): String {
        val customKey = getPrefs(context).getString(KEY_ZHIPU_API_KEY, "")?.trim() ?: ""
        if (customKey.isNotBlank()) {
            return customKey
        }
        val defaultKey = BuildConfig.ZHIPU_API_KEY.trim()
        return if (defaultKey != "MOCK_KEY_FOR_DEV" && defaultKey != "your_actual_api_key_here") {
            defaultKey
        } else {
            ""
        }
    }

    fun saveApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_ZHIPU_API_KEY, key.trim()).apply()
    }

    fun isKeyConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * 朗读语速设置 (默认 0.88f)
     */
    fun getSpeechRate(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_RATE, 0.88f)
    }

    fun saveSpeechRate(context: Context, rate: Float) {
        getPrefs(context).edit().putFloat(KEY_SPEECH_RATE, rate).apply()
    }

    /**
     * 逐句/古文全译精讲模式开关 (默认开启)
     */
    fun isDetailedModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DETAILED_MODE, true)
    }

    fun saveDetailedModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DETAILED_MODE, enabled).apply()
    }
}
