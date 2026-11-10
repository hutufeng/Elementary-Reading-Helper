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
    val answer: String
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
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 注入 DOM 监听与捕获脚本 (符合 W3C 标准，避免非法选择器崩溃)
     */
    private fun injectObserverScript() {
        val js = """
            (function() {
                if (window.__ds_observer_injected) return;
                window.__ds_observer_injected = true;

                // 监听内容产出
                const observer = new MutationObserver(() => {
                    // 1. 检测验证码
                    if (document.querySelector('.cf-turnstile, iframe[src*="cloudflare"], iframe[src*="turnstile"]')) {
                        window.AndroidBridge.onCaptchaDetected();
                        return;
                    }

                    // 2. 检测官方繁忙或错误提示
                    const errorElem = document.querySelector('.ds-error, div[class*="error-message"], [class*="alert-danger"]');
                    if (errorElem && errorElem.innerText && errorElem.innerText.trim()) {
                        window.AndroidBridge.onError(errorElem.innerText.trim());
                    }

                    // 3. 获取所有问答轮次的消息气泡
                    const bubbles = document.querySelectorAll('.ds-markdown, [class*="markdown"], div[class*="chat-message"]');
                    if (bubbles.length > 0) {
                        const lastBubble = bubbles[bubbles.length - 1];
                        
                        // 寻找思考过程
                        let thinking = "";
                        const thinkElem = lastBubble.querySelector('[class*="thinking"], [class*="thought"]');
                        if (thinkElem) {
                            thinking = thinkElem.innerText || "";
                        }

                        // 获取正式解答纯文本
                        const answer = lastBubble.innerText || "";

                        // 判断是否正在生成中 (通过打字光标或停止生成按钮判断)
                        const isGenerating = !!document.querySelector('.ds-typing, [class*="loading"], [class*="generating"], button[aria-label*="停止"]');

                        window.AndroidBridge.onContentUpdate(thinking, answer, !isGenerating);
                    }
                });

                observer.observe(document.body, { childList: true, subtree: true, characterData: true });
            })();
        """.trimIndent()
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 发送题目求解 (支持图片直发 + 定制 Prompt + R1 深度思考联动 + 状态打点)
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

        lastSentQuestion = rawProblemText.ifBlank { "【图片题目】" }
        _solveStatus.value = SolveStatus.SOLVING
        _currentThinking.value = ""
        _currentAnswer.value = ""
        _errorMessage.value = null
        _stepDiagnosis.value = "正在初始化题目并连接网页输入框..."

        // 取消上一轮可能的超时任务并启动新一轮诊断监控
        solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        solveTimeoutRunnable = Runnable {
            if (_solveStatus.value == SolveStatus.SOLVING && _currentAnswer.value.isBlank()) {
                _stepDiagnosis.value = "网页端响应较慢，可能触发人机验证，请点击【🌐 官方网页】查看"
            }
        }.also { mainHandler.postDelayed(it, 16000) }

        val promptText = buildProblemPrompt(rawProblemText)

        val js = """
            (async function() {
                try {
                    function logStep(msg) {
                        try { window.AndroidBridge.onStep(msg); } catch(e) {}
                    }

                    function findBtnByText(selector, text) {
                        const elements = document.querySelectorAll(selector);
                        for (let i = 0; i < elements.length; i++) {
                            if (elements[i].innerText && elements[i].innerText.includes(text)) return elements[i];
                        }
                        return null;
                    }

                    logStep("正在对齐 R1 深度思考模式...");
                    // 1. 确保 R1 深度思考按钮状态对齐
                    const r1Btn = document.querySelector('button[aria-label*="深度思考"]') 
                               || findBtnByText('button', '深度思考') 
                               || findBtnByText('div[role="button"]', '深度思考');

                    if (r1Btn) {
                        const isChecked = r1Btn.getAttribute('aria-checked') === 'true' || r1Btn.classList.contains('active');
                        const wantChecked = ${enableR1};
                        if (isChecked !== wantChecked) {
                            r1Btn.click();
                        }
                    }

                    // 2. 如果存在图片，模拟注入上传
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
                            await new Promise(r => setTimeout(r, 600));
                        }
                    } catch(err) {
                        logStep("图片注入跳过: " + err.message);
                    }
                    """ else ""}

                    // 3. 填入定制 Prompt
                    logStep("正在填入解题提示词...");
                    let textarea = document.querySelector('textarea') 
                                || document.querySelector('div[contenteditable="true"]');
                    if (!textarea) {
                        window.AndroidBridge.onError("未能定位输入框，请点击上方【🌐 官方网页】直接输入");
                        return;
                    }

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

                    await new Promise(r => setTimeout(r, 400));

                    // 4. 定位发送按钮并触发
                    logStep("正在检索并点击发送按钮...");
                    function getSendButton() {
                        const direct = document.querySelector('button[type="submit"], div[role="button"][aria-label*="发送"], button[aria-label*="发送"], [class*="send-btn"]');
                        if (direct) return direct;
                        
                        // 寻找包含 SVG 箭头的按钮
                        const allButtons = Array.from(document.querySelectorAll('button, div[role="button"]'));
                        for (let b of allButtons) {
                            if (b.querySelector('svg')) return b;
                        }
                        return null;
                    }

                    const sendBtn = getSendButton();
                    if (sendBtn) {
                        if (sendBtn.disabled) {
                            await new Promise(r => setTimeout(r, 600));
                        }
                        sendBtn.click();
                        logStep("已成功触发发送，等待官方大模型响应...");
                    } else {
                        textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
                        logStep("已发送键盘回车指令，若无响应请切换至网页端确认");
                    }

                } catch (e) {
                    window.AndroidBridge.onError("发送题目发生异常: " + e.message);
                }
            })();
        """.trimIndent()

        mainHandler.post {
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
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            _solveStatus.value = if (_currentAnswer.value.isNotBlank()) SolveStatus.SUCCESS else SolveStatus.IDLE
            _stepDiagnosis.value = "推导已手动停止"
        }
    }

    /**
     * 追加多轮追问
     */
    fun sendFollowUp(followUpText: String) {
        if (followUpText.isBlank()) return
        lastSentQuestion = followUpText
        _solveStatus.value = SolveStatus.SOLVING

        val js = """
            (function() {
                const textarea = document.querySelector('textarea');
                if (textarea) {
                    const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                    nativeSetter.call(textarea, ${JSONObject.quote(followUpText)});
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    setTimeout(() => {
                        const sendBtn = document.querySelector('button[type="submit"], div[role="button"][aria-label*="发送"], [class*="send-btn"]');
                        if (sendBtn && !sendBtn.disabled) {
                            sendBtn.click();
                        } else {
                            textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
                        }
                    }, 350);
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 开启新题目 (新建对话 session，隔离上下文)
     */
    fun startNewChat() {
        val js = """
            (function() {
                function findBtnByText(selector, text) {
                    const elements = document.querySelectorAll(selector);
                    for (let i = 0; i < elements.length; i++) {
                        if (elements[i].innerText && elements[i].innerText.includes(text)) return elements[i];
                    }
                    return null;
                }

                const newChatBtn = document.querySelector('a[href*="/chat"]') 
                                || findBtnByText('button', '新对话') 
                                || findBtnByText('div[role="button"]', '新建对话');

                if (newChatBtn) {
                    newChatBtn.click();
                } else {
                    window.location.href = "https://chat.deepseek.com";
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView.evaluateJavascript(js, null)
            _solveStatus.value = SolveStatus.IDLE
            _currentThinking.value = ""
            _currentAnswer.value = ""
            _errorMessage.value = null
            _qaHistory.value = emptyList()
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
        fun onContentUpdate(thinking: String, answer: String, isFinished: Boolean) {
            solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            _currentThinking.value = thinking
            _currentAnswer.value = answer
            if (isFinished && answer.isNotBlank()) {
                _solveStatus.value = SolveStatus.SUCCESS
                _stepDiagnosis.value = "推导完成"
                val existing = _qaHistory.value.toMutableList()
                if (existing.none { it.question == lastSentQuestion && it.answer == answer }) {
                    existing.add(QaHistoryItem(lastSentQuestion, thinking, answer))
                    _qaHistory.value = existing
                }
            } else {
                _solveStatus.value = SolveStatus.SOLVING
                _stepDiagnosis.value = if (thinking.isNotBlank() && answer.isBlank()) "🧠 官方 R1 正在深度思考中..." else "📝 正在输出解答..."
            }
        }
    }
}
