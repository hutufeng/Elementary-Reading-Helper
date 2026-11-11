package com.assistant.reading.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.reading.core.ai.AppSettingsManager
import com.assistant.reading.core.ai.ChatMessage
import com.assistant.reading.core.ai.ZhipuAiClient
import com.assistant.reading.core.bridge.AuthStatus
import com.assistant.reading.core.bridge.DeepSeekBridgeManager
import com.assistant.reading.core.bridge.SolveStatus
import com.assistant.reading.core.ocr.OcrManager
import com.assistant.reading.core.update.UpdateInfo
import com.assistant.reading.core.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val speechRate: Float = 0.88f,                       // 朗读语速 (0.5x ~ 1.5x)
    val isDetailedMode: Boolean = false,                 // 【需求 1】逐句精讲开关：默认关闭 (false)
    val engineMode: String = "API",                      // 伴读引擎："API" ⇋ "DEEPSEEK_WEB"
    val apiBaseUrl: String = AppSettingsManager.DEFAULT_BASE_URL,
    val modelName: String = AppSettingsManager.DEFAULT_MODEL_NAME,
    val isCheckingUpdate: Boolean = false,               // 是否正在检测更新
    val updateInfo: UpdateInfo? = null,                  // 检测到的新版本详情
    val updateMessage: String? = null                    // 更新提示 Toast 消息
)

class ReadingViewModel(application: Application) : AndroidViewModel(application) {

    private val ocrManager = OcrManager(application)
    private var bridgeManager: DeepSeekBridgeManager? = null

    // 动态注入用户配置的 Key、Base URL 与 Model Name
    private val aiClient = ZhipuAiClient(
        apiKeyProvider = { AppSettingsManager.getApiKey(getApplication()) },
        baseUrlProvider = { AppSettingsManager.getApiBaseUrl(getApplication()) },
        modelNameProvider = { AppSettingsManager.getModelName(getApplication()) }
    )

    private var readingJob: Job? = null

    private val _uiState = MutableStateFlow(
        ReadingUiState(
            speechRate = AppSettingsManager.getSpeechRate(application),
            isDetailedMode = AppSettingsManager.isDetailedModeEnabled(application),
            engineMode = AppSettingsManager.getReadingEngine(application),
            apiBaseUrl = AppSettingsManager.getApiBaseUrl(application),
            modelName = AppSettingsManager.getModelName(application)
        )
    )
    val uiState: StateFlow<ReadingUiState> = _uiState.asStateFlow()

    fun setBridgeManager(manager: DeepSeekBridgeManager) {
        this.bridgeManager = manager
    }

