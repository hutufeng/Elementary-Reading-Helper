package com.assistant.reading.core.ai

import android.content.Context
import android.content.SharedPreferences
import com.assistant.reading.BuildConfig

object ApiKeyManager {

    private const val PREF_NAME = "reading_assistant_prefs"
    private const val KEY_ZHIPU_API_KEY = "custom_zhipu_api_key"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取当前生效的 API Key
     * 优先级: 用户在页面设置的自定义 Key -> BuildConfig 预置的 Key
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

    /**
     * 保存用户在设置页面输入的 API Key
     */
    fun saveApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_ZHIPU_API_KEY, key.trim()).apply()
    }

    /**
     * 清空自定义 Key
     */
    fun clearApiKey(context: Context) {
        getPrefs(context).edit().remove(KEY_ZHIPU_API_KEY).apply()
    }

    /**
     * 检查当前是否有有效的 API Key 可供使用
     */
    fun isKeyConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }
}
