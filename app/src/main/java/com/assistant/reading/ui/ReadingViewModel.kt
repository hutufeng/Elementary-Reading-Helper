package com.assistant.reading.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.reading.core.ai.AppSettingsManager
import com.assistant.reading.core.ai.ChatMessage
import com.assistant.reading.core.ai.ZhipuAiClient
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
    val isSessionLocked: Boolean = false,                // 会话锁定防呆机制：一旦开启推导即锁定，换篇必须点【新一课】
    val apiBaseUrl: String = AppSettingsManager.DEFAULT_BASE_URL,
    val modelName: String = AppSettingsManager.DEFAULT_MODEL_NAME,
    val isCheckingUpdate: Boolean = false,               // 是否正在检测更新
    val updateInfo: UpdateInfo? = null,                  // 检测到的新版本详情
    val updateMessage: String? = null                    // 更新提示 Toast 消息
)

class ReadingViewModel(application: Application) : AndroidViewModel(application) {

    private val ocrManager = OcrManager(application)

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

    fun setReadingEngine(mode: String) {
        AppSettingsManager.saveReadingEngine(getApplication(), mode)
        _uiState.value = _uiState.value.copy(engineMode = mode)
    }

    fun updateInputText(text: String) {
        if (_uiState.value.isSessionLocked) return // 已锁定当前会话，禁止修改
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun clearInputText() {
        if (_uiState.value.isSessionLocked) return
        _uiState.value = _uiState.value.copy(inputText = "")
    }

    /**
     * 开启全新一课：彻底重置所有会话、输入内容与推导树，解锁主输入框
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
            isChatLoading = false,
            isSessionLocked = false // 解除锁定
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
        if (_uiState.value.isSessionLocked) return // 已锁定当前会话，必须点击【新一课】才能开启新篇
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
                    // 先复位 isLoading 为 false，确保随后调用的 startReadingAssistant 不被防重入逻辑拦截
                    _uiState.value = _uiState.value.copy(inputText = combinedText, isLoading = false)
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
     * 启动全景导学 (API 接口原生流式推导)
     * 防重入、会话彻底隔离、支持任务生命周期管控
     */
    fun startReadingAssistant(query: String = _uiState.value.inputText) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || _uiState.value.isLoading) return

        // 停止前序可能在执行的任务
        readingJob?.cancel()

        readingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isSessionLocked = true, // 启动推导后立即锁定会话！杜绝二次输入与混杂错误
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
        _uiState.value = _uiState.value.copy(isLoading = false)
    }

    /**
     * 将 Markdown 智能解析为段落卡片 (彻底过滤一二三四五前面的所有废话，双重语义校验精准识别5大关键点，确保无缝折叠点读)
     */
    private fun parseSections(markdown: String): List<ReadingSection> {
        if (markdown.isBlank()) return emptyList()

        val lines = markdown.lines()
        val sections = mutableListOf<ReadingSection>()
        var sectionIndex = 0

        var currentTitle: String? = null
        val currentBody = StringBuilder()

        fun commitCurrentSection() {
            val title = currentTitle
            if (title != null) {
                val content = currentBody.toString().trim()
                sections.add(
                    ReadingSection(
                        id = sectionIndex++,
                        title = title,
                        content = if (content.isBlank()) "（暂无详细解析）" else content
                    )
                )
                currentBody.clear()
            }
        }

        // 标题判定函数：结合序号与核心专属语义词，彻底根治符号/Emoji/标点差异导致五被漏掉的问题
        fun matchSectionHeader(rawLine: String): String? {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed == "---" || trimmed.length > 80) return null

            // 排除正文里的数字小分项 (如 1. 2. 3. 4.)
            if (Regex("""^[1-4][.、\s]""").containsMatchIn(trimmed)) {
                return null
            }

            // 1. 核心专属关键词优先匹配 (最稳固、抗一切标点与格式扰动)
            val isKeywordHit = trimmed.contains("零障碍逐句精讲") || trimmed.contains("文白全景对照") ||
                    trimmed.contains("这篇作品究竟讲了什么") || trimmed.contains("创作背景与时代风貌") ||
                    trimmed.contains("中心思想与内核概括") || trimmed.contains("核心主旨标准概括") ||
                    trimmed.contains("重要知识点·核心考点") || trimmed.contains("重要知识点与核心考点") || trimmed.contains("核心考点全览") ||
                    trimmed.contains("表达方法与构思艺术") || trimmed.contains("表达方法深度说透") ||
                    trimmed.contains("直接借鉴写作锦囊") || trimmed.contains("写作锦囊") || trimmed.contains("思考考点")

            // 2. 汉字序号规范匹配 (一、/ 二、/ 三、/ 四、/ 五、以及其各种标点变体)
            val chineseNumRegex = Regex(
                """^(?:#{1,6}\s*)?(?:\*{1,2})?(?:[^\w\s\u4e00-\u9fa5]{1,6}\s*)?(?:[一二三四五]|[1-5])[、.．:：\s\-_].*"""
            )
            val isNumHit = chineseNumRegex.matches(trimmed)

            return if (isKeywordHit || isNumHit) {
                trimmed.replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^\\*+\\s*"), "")
                    .replace(Regex("\\*+$"), "")
                    .trim()
            } else null
        }

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed == "---") continue

            val detectedTitle = matchSectionHeader(rawLine)
            if (detectedTitle != null) {
                commitCurrentSection()
                currentTitle = detectedTitle
            } else if (currentTitle != null) {
                // 仅当已经进入第一个板块之后才开始记录正文，板块一之前的所有开篇套话与废话自动被过滤排除
                currentBody.append(rawLine).append("\n")
            }
        }
        commitCurrentSection()

        if (sections.isEmpty()) {
            val fallbackParts = markdown.split(Regex("(?=### )"))
            for (part in fallbackParts) {
                val trimmed = part.trim()
                if (trimmed.isBlank() || trimmed == "---") continue
                val partLines = trimmed.lines()
                val firstLine = partLines.firstOrNull()?.replace("#", "")?.trim() ?: "导读核心"
                val body = partLines.drop(1).joinToString("\n").trim()
                sections.add(
                    ReadingSection(
                        id = sectionIndex++,
                        title = firstLine,
                        content = if (body.isBlank()) trimmed else body
                    )
                )
            }
        }

        return sections
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
