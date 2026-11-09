package com.assistant.reading.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false
    private var currentRate: Float = 0.88f

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.CHINESE)
            isReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(currentRate)
        }
    }

    /**
     * 动态设置朗读语速 (0.6x 极慢认字 ~ 1.5x 快速复习)
     */
    fun setSpeechRate(rate: Float) {
        currentRate = rate.coerceIn(0.5f, 2.0f)
        if (isReady) {
            tts?.setSpeechRate(currentRate)
        }
    }

    fun getSpeechRate(): Float = currentRate

    /**
     * 朗读指定段落文本 (自动净化 Markdown 标签，避免读出星号或井号)
     */
    fun speak(text: String) {
        val cleanText = cleanMarkdownForSpeech(text)
        if (isReady && cleanText.isNotBlank()) {
            tts?.stop()
            tts?.setSpeechRate(currentRate)
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "reading_tts_${System.currentTimeMillis()}")
        }
    }

    /**
     * 停止当前朗读
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        /**
         * 净化文本中的 Markdown 标记，避免 TTS 引擎念出“星号”、“井号”等符号
         */
        fun cleanMarkdownForSpeech(rawText: String): String {
            if (rawText.isBlank()) return ""

            var text = rawText
            // 1. 移除 Markdown 代码块
            text = text.replace(Regex("```[\\s\\S]*?```"), "")
            // 2. 移除行内代码 `code` -> code
            text = text.replace(Regex("`([^`]+)`"), "$1")
            // 3. 移除 Markdown 标题标记 (###, ##, #)
            text = text.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            // 4. 移除分割线 (---, ***, ===)
            text = text.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")
            // 5. 移除粗体和斜体标记：**文本** -> 文本, *文本* -> 文本, __文本__ -> 文本
            text = text.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            text = text.replace(Regex("__(.*?)__"), "$1")
            text = text.replace(Regex("\\*(.*?)\\*"), "$1")
            text = text.replace(Regex("_(.*?)_"), "$1")
            // 6. 移除链接语法 [文本](url) -> 文本
            text = text.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
            // 7. 移除无序列表符号 (- 列表项, * 列表项, + 列表项)
            text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            // 8. 移除多余的引用符号 (> 引用)
            text = text.replace(Regex("(?m)^\\s*>+\\s*"), "")
            // 9. 移除残留孤立的星号、井号与无声干扰符号
            text = text.replace(Regex("[*#~]"), "")
            // 10. 规整多余连续换行
            text = text.replace(Regex("\\n{3,}"), "\n\n")

            return text.trim()
        }
    }
}
