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
     * 注入 DOM 监听与捕获脚本 (符合 W3C 标准，支持全量提取解题所有段落)
     */
    private fun injectObserverScript() {
        val js = """
            (function() {
                if (window.__ds_observer_injected) return;
                window.__ds_observer_injected = true;

                function extractFullAiResponse() {
                    const allMarkdowns = Array.from(document.querySelectorAll('.ds-markdown'));
                    if (allMarkdowns.length === 0) return null;

                    // 找到最后一个 markdown 所在的整轮消息容器
                    const lastMd = allMarkdowns[allMarkdowns.length - 1];
                    let messageRoot = lastMd;
                    let curr = lastMd.parentElement;
                    while (curr && curr !== document.body) {
                        if (curr.querySelectorAll('.ds-markdown').length > 1 || curr.querySelector('[class*="thinking"], [class*="thought"]')) {
                            messageRoot = curr;
                        }
                        if (curr.getAttribute('role') === 'log' || curr.id === 'chat-container') break;
                        curr = curr.parentElement;
                    }

                    // 1. 提取思考过程
                    let thinking = "";
                    const thinkElem = messageRoot.querySelector('[class*="thinking"], [class*="thought"]');
                    if (thinkElem) {
                        thinking = thinkElem.innerText || "";
                    }

                    // 2. 提取完整解答：将所有非思考容器的 markdown 块全部提取拼接，绝不漏掉任何一个步骤！
                    const answerMds = Array.from(messageRoot.querySelectorAll('.ds-markdown')).filter(md => {
                        return !thinkElem || !thinkElem.contains(md);
                    });

                    let fullAnswer = "";
                    if (answerMds.length > 0) {
                        fullAnswer = answerMds.map(md => (md.innerText || "").trim()).filter(t => t.length > 0).join("\n\n");
                    } else {
                        const clone = messageRoot.cloneNode(true);
                        const cloneThink = clone.querySelector('[class*="thinking"], [class*="thought"]');
                        if (cloneThink) cloneThink.remove();
                        const cloneBtns = clone.querySelectorAll('button, [class*="action"]');
                        cloneBtns.forEach(b => b.remove());
                        fullAnswer = clone.innerText || "";
                    }

                    return {
                        thinking: thinking.trim(),
                        answer: fullAnswer.trim()
                    };
                }

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

                    // 3. 提取全量问答内容
                    const res = extractFullAiResponse();
                    if (res && (res.thinking || res.answer)) {
                        const isGenerating = !!document.querySelector('.ds-typing, [class*="loading"], [class*="generating"], button[aria-label*="停止"]');
                        window.AndroidBridge.onContentUpdate(res.thinking, res.answer, !isGenerating);
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
        lastSentQuestion = rawProblemText.ifBlank { "【图片题目】" }
        _solveStatus.value = SolveStatus.SOLVING
        _errorMessage.value = null
        _stepDiagnosis.value = "正在初始化题目会话..."

        // 取消上一轮可能的超时任务并启动新一轮诊断监控
        solveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        solveTimeoutRunnable = Runnable {
            if (_solveStatus.value == SolveStatus.SOLVING && _currentAnswer.value.isBlank()) {
                _stepDiagnosis.value = "网页端响应较慢，可能触发人机验证，请点击【🌐 官方网页】查看"
            }
        }.also { mainHandler.postDelayed(it, 20000) }

        val promptText = buildProblemPrompt(rawProblemText)

        val js = """
            (async function() {
                try {
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
     * 追加多轮追问 (仅在追问时归档上一问，保留会话上下文)
     */
    fun sendFollowUp(followUpText: String) {
        if (followUpText.isBlank()) return
        
        // 归档上一问
        if (_currentAnswer.value.isNotBlank()) {
            val existing = _qaHistory.value.toMutableList()
            existing.add(QaHistoryItem(lastSentQuestion, _currentThinking.value, _currentAnswer.value))
            _qaHistory.value = existing
        }

        lastSentQuestion = followUpText
        _currentThinking.value = ""
        _currentAnswer.value = ""
        _solveStatus.value = SolveStatus.SOLVING
        _stepDiagnosis.value = "正在追加追问..."

        val js = """
            (function() {
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
            } else {
                _solveStatus.value = SolveStatus.SOLVING
                _stepDiagnosis.value = if (thinking.isNotBlank() && answer.isBlank()) "🧠 官方 R1 正在深度思考中..." else "📝 正在输出规范解答..."
            }
        }
    }
}
