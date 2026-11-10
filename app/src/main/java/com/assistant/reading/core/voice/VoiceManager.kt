package com.assistant.reading.core.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class PlaybackState {
    IDLE,       // 空闲
    PLAYING,    // 正在朗读
    PAUSED      // 已暂停 (保留断点)
}

class VoiceManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false
    private var currentRate: Float = 0.88f
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState = _playbackState.asStateFlow()

    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex: Int = 0

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.CHINESE)
            isReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(currentRate)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        if (_playbackState.value != PlaybackState.PAUSED) {
                            _playbackState.value = PlaybackState.PLAYING
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (_playbackState.value == PlaybackState.PLAYING) {
                            currentSentenceIndex++
                            if (currentSentenceIndex < sentences.size) {
                                playNextSentence()
                            } else {
                                _playbackState.value = PlaybackState.IDLE
                                currentSentenceIndex = 0
                            }
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        _playbackState.value = PlaybackState.IDLE
                    }
                }
            })
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
     * 从头开始朗读指定段落文本 (支持断点切句记忆)
     */
    fun speak(text: String) {
        val cleanText = cleanMarkdownForSpeech(text)
        if (cleanText.isBlank()) return

        stopSpeaking()
        sentences = splitIntoSentences(cleanText)
        currentSentenceIndex = 0

        if (isReady && sentences.isNotEmpty()) {
            tts?.setSpeechRate(currentRate)
            _playbackState.value = PlaybackState.PLAYING
            playNextSentence()
        }
    }

    /**
     * 暂停当前朗读 (记录已读到的分句断点)
     */
    fun pause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            _playbackState.value = PlaybackState.PAUSED
            tts?.stop()
        }
    }

    /**
     * 从暂停处继续朗读
     */
    fun resume() {
        if (_playbackState.value == PlaybackState.PAUSED && isReady) {
            _playbackState.value = PlaybackState.PLAYING
            tts?.setSpeechRate(currentRate)
            playNextSentence()
        }
    }

    /**
     * 停止朗读并复位状态
     */
    fun stopSpeaking() {
        _playbackState.value = PlaybackState.IDLE
        currentSentenceIndex = 0
        sentences = emptyList()
        tts?.stop()
    }

    private fun playNextSentence() {
        if (currentSentenceIndex < sentences.size) {
            val sentence = sentences[currentSentenceIndex]
            tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "voice_utterance_${currentSentenceIndex}_${System.currentTimeMillis()}")
        } else {
            _playbackState.value = PlaybackState.IDLE
            currentSentenceIndex = 0
        }
    }

    /**
     * 将长文本切分为适合 TTS 朗读与断点记忆的句子序列
     */
    private fun splitIntoSentences(text: String): List<String> {
        val regex = Regex("(?<=[。！？!?；;\n])")
        return text.split(regex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        _playbackState.value = PlaybackState.IDLE
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
