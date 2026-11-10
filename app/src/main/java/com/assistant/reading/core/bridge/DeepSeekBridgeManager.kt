package com.assistant.reading.core.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONTokener

enum class AuthStatus {
    CHECKING,
    LOGGED_IN,
    NOT_LOGGED_IN
}

enum class SolveStatus {
    IDLE,
    SOLVING,
    SUCCESS,
    ERROR
}

data class QaHistoryItem(
    val question: String,
    val thinking: String,
    val answer: String,
    val answerHtml: String = "",
    val plainText: String = ""
)

@SuppressLint("SetJavaScriptEnabled")
class DeepSeekBridgeManager(private val context: Context) {

    companion object {
        const val DEEPSEEK_HOME_URL = "https://chat.deepseek.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _authStatus = MutableStateFlow(AuthStatus.CHECKING)
    val authStatus = _authStatus.asStateFlow()

    private val _solveStatus = MutableStateFlow(SolveStatus.IDLE)
    val solveStatus = _solveStatus.asStateFlow()

    private val _currentThinking = MutableStateFlow("")
    val currentThinking = _currentThinking.asStateFlow()

    private val _currentAnswer = MutableStateFlow("")
    val currentAnswer = _currentAnswer.asStateFlow()

    private val _currentAnswerHtml = MutableStateFlow("")
    val currentAnswerHtml = _currentAnswerHtml.asStateFlow()

    private val _currentPlainText = MutableStateFlow("")
    val currentPlainText = _currentPlainText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _showCaptchaDialog = MutableStateFlow(false)
    val showCaptchaDialog = _showCaptchaDialog.asStateFlow()

    private val _webProgress = MutableStateFlow(0)
    val webProgress = _webProgress.asStateFlow()

    private val _qaHistory = MutableStateFlow<List<QaHistoryItem>>(emptyList())
    val qaHistory = _qaHistory.asStateFlow()

    private val _stepDiagnosis = MutableStateFlow("空闲待命")
    val stepDiagnosis = _stepDiagnosis.asStateFlow()

    private var lastSentQuestion: String = ""
    private var solveTimeoutRunnable: Runnable? = null

    val webView: WebView by lazy {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = USER_AGENT
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            // 显式激活运行周期与计时器，防止后台休眠
            onResume()
            resumeTimers()

            // 启用 Cookie 支持
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(AndroidJsBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkUrlAndCookies(url)
                    injectObserverScript()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        _errorMessage.value = "网页连接出现波动，请检查网络或点击刷新"
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    _webProgress.value = newProgress
                }
            }

            loadUrl(DEEPSEEK_HOME_URL)
        }
    }

    /**
     * 检查并刷新登录状态
     */
    fun checkLoginStatus(forceReload: Boolean = false) {
        _authStatus.value = AuthStatus.CHECKING
        mainHandler.post {
            if (forceReload) {
                webView.reload()
            } else {
                checkUrlAndCookies(webView.url)
            }
        }
    }

    private fun checkUrlAndCookies(currentUrl: String?) {
        val cookies = CookieManager.getInstance().getCookie(DEEPSEEK_HOME_URL) ?: ""
        val url = currentUrl ?: ""

        val hasAuthToken = cookies.contains("userToken") || cookies.contains("auth_token") || cookies.contains("session")
        val isAtLoginPage = url.contains("/login") || url.contains("/sign_in")

        if (hasAuthToken && !isAtLoginPage) {
            _authStatus.value = AuthStatus.LOGGED_IN
        } else if (isAtLoginPage || cookies.isBlank()) {
            _authStatus.value = AuthStatus.NOT_LOGGED_IN
        } else {
            // 通过 W3C 标准脚本进一步探测是否存在输入框
            detectChatInputDom()
        }
    }

    private fun detectChatInputDom() {
        val js = """
            (function() {
                function hasElementWithText(selector, keyword) {
                    const list = document.querySelectorAll(selector);
                    for (let i = 0; i < list.length; i++) {
                        if (list[i].innerText && list[i].innerText.includes(keyword)) return true;
                    }
                    return false;
                }

                const textarea = document.querySelector('textarea');
                const hasLoginLink = !!document.querySelector('a[href*="login"], a[href*="sign_in"]');
                const hasLoginBtn = hasElementWithText('button', '登录') || hasElementWithText('div[role="button"]', '登录');

                if (textarea && !hasLoginLink && !hasLoginBtn) {
                    window.AndroidBridge.onAuthResult(true);
                } else {
                    window.AndroidBridge.onAuthResult(false);
                }
            })();
        """.trimIndent()
        mainHandler.post {
            webView.resumeTimers()
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 注入 DOM 监听与捕获脚本 (严格等待推导完成，杜绝半成品公式；精准提取最后一轮问答，杜绝追问重复)
     */
    private fun injectObserverScript() {
        val d = '$'
        val js = """
            (function() {
                if (window.__ds_observer_injected) return;
                window.__ds_observer_injected = true;

                // 提取节点的 Markdown 文本 (规范化 LaTeX 符号，保留段落换行与步骤骨架)
                function extractMarkdownFromNode(node) {
                    if (!node) return "";
                    const clone = node.cloneNode(true);

                    // 1. 将 KaTeX 替换为原生 TeX 源码，块级公式用 $$ 包裹，行内公式用 $ 包裹
                    const katexs = Array.from(clone.querySelectorAll('.katex'));
                    katexs.forEach(k => {
                        const ann = k.querySelector('annotation[encoding*="tex"]');
                        let tex = "";
                        if (ann && ann.textContent) {
                            tex = ann.textContent.trim();
                        } else {
                            tex = (k.textContent || "").replace(/\s+/g, ' ').trim();
                        }
                        const isDisplay = k.classList.contains('katex-display') || k.closest('.katex-display');
                        const replacement = isDisplay ? ("\n\n$d$d " + tex + " $d$d\n\n") : (" $d" + tex + "$d ");
                        const textNode = document.createTextNode(replacement);
                        if (k.parentNode) {
                            k.parentNode.replaceChild(textNode, k);
                        }
                    });

                    // 2. 清除渲染噪音与交互按键 (只清除真正的交互按钮与图标，严禁误杀包含 action 等类名的推导容器)
                    clone.querySelectorAll('.katex-html, .katex-mathml, button, svg').forEach(el => el.remove());

                    // 3. 规范段落与列表换行
                    clone.querySelectorAll('p').forEach(p => {
                        p.append(document.createTextNode("\n\n"));
                    });
                    clone.querySelectorAll('li').forEach(li => {
                        li.prepend(document.createTextNode("\n• "));
                    });

                    // 4. 采用 textContent 全量递归提取，彻底杜绝游离 DOM 树中 innerText 提前截断的系统 Bug！
                    let raw = (clone.textContent || "").trim();
                    return raw.replace(/\n{3,}/g, '\n\n');
                }

                // 提取供语音朗读的纯文本 (去除公式符号干扰，发音自然)
                function extractPlainTextFromNode(node) {
                    if (!node) return "";
                    const clone = node.cloneNode(true);
                    const katexs = Array.from(clone.querySelectorAll('.katex'));
                    katexs.forEach(k => {
                        const ann = k.querySelector('annotation[encoding*="tex"]');
                        let tex = (ann && ann.textContent) ? ann.textContent.trim() : (k.textContent || "");
                        tex = tex.replace(/\\times/g, " 乘以 ")
                                 .replace(/\\div/g, " 除以 ")
                                 .replace(/\\frac\{([^{}]+)\}\{([^{}]+)\}/g, " ${d}2分之${d}1 ")
                                 .replace(/\\sqrt\{([^{}]+)\}/g, " 根号${d}1 ")
                                 .replace(/\\pm/g, " 加减 ")
                                 .replace(/[\\${d}{}]/g, " ");
                        const textNode = document.createTextNode(" " + tex + " ");
                        if (k.parentNode) k.parentNode.replaceChild(textNode, k);
                    });
                    clone.querySelectorAll('.katex-html, .katex-mathml, button, svg').forEach(el => el.remove());
                    return (clone.textContent || "").replace(/\s+/g, ' ').trim();
                }

                // 基于“用户最新输入为分水岭”的全量无损提取引擎 (从最后一次输入一直提取到页面末尾)
                // 100% 保证：本轮考点、所有分步推导、公式、易错点完整全量；同时物理隔离上一问与历史，零重复！
                function extractLatestTurnData() {
                    // 1. 定位页面上的思考容器
                    const thinkContainers = Array.from(document.querySelectorAll('[class*="thinking"], [class*="thought"]'));
                    let lastThinkElem = thinkContainers.length > 0 ? thinkContainers[thinkContainers.length - 1] : null;
                    let thinkingText = lastThinkElem ? extractMarkdownFromNode(lastThinkElem) : "";

                    // 2. 获取全页面所有的 .ds-markdown 节点
                    const allMds = Array.from(document.querySelectorAll('.ds-markdown'));
                    if (allMds.length === 0) return null;

                    // 3. 严格排除属于思考容器内部的 .ds-markdown，锁定全部正文解答块
                    const contentMds = allMds.filter(md => {
                        if (lastThinkElem && (lastThinkElem === md || lastThinkElem.contains(md))) {
                            return false;
                        }
                        return true;
                    });

                    if (contentMds.length === 0) {
                        return {
                            thinking: thinkingText.trim(),
                            markdown: "",
                            html: "",
                            plainText: ""
                        };
                    }

                    // 4. 定位属于本轮正式回答的所有节点：
                    // 在 contentMds 中，排除带 data-ds-history="true" 的旧历史
                    let currentTurnMds = contentMds.filter(md => md.getAttribute('data-ds-history') !== 'true');

                    // 容错兜底：若均为历史或首次加载，直接取 contentMds 中的最后一个（确保拿到全量正文）
                    if (currentTurnMds.length === 0) {
                        currentTurnMds = [contentMds[contentMds.length - 1]];
                    }

                    // 5. 将本轮正文回答的所有 markdown 节点无损全量拼接提取，绝不遗漏后半段
                    let answerHtml = currentTurnMds.map(md => md.innerHTML).join('<div style="margin:16px 0;border-top:1px dashed #e1e4e8;"></div>');
                    let answerMarkdown = currentTurnMds.map(md => extractMarkdownFromNode(md)).filter(t => t.length > 0).join("\n\n");
                    let plainText = currentTurnMds.map(md => extractPlainTextFromNode(md)).filter(t => t.length > 0).join(" ");

                    return {
                        thinking: thinkingText.trim(),
                        markdown: answerMarkdown.trim(),
                        html: answerHtml.trim(),
                        plainText: plainText.trim()
                    };
                }

                // 基于“停止状态 vs 发送/操作工具栏状态”互斥的第一性原理精准判定大模型生成状态
                function detectGenerationStatus() {
                    const allButtons = Array.from(document.querySelectorAll('button, div[role="button"], a[role="button"]'));
                    let hasStopButton = false;
                    let hasSendButton = false;
                    let hasMessageActionTools = false;

                    for (const btn of allButtons) {
                        const label = (btn.getAttribute('aria-label') || btn.innerText || btn.title || '').trim().toLowerCase();
                        const isStopWord = label.includes('停止') || label.includes('stop') || label.includes('终止');
                        // 判定停止图标：包含矩形/正方形方块
                        const hasSquareIcon = !!btn.querySelector('rect, [d*="M6 6h12v12"], [d*="M4 4h16v16"], [d*="M3 3h18v18"], [d*="M2 2h20v20"], [d*="M5 5h14v14"]');
                        const isStopClass = typeof btn.className === 'string' && btn.className.toLowerCase().includes('stop');

                        if (isStopWord || hasSquareIcon || isStopClass) {
                            const rect = btn.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) {
                                hasStopButton = true;
                                break;
                            }
                        }
                    }

                    // 检查打字机闪烁光标 (DeepSeek 专属 class 和通用 typing)
                    const hasTypingCursor = !!document.querySelector('.ds-typing, .typing, [class*="cursor"], [class*="blinking"]');

                    // 检查思考活跃度 (如“思考中 00:15”)
                    let isThinkingActive = false;
                    const thinkElems = Array.from(document.querySelectorAll('[class*="thinking"], [class*="thought"]'));
                    for (const el of thinkElems) {
                        const txt = (el.innerText || '').trim();
                        if (txt.includes('思考中') || txt.includes('Thinking...')) {
                            isThinkingActive = true;
                            break;
                        }
                    }

                    // 检查大模型完成的双重铁证：
                    // 铁证 1：消息底部渲染出了【复制】或【重新生成/重新回答】操作工具栏
                    for (const btn of allButtons) {
                        const label = (btn.getAttribute('aria-label') || btn.innerText || btn.title || '').trim().toLowerCase();
                        if (label.includes('复制') || label.includes('copy') || label.includes('重新生成') || label.includes('重新回答') || label.includes('regenerate')) {
                            const rect = btn.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) {
                                hasMessageActionTools = true;
                                break;
                            }
                        }
                    }

                    // 铁证 2：输入框右侧恢复为【发送状态】(向上箭头或发送按钮)
                    for (const btn of allButtons) {
                        const label = (btn.getAttribute('aria-label') || btn.innerText || btn.title || '').trim().toLowerCase();
                        const isSendWord = label.includes('发送') || label.includes('send');
                        const hasArrowSvg = !!btn.querySelector('path, polygon, polyline');
                        const rect = btn.getBoundingClientRect();

                        if (rect.top > window.innerHeight * 0.3 && (isSendWord || (hasArrowSvg && !label.includes('思考') && !label.includes('上传')))) {
                            hasSendButton = true;
                            break;
                        }
                    }

                    // 严格综合裁决：
                    // 1. 只要存在停止按钮、或有打字光标、或思考活跃中 -> 100% 确定仍在生成中！
                    if (hasStopButton || hasTypingCursor || isThinkingActive) {
                        return true;
                    }

                    // 2. 只要停止按钮与光标消失，且满足【消息出现复制/重新生成栏】或【输入框发送按钮恢复】任一铁证 -> 100% 判定推导彻底完成！
                    if (hasMessageActionTools || hasSendButton) {
                        return false;
                    }

                    // 3. 过渡中间态：既无停止按钮也无工具栏/发送按钮 -> 保守判定仍在生成过渡态！
                    return true;
                }

                // 双轨保障状态机：绝不死锁，只要网页端出了结果立即捕获！
                let lastStableLength = 0;
                let stableCount = 0;
                let finishReported = false;

                function checkStatus() {
                    // 1. 异常与验证码拦截
                    if (document.querySelector('.cf-turnstile, iframe[src*="cloudflare"], iframe[src*="turnstile"]')) {
                        window.AndroidBridge.onCaptchaDetected();
                        return;
                    }
                    const errorElem = document.querySelector('.ds-error, div[class*="error-message"], [class*="alert-danger"]');
                    if (errorElem && errorElem.innerText && errorElem.innerText.trim()) {
                        window.AndroidBridge.onError(errorElem.innerText.trim());
                        return;
                    }

                    // 2. 状态判定与内容提取
                    const isGenerating = detectGenerationStatus();
                    const data = extractLatestTurnData();
                    const hasContent = !!(data && (data.markdown || data.html));

                    if (isGenerating) {
                        finishReported = false;
                        stableCount = 0;
                        const len = data ? (data.markdown || "").length : 0;
                        const msg = len > 0 ? ("📝 正在输出公式与分步解答 (已生成 " + len + " 字)...") : "🧠 官方 R1 正在深度思考中...";
                        window.AndroidBridge.onGeneratingProgress(msg);
                        return;
                    }

                    // 3. 真正完成判定：非生成态(发送按钮已就绪) + 内容非空 + 连续 3 次检测(1.2秒以上)稳定不变！
                    if (hasContent) {
                        const currentLen = (data.markdown || "").length + (data.html || "").length;
                        if (currentLen === lastStableLength) {
                            stableCount++;
                        } else {
                            lastStableLength = currentLen;
                            stableCount = 1;
                        }

                        // 内容连续稳定且发送按钮就绪，直接派发最终完整结果！
                        if (stableCount >= 3 && !finishReported) {
                            finishReported = true;
                            window.AndroidBridge.onSolveFinished(data.thinking, data.markdown, data.html, data.plainText);
                        }
                    }
                }

                // 暴露主动强制提取接口供 Native 端高频轮询调用 (返回完整状态与提取结果)
                window.__ds_force_extract = function() {
                    const isGenerating = detectGenerationStatus();
                    const data = extractLatestTurnData();
                    const hasContent = !!(data && (data.markdown || data.html));

                    const result = {
                        isGenerating: isGenerating,
                        thinking: (data && data.thinking) || "",
                        markdown: (data && data.markdown) || "",
                        html: (data && data.html) || "",
                        plainText: (data && data.plainText) || ""
                    };

                    // 仅当非生成态(发送按钮已就绪)、内容有效且连续稳定时才上报完成
                    if (!isGenerating && hasContent && stableCount >= 3) {
                        try {
                            window.AndroidBridge.onSolveFinished(result.thinking, result.markdown, result.html, result.plainText);
                        } catch(e) {}
                    }
                    return JSON.stringify(result);
                };

                // 1. DOM 变动监听
                const observer = new MutationObserver(checkStatus);
                observer.observe(document.body, { childList: true, subtree: true, characterData: true });

                // 2. 每 400ms 定时双保险轮询 (防止大模型停产后 DOM 静止导致漏检)
                if (window.__ds_poll_timer) clearInterval(window.__ds_poll_timer);
                window.__ds_poll_timer = setInterval(checkStatus, 400);
            })();
        """.trimIndent()
        mainHandler.post {
            webView.resumeTimers()
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 统一处理推导完成 (UI 刷新与状态闭环，供 JS 回调和 Native 轮询双向复用)
     */
    fun handleSolveFinished(thinking: String, markdown: String, html: String, plainText: String) {
        if (_solveStatus.value == SolveStatus.SOLVING && (markdown.isNotBlank() || html.isNotBlank())) {
            solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            solveTimeoutRunnable = null
            _currentThinking.value = thinking
            _currentAnswer.value = markdown
            _currentAnswerHtml.value = html
            _currentPlainText.value = plainText.ifBlank { markdown }
            _solveStatus.value = SolveStatus.SUCCESS
            _stepDiagnosis.value = "推导完成"
        }
    }

    /**
     * 启动 Native 强劲主动轮询机制 (每 1.5 秒直接抓取 JSON 状态，绝不盲等，持续 3 分钟)
     */
    private fun startActivePolling() {
        solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        var pollCount = 0
        val maxPollCount = 120 // 120 次 * 1.5秒 = 180秒 (3分钟)，完全覆盖大模型深度思考与输出耗时

        val pollTask = object : Runnable {
            override fun run() {
                if (_solveStatus.value != SolveStatus.SOLVING) {
                    return
                }
                pollCount++

                mainHandler.post {
                    webView.resumeTimers()
                }

                val extractJs = """
                    (function() {
                        try {
                            if (window.__ds_force_extract) {
                                return window.__ds_force_extract();
                            }
                        } catch(e) {}
                        return "";
                    })()
                """.trimIndent()

                webView.evaluateJavascript(extractJs) { rawResult ->
                    if (_solveStatus.value != SolveStatus.SOLVING) return@evaluateJavascript
                    if (!rawResult.isNullOrBlank() && rawResult != "null" && rawResult != "\"\"") {
                        try {
                            val parsed = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                                JSONTokener(rawResult).nextValue().toString()
                            } else {
                                rawResult
                            }
                            val obj = JSONObject(parsed)
                            val isGenerating = obj.optBoolean("isGenerating", false)
                            val thinking = obj.optString("thinking", "")
                            val markdown = obj.optString("markdown", "")
                            val html = obj.optString("html", "")
                            val plainText = obj.optString("plainText", "")

                            if (isGenerating) {
                                // 实时渐进式流式输出预览：即使大模型仍在生成，也将已输出的内容实时刷给界面！
                                if (markdown.isNotBlank()) {
                                    _currentAnswer.value = markdown
                                    _currentAnswerHtml.value = html
                                    _currentPlainText.value = plainText
                                }
                                if (thinking.isNotBlank()) {
                                    _currentThinking.value = thinking
                                }
                                _stepDiagnosis.value = if (thinking.isNotBlank() && markdown.isBlank()) {
                                    "🧠 官方 R1 正在深度思考中..."
                                } else {
                                    "📝 正在输出公式与规范解答（已输出 ${markdown.length} 字）..."
                                }
                            } else if (markdown.isNotBlank() || html.isNotBlank()) {
                                mainHandler.post {
                                    handleSolveFinished(thinking, markdown, html, plainText)
                                }
                                return@evaluateJavascript
                            }
                        } catch (e: Exception) {}
                    }
                }

                if (pollCount >= maxPollCount) {
                    if (_currentAnswer.value.isBlank()) {
                        _stepDiagnosis.value = "推导等待时间较长，若触发人机验证请点击【🌐 官方网页】"
                    }
                } else {
                    mainHandler.postDelayed(this, 1500)
                }
            }
        }
        solveTimeoutRunnable = pollTask
        mainHandler.postDelayed(pollTask, 1500)
    }

    /**
     * 发送题目求解 (支持图片直发 + 定制 Prompt + R1 深度思考联动 + 状态打点 + 复合点击/回车重试)
     */
    fun solveProblem(
        rawProblemText: String,
        imageBase64: String?,
        enableR1: Boolean = true
    ) {
        if (_authStatus.value != AuthStatus.LOGGED_IN) {
            _errorMessage.value = "请先登录 DeepSeek 账号"
            return
        }

        // 新题目开始：清空旧的追问历史和答案，开辟纯净上下文！
        _qaHistory.value = emptyList()
        _currentThinking.value = ""
        _currentAnswer.value = ""
        _currentAnswerHtml.value = ""
        _currentPlainText.value = ""
        lastSentQuestion = rawProblemText.ifBlank { "【图片题目】" }
        _solveStatus.value = SolveStatus.SOLVING
        _errorMessage.value = null
        _stepDiagnosis.value = "正在初始化题目会话..."

        // 启动 Native 端高频主动抓取机制 (每 1.5 秒主动探测一次)
        startActivePolling()

        val promptText = buildProblemPrompt(rawProblemText)

        val js = """
            (async function() {
                try {
                    // 发送新题目：将当前页面已存在的历史内容全部归档锁定，确保本次推导从输入点到末尾全量提取、零混淆！
                    document.querySelectorAll('.ds-markdown, [class*="thinking"], [class*="thought"]').forEach(el => {
                        el.setAttribute('data-ds-history', 'true');
                    });

                    function logStep(msg) {
                        try { window.AndroidBridge.onStep(msg); } catch(e) {}
                    }

                    function dispatchMobileClick(element) {
                        if (!element) return;
                        const rect = element.getBoundingClientRect();
                        const x = rect.left + rect.width / 2;
                        const y = rect.top + rect.height / 2;
                        
                        try {
                            const touch = new Touch({
                                identifier: Date.now(),
                                target: element,
                                clientX: x,
                                clientY: y,
                                pageX: x,
                                pageY: y
                            });
                            element.dispatchEvent(new TouchEvent('touchstart', { touches: [touch], targetTouches: [touch], changedTouches: [touch], bubbles: true, cancelable: true }));
                            element.dispatchEvent(new TouchEvent('touchend', { touches: [touch], targetTouches: [touch], changedTouches: [touch], bubbles: true, cancelable: true }));
                        } catch(e) {}

                        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(name => {
                            try {
                                element.dispatchEvent(new MouseEvent(name, { clientX: x, clientY: y, bubbles: true, cancelable: true, view: window }));
                            } catch(e) {}
                        });
                        try { element.click(); } catch(e) {}
                    }

                    function dispatchEnter(elem) {
                        if (!elem) return;
                        try { elem.focus(); } catch(e) {}
                        ['keydown', 'keypress', 'keyup'].forEach(name => {
                            try {
                                elem.dispatchEvent(new KeyboardEvent(name, {
                                    bubbles: true,
                                    cancelable: true,
                                    key: 'Enter',
                                    code: 'Enter',
                                    keyCode: 13,
                                    which: 13,
                                    view: window
                                }));
                            } catch(e) {}
                        });
                    }

                    // 1. 深度思考 (R1) 模式联动与强制开启
                    logStep("正在对齐 R1 深度思考模式...");
                    function getR1Button() {
                        const candidates = Array.from(document.querySelectorAll('button, div[role="button"], div, span'));
                        for (const el of candidates) {
                            const txt = (el.innerText || el.getAttribute('aria-label') || '').trim();
                            if ((txt === '深度思考' || txt === '深度思考 (R1)' || txt === 'DeepThink (R1)' || txt.includes('深度思考')) && el.children.length <= 3) {
                                return el.closest('button, div[role="button"]') || el;
                            }
                        }
                        return document.querySelector('button[aria-label*="深度思考"]');
                    }

                    function isR1Active(btn) {
                        if (!btn) return false;
                        if (btn.getAttribute('aria-checked') === 'true' || btn.getAttribute('aria-pressed') === 'true') return true;
                        const cls = (btn.className || '').toString();
                        if (cls.includes('active') || cls.includes('checked') || cls.includes('primary') || cls.includes('selected') || cls.includes('on')) return true;
                        const style = window.getComputedStyle(btn);
                        const bg = style.backgroundColor || '';
                        if (bg.includes('77') || bg.includes('rgb(77') || bg.includes('rgba(77') || bg.includes('blue')) return true;
                        return false;
                    }

                    const r1Btn = getR1Button();
                    if (r1Btn) {
                        const active = isR1Active(r1Btn);
                        const wantActive = ${enableR1};
                        if (active !== wantActive) {
                            dispatchMobileClick(r1Btn);
                            await new Promise(r => setTimeout(r, 200));
                        }
                    }

                    // 2. 注入图片并智能等待上传完成态
                    ${if (!imageBase64.isNullOrBlank()) """
                    logStep("正在注入题目原图...");
                    try {
                        const res = await fetch("${imageBase64}");
                        const blob = await res.blob();
                        const file = new File([blob], "problem_image.jpg", { type: "image/jpeg" });
                        const fileInput = document.querySelector('input[type="file"][accept*="image"]') 
                                       || document.querySelector('input[type="file"]');
                        if (fileInput) {
                            const dataTransfer = new DataTransfer();
                            dataTransfer.items.add(file);
                            fileInput.files = dataTransfer.files;
                            fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                            logStep("原图已注入，正在等待官方解析与上传就绪...");
                        }
                    } catch(err) {
                        logStep("图片注入跳过: " + err.message);
                    }
                    """ else ""}

                    // 3. 填入定制 Prompt
                    let textarea = document.querySelector('textarea') 
                                || document.querySelector('div[contenteditable="true"]');
                    if (!textarea) {
                        window.AndroidBridge.onError("未能定位输入框，请点击上方【🌐 官方网页】直接输入");
                        return;
                    }

                    textarea.focus();
                    if (textarea.tagName === 'TEXTAREA') {
                        const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value")?.set;
                        if (nativeSetter) {
                            nativeSetter.call(textarea, ${JSONObject.quote(promptText)});
                        } else {
                            textarea.value = ${JSONObject.quote(promptText)};
                        }
                    } else {
                        textarea.innerText = ${JSONObject.quote(promptText)};
                    }
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));

                    // 4. 定位发送按钮 (严格在输入框区域内寻找，绝不误触顶部菜单)
                    function findSendButton(textareaElem) {
                        // 策略 A：从 textarea 向上遍历父级容器寻找右下角带 SVG 的按钮
                        let parent = textareaElem.parentElement;
                        while (parent && parent !== document.body) {
                            const btns = Array.from(parent.querySelectorAll('button, div[role="button"]'));
                            for (let i = btns.length - 1; i >= 0; i--) {
                                const b = btns[i];
                                const text = (b.innerText || b.getAttribute('aria-label') || '').trim();
                                if (text.includes('思考') || text.includes('联网') || text.includes('上传') || text.includes('搜索')) continue;
                                if (b.querySelector('svg')) {
                                    return b;
                                }
                            }
                            if (btns.length >= 2) break;
                            parent = parent.parentElement;
                        }

                        // 策略 B：全局底部带 SVG 按钮
                        const allBtns = Array.from(document.querySelectorAll('button, div[role="button"]'));
                        for (let i = allBtns.length - 1; i >= 0; i--) {
                            const b = allBtns[i];
                            const rect = b.getBoundingClientRect();
                            if (rect.top > window.innerHeight * 0.4 && b.querySelector('svg')) {
                                const text = (b.innerText || b.getAttribute('aria-label') || '').trim();
                                if (!text.includes('思考') && !text.includes('联网') && !text.includes('上传')) {
                                    return b;
                                }
                            }
                        }
                        return null;
                    }

                    // 5. 智能等待图片上传完成态与发送就绪 (最长等待 12 秒)
                    const hasImage = ${!imageBase64.isNullOrBlank()};
                    const maxWait = hasImage ? 12000 : 2500;
                    const startTime = Date.now();
                    logStep(hasImage ? "正在等待题目图片上传解析完成..." : "正在准备自动发送...");

                    let sendBtn = null;
                    while (Date.now() - startTime < maxWait) {
                        sendBtn = findSendButton(textarea);
                        if (sendBtn) {
                            const isDisabled = sendBtn.hasAttribute('disabled') 
                                            || sendBtn.getAttribute('aria-disabled') === 'true'
                                            || (sendBtn.className || '').includes('disabled');
                            if (!isDisabled) {
                                break;
                            }
                        }
                        await new Promise(r => setTimeout(r, 350));
                    }

                    // 6. 执行多次移动端点击与回车触发
                    logStep("图片与题目已就绪，正在自动触发发送...");
                    let attempts = 0;
                    const timer = setInterval(() => {
                        attempts++;
                        const currentBtn = findSendButton(textarea);
                        const isStopBtn = !!document.querySelector('button[aria-label*="停止"], [class*="stop-btn"], div[role="button"][aria-label*="停止"]');
                        const isCleared = textarea && textarea.value && textarea.value.trim() === '';

                        if (isStopBtn || isCleared || attempts >= 5) {
                            clearInterval(timer);
                            logStep("已成功触发发送，官方大模型正在推导...");
                            return;
                        }

                        if (currentBtn && currentBtn.getAttribute('aria-disabled') !== 'true') {
                            dispatchMobileClick(currentBtn);
                        }
                        dispatchEnter(textarea);
                    }, 300);

                } catch (e) {
                    window.AndroidBridge.onError("发送题目发生异常: " + e.message);
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView.resumeTimers()
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 手动停止推导
     */
    fun stopSolving() {
        solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.post {
            val js = """
                (function() {
                    const stopBtn = document.querySelector('button[aria-label*="停止"], [class*="stop-btn"], div[role="button"][aria-label*="停止"]');
                    if (stopBtn) stopBtn.click();
                    if (window.__ds_poll_timer) {
                        clearInterval(window.__ds_poll_timer);
                        window.__ds_poll_timer = null;
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            _solveStatus.value = if (_currentAnswer.value.isNotBlank()) SolveStatus.SUCCESS else SolveStatus.IDLE
            _stepDiagnosis.value = "推导已手动停止"
        }
    }

    /**
     * 追加多轮追问 (仅在追问时归档上一问，保留会话上下文)
     */
    fun sendFollowUp(followUpText: String) {
        if (followUpText.isBlank()) return
        
        // 归档上一问 (严格去重，仅在历史中未记录该问题时归档，杜绝追问内容重复)
        if (_currentAnswer.value.isNotBlank() && lastSentQuestion.isNotBlank()) {
            val existing = _qaHistory.value.toMutableList()
            if (existing.none { it.question == lastSentQuestion }) {
                existing.add(
                    QaHistoryItem(
                        question = lastSentQuestion,
                        thinking = _currentThinking.value,
                        answer = _currentAnswer.value,
                        answerHtml = _currentAnswerHtml.value,
                        plainText = _currentPlainText.value
                    )
                )
                _qaHistory.value = existing
            }
        }

        lastSentQuestion = followUpText
        _currentThinking.value = ""
        _currentAnswer.value = ""
        _currentAnswerHtml.value = ""
        _currentPlainText.value = ""
        _solveStatus.value = SolveStatus.SOLVING
        _stepDiagnosis.value = "正在追加追问..."

        // 启动 Native 端高频主动抓取机制 (每 1.5 秒主动探测一次)
        startActivePolling()

        val js = """
            (function() {
                // 追问发出前：将上一问及其之前的所有内容全部锁定归档为历史！
                document.querySelectorAll('.ds-markdown, [class*="thinking"], [class*="thought"]').forEach(el => {
                    el.setAttribute('data-ds-history', 'true');
                });

                const textarea = document.querySelector('textarea') || document.querySelector('div[contenteditable="true"]');
                if (textarea) {
                    textarea.focus();
                    if (textarea.tagName === 'TEXTAREA') {
                        const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value")?.set;
                        if (nativeSetter) {
                            nativeSetter.call(textarea, ${JSONObject.quote(followUpText)});
                        } else {
                            textarea.value = ${JSONObject.quote(followUpText)};
                        }
                    } else {
                        textarea.innerText = ${JSONObject.quote(followUpText)};
                    }
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));

                    setTimeout(() => {
                        const sendBtn = document.querySelector('button[type="submit"], div[role="button"][aria-label*="发送"], button[aria-label*="发送"], [class*="send-btn"]');
                        if (sendBtn && sendBtn.getAttribute('aria-disabled') !== 'true') {
                            sendBtn.click();
                        } else {
                            textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                        }
                    }, 300);
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView.resumeTimers()
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 开启新题目 (彻底新建对话 session，100% 隔离上下文与历史记录)
     */
    fun startNewChat() {
        mainHandler.post {
            // 1. 彻底清空 Native 端的所有状态、答案与追问历史
            solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            solveTimeoutRunnable = null
            _qaHistory.value = emptyList()
            _currentThinking.value = ""
            _currentAnswer.value = ""
            _currentAnswerHtml.value = ""
            _currentPlainText.value = ""
            lastSentQuestion = ""
            _solveStatus.value = SolveStatus.IDLE
            _errorMessage.value = null
            _stepDiagnosis.value = "已开辟全新题目，请导入或输入新题目"

            // 2. 网页端彻底开启新会话 (双重机制：先尝试 SPA 内点击新对话，再通过原生 loadUrl 彻底重置根路由)
            val js = """
                (function() {
                    try {
                        const candidates = Array.from(document.querySelectorAll('a, button, div[role="button"], span'));
                        for (const el of candidates) {
                            const text = (el.innerText || el.getAttribute('aria-label') || '').trim();
                            if (text === '开启新对话' || text === '新建对话' || text === '新对话' || text === 'New Chat') {
                                el.click();
                                return true;
                            }
                        }
                    } catch(e) {}
                    return false;
                })()
            """.trimIndent()

            webView.evaluateJavascript(js) { clicked ->
                // 如果未能通过 DOM 点击进入新对话，直接通过原生 loadUrl 重新加载干净主站路由！
                if (clicked != "true" && clicked != "\"true\"") {
                    webView.loadUrl(DEEPSEEK_HOME_URL)
                }
            }
        }
    }

    /**
     * 退出当前账号并清除缓存
     */
    fun logoutAndClear() {
        mainHandler.post {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            webView.clearCache(true)
            webView.clearHistory()
            _authStatus.value = AuthStatus.NOT_LOGGED_IN
            _qaHistory.value = emptyList()
            webView.loadUrl(DEEPSEEK_HOME_URL)
        }
    }

    fun dismissCaptcha() {
        _showCaptchaDialog.value = false
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webView.apply {
                (parent as? ViewGroup)?.removeView(this)
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
            }
        } catch (e: Exception) {}
    }

    private fun buildProblemPrompt(problemText: String): String {
        return """
            【角色定位】：你是一位资深理科金牌导师。
            【解答规范】：请开启深度推导，按照以下三步结构为学生规范剖析：
            1. 🔍【核心考点与破题题眼】：直观指出本题考查的核心定理/公式，说明第一步为什么这么切入；
            2. 📝【详尽分步解答】：每一步均写出严谨的代数/几何推导与定理依据，公式使用规范排版；
            3. 💡【易错陷阱与举一反三】：标出最容易丢分的坑点，并总结秒杀同类题的关键技巧。
            
            【题目内容】：
            $problemText
        """.trimIndent()
    }

    private fun buildImageInjectScript(base64: String): String {
        return """
            try {
                fetch("$base64")
                    .then(res => res.blob())
                    .then(blob => {
                        const file = new File([blob], "problem_image.jpg", { type: "image/jpeg" });
                        const fileInput = document.querySelector('input[type="file"][accept*="image"]');
                        if (fileInput) {
                            const dataTransfer = new DataTransfer();
                            dataTransfer.items.add(file);
                            fileInput.files = dataTransfer.files;
                            fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    });
            } catch(e) {}
        """.trimIndent()
    }

    /**
     * 与 JS 桥接的原生回调
     */
    inner class AndroidJsBridge {
        @JavascriptInterface
        fun onAuthResult(isLoggedIn: Boolean) {
            _authStatus.value = if (isLoggedIn) AuthStatus.LOGGED_IN else AuthStatus.NOT_LOGGED_IN
        }

        @JavascriptInterface
        fun onCaptchaDetected() {
            _showCaptchaDialog.value = true
        }

        @JavascriptInterface
        fun onError(msg: String) {
            solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            _errorMessage.value = msg
            _solveStatus.value = SolveStatus.ERROR
            _stepDiagnosis.value = "推导异常: $msg"
        }

        @JavascriptInterface
        fun onStep(msg: String) {
            _stepDiagnosis.value = msg
        }

        @JavascriptInterface
        fun onGeneratingProgress(msg: String) {
            // 严禁 JS 擅自篡权：只有当 Native 主动发起了题目推导 (SOLVING) 时，才更新进度与状态
            if (_solveStatus.value == SolveStatus.SOLVING) {
                _stepDiagnosis.value = msg
            }
        }

        @JavascriptInterface
        fun onSolveFinished(thinking: String, markdown: String, html: String, plainText: String) {
            mainHandler.post {
                handleSolveFinished(thinking, markdown, html, plainText)
            }
        }

        @JavascriptInterface
        fun onContentUpdate(thinking: String, answer: String, isFinished: Boolean) {
            solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            _currentThinking.value = thinking
            _currentAnswer.value = answer
            if (isFinished && answer.isNotBlank()) {
                _solveStatus.value = SolveStatus.SUCCESS
                _stepDiagnosis.value = "推导完成"
            } else {
                _solveStatus.value = SolveStatus.SOLVING
                _stepDiagnosis.value = if (thinking.isNotBlank() && answer.isBlank()) "🧠 官方 R1 正在深度思考中..." else "📝 正在输出规范解答..."
            }
        }
    }
}
