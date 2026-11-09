package com.assistant.reading.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.reading.BuildConfig
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

data class ReadingUiState(
    val inputText: String = "",               // 课文/短文输入框内容
    val readingResult: String = "",            // 五维导学解析结果
    val chatQuestion: String = "",            // 自由问答提问框内容
    val chatHistory: List<UiChatMessage> = emptyList(), // 多轮互动对话历史
    val isLoading: Boolean = false,            // 导读/AI 正在输出中
    val isChatLoading: Boolean = false,        // 问答对话正在输出中
    val fontSizeSp: Int = 18                   // 护眼字号大小
)

class ReadingViewModel(application: Application) : AndroidViewModel(application) {

    private val ocrManager = OcrManager(application)
    private val aiClient = ZhipuAiClient(apiKey = BuildConfig.ZHIPU_API_KEY)

    private val _uiState = MutableStateFlow(ReadingUiState())
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
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * 启动五维深度导学
     */
    fun startReadingAssistant(query: String = _uiState.value.inputText) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                readingResult = ""
            )

            aiClient.streamAsk(query).collect { chunk ->
                _uiState.value = _uiState.value.copy(
                    readingResult = _uiState.value.readingResult + chunk
                )
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * 自由 AI 互动问答：支持基于当前短文深度追问，也支持独立自由提问
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
            // 组装带上下文的消息链
            val apiMessages = mutableListOf<ChatMessage>()
            apiMessages.add(ChatMessage("system", ZhipuAiClient.TEACHER_SYSTEM_PROMPT))

            // 如果当前有正在阅读的短文或课文，作为上下文注入，便于针对性追问
            if (_uiState.value.inputText.isNotBlank()) {
                apiMessages.add(
                    ChatMessage(
                        "system",
                        "学生当前正在阅读的课文/资料背景内容为：\n${_uiState.value.inputText.take(2000)}"
                    )
                )
            }

            // 附加最近的对话历史 (保留最近 6 条，保证流畅且不超出限制)
            val recentHistory = currentHistory.takeLast(6)
            for (msg in recentHistory) {
                apiMessages.add(
                    ChatMessage(
                        role = if (msg.isUser) "user" else "assistant",
                        content = msg.content
                    )
                )
            }

            // 先占位一个空的 assistant 回复消息用于流式追加
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
