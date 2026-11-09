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
     * 朗读指定段落文本
     */
    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            tts?.stop()
            tts?.setSpeechRate(currentRate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reading_tts_${System.currentTimeMillis()}")
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
}
