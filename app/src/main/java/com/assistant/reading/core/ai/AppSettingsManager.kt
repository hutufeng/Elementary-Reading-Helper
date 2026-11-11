package com.assistant.reading.core.ai

import android.content.Context
import android.content.SharedPreferences
import com.assistant.reading.BuildConfig

object AppSettingsManager {

    private const val PREF_NAME = "reading_assistant_prefs"
    private const val KEY_API_KEY = "custom_zhipu_api_key"
    private const val KEY_API_BASE_URL = "custom_api_base_url"
    private const val KEY_MODEL_NAME = "custom_model_name"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_DETAILED_MODE = "detailed_sentence_mode"

    // 默认预设配置：智谱 AI (GLM-4-Flash 永久免费)
    const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val DEFAULT_MODEL_NAME = "glm-4-flash"
    const val DEFAULT_SPEECH_RATE = 0.88f

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * API Key 管理 (自定义优先，降级 BuildConfig)
     */
    fun getApiKey(context: Context): String {
        val customKey = getPrefs(context).getString(KEY_API_KEY, "")?.trim() ?: ""
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
        getPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun isKeyConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * API 基础接口地址 (Base URL，兼容标准 OpenAI 协议)
     */
    fun getApiBaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_API_BASE_URL, DEFAULT_BASE_URL)?.trim() ?: DEFAULT_BASE_URL
    }

    fun saveApiBaseUrl(context: Context, url: String) {
        val cleanUrl = if (url.isBlank()) DEFAULT_BASE_URL else url.trim().trimEnd('/')
        getPrefs(context).edit().putString(KEY_API_BASE_URL, cleanUrl).apply()
    }

    /**
     * 大模型名称 (Model)
     */
    fun getModelName(context: Context): String {
        return getPrefs(context).getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME)?.trim() ?: DEFAULT_MODEL_NAME
    }

    fun saveModelName(context: Context, model: String) {
        val cleanModel = if (model.isBlank()) DEFAULT_MODEL_NAME else model.trim()
        getPrefs(context).edit().putString(KEY_MODEL_NAME, cleanModel).apply()
    }

    /**
     * 恢复默认 AI 设置 (重置为智谱官方推荐配置)
     */
    fun resetToDefaults(context: Context) {
        getPrefs(context).edit()
            .putString(KEY_API_BASE_URL, DEFAULT_BASE_URL)
            .putString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME)
            .apply()
    }

    /**
     * 朗读语速 (默认 0.88x)
     */
    fun getSpeechRate(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
    }

    fun saveSpeechRate(context: Context, rate: Float) {
        getPrefs(context).edit().putFloat(KEY_SPEECH_RATE, rate).apply()
    }

    /**
     * 逐句/文言精讲模式开关 (【需求 1】按要求默认设为关闭 false)
     */
    fun isDetailedModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DETAILED_MODE, false)
    }

    fun saveDetailedModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DETAILED_MODE, enabled).apply()
    }

    /**
     * 语文伴读引擎 (API 模式 ⇋ DeepSeek 网页免 Key 模式)
     */
    fun getReadingEngine(context: Context): String {
        return getPrefs(context).getString("reading_engine_mode", "API") ?: "API"
    }

    fun saveReadingEngine(context: Context, mode: String) {
        getPrefs(context).edit().putString("reading_engine_mode", mode).apply()
    }
}
