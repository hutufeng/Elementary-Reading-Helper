package com.assistant.reading.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.CHINESE)
            isReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.88f) // 慢速清晰
        }
    }

    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            tts?.stop()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reading_tts")
        }
    }

    fun stopSpeaking() { tts?.stop() }
    fun destroy() {
        tts?.stop()
        tts?.shutdown()
    }
}
