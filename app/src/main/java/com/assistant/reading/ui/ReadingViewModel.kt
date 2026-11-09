package com.assistant.reading.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.reading.core.ai.AppSettingsManager
import com.assistant.reading.core.ai.ChatMessage
import com.assistant.reading.core.ai.ZhipuAiClient
import com.assistant.reading.core.ocr.OcrManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiChatMessage(
    val id: Long = System.currentTimeMillis(),
    val isUser: Boolean,
    val content: String
)

data class ReadingSection(
    val id: Int,
    val title: String,
    val content: String
)

data class ReadingUiState(
    val inputText: String = "",                         // 课文/短文输入框内容
    val readingResult: String = "",                      // 五维导学原始结果
    val sections: List<ReadingSection> = emptyList(),    // 按章节/段落拆分的结构化列表 (支持分段点读)
    val chatQuestion: String = "",                      // 自由问答提问框内容
    val chatHistory: List<UiChatMessage> = emptyList(),   // 多轮互动对话历史
    val isLoading: Boolean = false,                      // 导读/AI 正在输出中
    val isChatLoading: Boolean = false,                  // 问答对话正在输出中
    val fontSizeSp: Int = 18,                            // 护眼字号大小
    val speechRate: Float = 0.88f,                       // 朗读语速 (0.6x ~ 1.5x)
    val isDetailedMode: Boolean = true                   // 逐句精讲/古文全译开关 (默认开启)
)

class ReadingViewModel(application: Application) : AndroidViewModel(application) {

    private val ocrManager = OcrManager(application)
    private val aiClient = ZhipuAiClient { AppSettingsManager.getApiKey(getApplication()) }

    private val _uiState = MutableStateFlow(
        ReadingUiState(
            speechRate = AppSettingsManager.getSpeechRate(application),
            isDetailedMode = AppSettingsManager.isDetailedModeEnabled(application)
        )
    )
    val uiState: StateFlow<ReadingUiState> = _uiState.asStateFlow()

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun updateChatQuestion(text: String) {
        _uiState.value = _uiState.value.copy(chatQuestion = text)
    }

    fun changeFontSize(delta: Int) {
        val newSize = (_uiState.value.fontSizeSp + delta).coerceIn(14, 28)
        _uiState.value = _uiState.value.copy(fontSizeSp = newSize)
    }

    fun setSpeechRate(rate: Float) {
        AppSettingsManager.saveSpeechRate(getApplication(), rate)
        _uiState.value = _uiState.value.copy(speechRate = rate)
    }

    fun toggleDetailedMode(enabled: Boolean) {
        AppSettingsManager.saveDetailedModeEnabled(getApplication(), enabled)
        _uiState.value = _uiState.value.copy(isDetailedMode = enabled)
    }

    fun saveApiKey(key: String) {
        AppSettingsManager.saveApiKey(getApplication(), key)
    }

    fun getApiKey(): String = AppSettingsManager.getApiKey(getApplication())

    fun isKeyConfigured(): Boolean = AppSettingsManager.isKeyConfigured(getApplication())

    /**
     * 拍照识别后自动开始深度导读
     */
    fun processImageUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val extracted = ocrManager.recognizeTextFromUri(uri)
                _uiState.value = _uiState.value.copy(inputText = extracted)
                startReadingAssistant(extracted)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * 启动五维全景导学 (支持流式更新与动态分段)
     */
    fun startReadingAssistant(query: String = _uiState.value.inputText) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                readingResult = "",
                sections = emptyList()
            )

            var accumulated = ""
            aiClient.streamAsk(query, isDetailedMode = _uiState.value.isDetailedMode).collect { chunk ->
                accumulated += chunk
                _uiState.value = _uiState.value.copy(
                    readingResult = accumulated,
                    sections = parseSections(accumulated)
                )
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * 将 Markdown 智能解析为段落卡片 (供每段独立点读)
     */
    private fun parseSections(markdown: String): List<ReadingSection> {
        if (markdown.isBlank()) return emptyList()
        val parts = markdown.split(Regex("(?=### )"))
        var index = 0
        return parts.mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isBlank() || trimmed == "---") null
            else {
                val lines = trimmed.lines()
                val firstLine = lines.firstOrNull()?.replace("#", "")?.trim() ?: "导读精要"
                val body = lines.drop(1).joinToString("\n").trim()
                ReadingSection(
                    id = index++,
                    title = firstLine,
                    content = if (body.isBlank()) trimmed else body
                )
            }
        }
    }

    /**
     * 自由 AI 互动问答
     */
    fun sendChatQuestion(questionText: String = _uiState.value.chatQuestion) {
        if (questionText.isBlank()) return

        val userMessage = UiChatMessage(isUser = true, content = questionText)
        val currentHistory = _uiState.value.chatHistory + userMessage
        _uiState.value = _uiState.value.copy(
            chatHistory = currentHistory,
            chatQuestion = "",
            isChatLoading = true
        )

        viewModelScope.launch {
            val apiMessages = mutableListOf<ChatMessage>()
            apiMessages.add(ChatMessage("system", ZhipuAiClient.TEACHER_SYSTEM_PROMPT))

            if (_uiState.value.inputText.isNotBlank()) {
                apiMessages.add(
                    ChatMessage(
                        "system",
                        "学生当前正在阅读的内容为：\n${_uiState.value.inputText.take(2000)}"
                    )
                )
            }

            val recentHistory = currentHistory.takeLast(6)
            for (msg in recentHistory) {
                apiMessages.add(
                    ChatMessage(
                        role = if (msg.isUser) "user" else "assistant",
                        content = msg.content
                    )
                )
            }

            val aiMessage = UiChatMessage(isUser = false, content = "")
            _uiState.value = _uiState.value.copy(
                chatHistory = _uiState.value.chatHistory + aiMessage
            )

            var accumulatedText = ""
            aiClient.streamChat(apiMessages).collect { delta ->
                accumulatedText += delta
                val updatedHistory = _uiState.value.chatHistory.toMutableList()
                if (updatedHistory.isNotEmpty()) {
                    updatedHistory[updatedHistory.lastIndex] = aiMessage.copy(content = accumulatedText)
                    _uiState.value = _uiState.value.copy(chatHistory = updatedHistory)
                }
            }
            _uiState.value = _uiState.value.copy(isChatLoading = false)
        }
    }

    fun clearChatHistory() {
        _uiState.value = _uiState.value.copy(chatHistory = emptyList())
    }
}