    fun setReadingEngine(mode: String) {
        AppSettingsManager.saveReadingEngine(getApplication(), mode)
        _uiState.value = _uiState.value.copy(engineMode = mode)
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun clearInputText() {
        _uiState.value = _uiState.value.copy(inputText = "")
    }

    /**
     * 开启全新一课：彻底重置所有会话、输入内容与推导树
     */
    fun startNewLesson() {
        stopReadingAssistant()
        _uiState.value = _uiState.value.copy(
            inputText = "",
            readingResult = "",
            sections = emptyList(),
            chatHistory = emptyList(),
            chatQuestion = "",
            isLoading = false,
            isChatLoading = false
        )
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

    fun saveAiConfig(key: String, baseUrl: String, model: String) {
        AppSettingsManager.saveApiKey(getApplication(), key)
        AppSettingsManager.saveApiBaseUrl(getApplication(), baseUrl)
        AppSettingsManager.saveModelName(getApplication(), model)
        _uiState.value = _uiState.value.copy(
            apiBaseUrl = AppSettingsManager.getApiBaseUrl(getApplication()),
            modelName = AppSettingsManager.getModelName(getApplication())
        )
    }

    fun resetAiConfigToDefaults() {
        AppSettingsManager.resetToDefaults(getApplication())
        _uiState.value = _uiState.value.copy(
            apiBaseUrl = AppSettingsManager.DEFAULT_BASE_URL,
            modelName = AppSettingsManager.DEFAULT_MODEL_NAME
        )
    }

    /**
     * 手动检查 GitHub Release 新版本
     */
    fun checkForUpdate(currentVersion: String) {
        if (_uiState.value.isCheckingUpdate) return
        _uiState.value = _uiState.value.copy(isCheckingUpdate = true, updateMessage = null)
        viewModelScope.launch {
            val result = UpdateManager.checkUpdate(currentVersion)
            result.onSuccess { info ->
                _uiState.value = _uiState.value.copy(
                    isCheckingUpdate = false,
                    updateInfo = if (info.hasUpdate) info else null,
                    updateMessage = if (!info.hasUpdate) "当前已是最新版本 (v$currentVersion)" else null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isCheckingUpdate = false,
                    updateMessage = "检测更新失败：${error.localizedMessage ?: "网络异常"}"
                )
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.value = _uiState.value.copy(updateInfo = null)
    }

    fun dismissUpdateMessage() {
        _uiState.value = _uiState.value.copy(updateMessage = null)
    }

    fun getApiKey(): String = AppSettingsManager.getApiKey(getApplication())

    fun isKeyConfigured(): Boolean = AppSettingsManager.isKeyConfigured(getApplication())

    /**
     * 拍照识别单张图片 (兼容旧接口)
     */
    fun processImageUri(uri: Uri) {
        processImageUris(listOf(uri))
    }

    /**
     * 支持多张书籍/课文图片批量 OCR 识别并合并全景推导
     */
    fun processImageUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        stopReadingAssistant()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val textBuilder = StringBuilder()
                for ((index, uri) in uris.withIndex()) {
                    val extracted = ocrManager.recognizeTextFromUri(uri).trim()
                    if (extracted.isNotBlank()) {
                        if (textBuilder.isNotEmpty()) {
                            textBuilder.append("\n\n")
                        }
                        if (uris.size > 1) {
                            textBuilder.append("【第 ${index + 1} 页】\n")
                        }
                        textBuilder.append(extracted)
                    }
                }
                val combinedText = textBuilder.toString().trim()
                if (combinedText.isNotBlank()) {
                    _uiState.value = _uiState.value.copy(inputText = combinedText)
                    startReadingAssistant(combinedText)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * 启动全景导学 (支持 API 接口流式模式 与 DeepSeek 网页免 Key 极速模式)
     * 防重入、会话彻底隔离、支持任务生命周期管控
     */
    fun startReadingAssistant(query: String = _uiState.value.inputText) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || _uiState.value.isLoading) return

        // 停止前序可能在执行的任务
        readingJob?.cancel()

        if (_uiState.value.engineMode == "DEEPSEEK_WEB") {
            // DeepSeek 网页模式 (免 Key、共用理科解题已登录的 WebView 会话)
            if (bridgeManager == null || bridgeManager?.authStatus?.value != AuthStatus.LOGGED_IN) {
                _uiState.value = _uiState.value.copy(
                    updateMessage = "⚠️ DeepSeek 网页未登录！请切换至【理科解题】登录账号，登录一次全局共享免密使用。"
                )
                return
            }

            val guidePrompt = ZhipuAiClient.buildGuidePrompt(_uiState.value.isDetailedMode)
            val customPrompt = """
                $guidePrompt
                
                【请对以下篇目/文本开展全景深度导读】：
                $trimmed
            """.trimIndent()

            bridgeManager?.solveProblem(
                rawProblemText = trimmed,
                imageBase64 = null,
                enableR1 = false, // 语文伴读极速模式：关闭 R1 深度思考
                customPrompt = customPrompt
            )

            readingJob = viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    readingResult = "",
                    sections = emptyList(),
                    chatHistory = emptyList() // 彻底隔离历史问答
                )

                try {
                    launch {
                        bridgeManager?.currentAnswer?.collect { answer ->
                            if (_uiState.value.engineMode == "DEEPSEEK_WEB" && answer.isNotBlank()) {
                                _uiState.value = _uiState.value.copy(
                                    readingResult = answer,
                                    sections = parseSections(answer)
                                )
                            }
                        }
                    }
                    launch {
                        bridgeManager?.solveStatus?.collect { status ->
                            if (_uiState.value.engineMode == "DEEPSEEK_WEB") {
                                if (status == SolveStatus.SUCCESS || status == SolveStatus.ERROR) {
                                    _uiState.value = _uiState.value.copy(isLoading = false)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // 用户主动停止或取消
                }
            }
            return
        }

        // 默认：API 接口流式模式
        readingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                readingResult = "",
                sections = emptyList(),
                chatHistory = emptyList() // 彻底隔离：启动新一课推导时自动清空历史问答，杜绝串台
            )

            try {
                var accumulated = ""
                aiClient.streamAsk(trimmed, isDetailedMode = _uiState.value.isDetailedMode).collect { chunk ->
                    accumulated += chunk
                    _uiState.value = _uiState.value.copy(
                        readingResult = accumulated,
                        sections = parseSections(accumulated)
                    )
                }
            } catch (e: CancellationException) {
                // 用户主动停止或取消，保留当前已生成的阶段性内容，优雅退出
            } catch (e: Exception) {
                // 异常情况下维持现有结果
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * 停止或取消当前正在进行的全景推导任务
     */
    fun stopReadingAssistant() {
        readingJob?.cancel()
        readingJob = null
        if (_uiState.value.engineMode == "DEEPSEEK_WEB") {
            bridgeManager?.stopSolving()
        }
        _uiState.value = _uiState.value.copy(isLoading = false)
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

        if (_uiState.value.engineMode == "DEEPSEEK_WEB") {
            // DeepSeek 网页模式追问
            if (bridgeManager == null || bridgeManager?.authStatus?.value != AuthStatus.LOGGED_IN) {
                _uiState.value = _uiState.value.copy(
                    isChatLoading = false,
                    updateMessage = "⚠️ DeepSeek 网页未登录！请在【理科解题】页登录。"
                )
                return
            }

            val aiMessage = UiChatMessage(isUser = false, content = "")
            _uiState.value = _uiState.value.copy(chatHistory = _uiState.value.chatHistory + aiMessage)
            bridgeManager?.sendFollowUp(questionText)

            viewModelScope.launch {
                launch {
                    bridgeManager?.currentAnswer?.collect { answer ->
                        val updatedHistory = _uiState.value.chatHistory.toMutableList()
                        if (updatedHistory.isNotEmpty()) {
                            updatedHistory[updatedHistory.lastIndex] = aiMessage.copy(content = answer)
                            _uiState.value = _uiState.value.copy(chatHistory = updatedHistory)
                        }
                    }
                }
                launch {
                    bridgeManager?.solveStatus?.collect { status ->
                        if (status == SolveStatus.SUCCESS || status == SolveStatus.ERROR) {
                            _uiState.value = _uiState.value.copy(isChatLoading = false)
                        }
                    }
                }
            }
            return
        }

        viewModelScope.launch {
            val apiMessages = mutableListOf<ChatMessage>()
            apiMessages.add(ChatMessage("system", ZhipuAiClient.TEACHER_SYSTEM_PROMPT))

            if (_uiState.value.inputText.isNotBlank()) {
                apiMessages.add(
                    ChatMessage(
                        "system",
                        "读者当前正在阅读的内容为：\n${_uiState.value.inputText.take(2000)}"
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
