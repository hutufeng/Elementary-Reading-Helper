package com.assistant.reading.ui.solver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.assistant.reading.core.ai.ZhipuAiClient
import com.assistant.reading.core.bridge.AuthStatus
import com.assistant.reading.core.bridge.DeepSeekBridgeManager
import com.assistant.reading.core.bridge.SolveStatus
import com.assistant.reading.core.voice.PlaybackState
import com.assistant.reading.core.voice.VoiceManager
import java.util.Locale

/**
 * 专为语文阅读伴读定制的 DeepSeek 官方网页版全能主视窗 (免 Key 极速模式)
 * 底层常驻挂载官方 WebView，彻底杜绝离屏节流挂起，共享登录态，支持状态打点、防截断与多轮追问
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingWebSolverScreen(
    bridgeManager: DeepSeekBridgeManager,
    voiceManager: VoiceManager,
    onSwitchToApi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authStatus by bridgeManager.authStatus.collectAsState()
    val solveStatus by bridgeManager.solveStatus.collectAsState()
    val thinkingText by bridgeManager.currentThinking.collectAsState()
    val answerText by bridgeManager.currentAnswer.collectAsState()
    val answerHtml by bridgeManager.currentAnswerHtml.collectAsState()
    val plainText by bridgeManager.currentPlainText.collectAsState()
    val errorMessage by bridgeManager.errorMessage.collectAsState()
    val showCaptcha by bridgeManager.showCaptchaDialog.collectAsState()
    val qaHistory by bridgeManager.qaHistory.collectAsState()
    val stepDiagnosis by bridgeManager.stepDiagnosis.collectAsState()
    val playbackState by voiceManager.playbackState.collectAsState()

    // 0: 📱 原生伴读精排视图, 1: 🌐 官方网页原生视图 (用于滑动滑块或扫码登录)
    var currentViewMode by remember { mutableIntStateOf(0) }
    // 0: 🌐 HTML 优雅排版, 1: 📝 Markdown 排版
    var displayMode by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var enableR1 by remember { mutableStateOf(false) } // 语文伴读极速模式：默认关闭深度思考
    var isDetailedMode by remember { mutableStateOf(false) } // 逐句精讲与文白全景对照
    var isThinkingExpanded by remember { mutableStateOf(false) }
    var followUpQuestion by remember { mutableStateOf("") }

    // 物理返回键拦截：网页视图时优先回退网页，到底切回原生伴读
    BackHandler(enabled = currentViewMode == 1) {
        if (bridgeManager.webView.canGoBack()) {
            bridgeManager.webView.goBack()
        } else {
            currentViewMode = 0
        }
    }

    // 拍书籍 / 选图
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            val b64 = compressImageToBase64(context, it)
            if (b64 != null) {
                selectedImageBase64 = b64
            } else {
                Toast.makeText(context, "图片压缩失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 语音输入：短文篇名录入 (带宽限超时参数，避免低年级学生说话慢报错)
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                inputText = spoken
            }
        }
    }

    // 语音输入：伴读提问录入
    val questionSpeechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                followUpQuestion = spoken
                if (spoken.isNotBlank()) {
                    bridgeManager.sendFollowUp(spoken)
                    followUpQuestion = ""
                }
            }
        }
    }

    // 错误信息弹窗提示
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    // 验证码安全弹窗
    if (showCaptcha) {
        AlertDialog(
            onDismissRequest = { bridgeManager.dismissCaptcha() },
            title = { Text("⚠️ 需要进行安全验证", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text("DeepSeek 官方正在请求滑动人机验证，点击前往【官方网页】完成滑动验证即可自动继续导读。")
            },
            confirmButton = {
                Button(onClick = {
                    bridgeManager.dismissCaptcha()
                    currentViewMode = 1
                }) {
                    Text("前往网页滑动验证")
                }
            },
            dismissButton = {
                TextButton(onClick = { bridgeManager.dismissCaptcha() }) {
                    Text("稍后")
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ================= 常驻底层挂载官方 WebView =================
        // 核心技术保障：保持 Attached to Window 全屏常驻，防止 Chromium 渲染树剔除与定时器深度节流休眠！
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (currentViewMode == 1) 46.dp else 0.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    bridgeManager.webView.apply {
                        (parent as? ViewGroup)?.removeView(this)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                }
            )
        }

        if (currentViewMode == 1) {
            // ================= 视图 2：🌐 官方网页顶部悬浮工具栏 =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Color(0xFF1B3B5A))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FilledTonalButton(
                    onClick = { currentViewMode = 0 },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF2C5E8A),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回原生", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("返回伴读主界面", fontSize = 12.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { bridgeManager.checkLoginStatus(forceReload = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color.White)
                    }
                    IconButton(onClick = {
                        voiceManager.stopSpeaking()
                        bridgeManager.startNewChat()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "新一课", tint = Color.White)
                    }
                    IconButton(onClick = { bridgeManager.logoutAndClear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清理登出", tint = Color.White)
                    }
                }
            }
        } else {
            // ================= 视图 1：📱 原生语文伴读精排主视图 =================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF9F7F1)) // 柔光护眼米色
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. 顶部引擎切换与状态卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        // 引擎切换胶囊行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("伴读引擎：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B3B5A))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = false,
                                    onClick = onSwitchToApi,
                                    label = { Text("⚡ 切换为 API 模式", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("🐋 DeepSeek 网页版 (当前)", fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 登录状态条与新一课按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (authStatus) {
                                    AuthStatus.LOGGED_IN -> Color(0xFF4CAF50)
                                    AuthStatus.CHECKING -> Color(0xFFFFA000)
                                    AuthStatus.NOT_LOGGED_IN -> Color(0xFFE53935)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(dotColor, RoundedCornerShape(5.dp))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (authStatus) {
                                        AuthStatus.LOGGED_IN -> "DeepSeek 官方已就绪 (免密免Key)"
                                        AuthStatus.CHECKING -> "正在检测账号登录状态..."
                                        AuthStatus.NOT_LOGGED_IN -> "未登录 (点击前往网页登录)"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (authStatus == AuthStatus.NOT_LOGGED_IN) Color.Red else Color(0xFF333333),
                                    modifier = Modifier.clickable {
                                        if (authStatus != AuthStatus.LOGGED_IN) currentViewMode = 1
                                    }
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        voiceManager.stopSpeaking()
                                        inputText = ""
                                        selectedImageUri = null
                                        selectedImageBase64 = null
                                        followUpQuestion = ""
                                        bridgeManager.startNewChat()
                                        Toast.makeText(context, "已开启全新一课，上下文已重置！", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C6454))
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("新一课", fontSize = 11.sp)
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                OutlinedButton(
                                    onClick = { currentViewMode = 1 },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("🌐 官方网页", fontSize = 11.sp, color = Color(0xFF1B3B5A))
                                }
                            }
                        }

                        // 诊断探针状态提示条 (实时反映字数增长与打点)
                        if (solveStatus == SolveStatus.SOLVING || stepDiagnosis.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    if (solveStatus == SolveStatus.SOLVING) {
                                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = stepDiagnosis,
                                        fontSize = 11.sp,
                                        color = if (solveStatus == SolveStatus.ERROR) Color.Red else Color(0xFF475569)
                                    )
                                }

                                if (solveStatus == SolveStatus.SOLVING) {
                                    Text(
                                        text = "直看网页 👉",
                                        fontSize = 11.sp,
                                        color = Color(0xFF1B3B5A),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { currentViewMode = 1 }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. 快捷操作条 (语音、拍照原图、朗读)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            try {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "说出想读的课文名、名篇或短文...")
                                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                                }
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "未找到语音服务，可直接打字输入", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Mic, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("按住说")
                    }

                    FilledTonalButton(onClick = { imgPicker.launch("image/*") }) {
                        Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (selectedImageUri == null) "拍书籍/传图" else "重选图片")
                    }

                    // 朗读全文控制
                    when (playbackState) {
                        PlaybackState.PLAYING -> {
                            Button(
                                onClick = { voiceManager.pause() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                            ) {
                                Icon(Icons.Default.Pause, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("暂停朗读")
                            }
                            IconButton(onClick = { voiceManager.stopSpeaking() }) {
                                Icon(Icons.Default.Stop, null, tint = Color.Red)
                            }
                        }
                        PlaybackState.PAUSED -> {
                            Button(
                                onClick = { voiceManager.resume() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("继续朗读")
                            }
                            IconButton(onClick = { voiceManager.stopSpeaking() }) {
                                Icon(Icons.Default.Stop, null, tint = Color.Red)
                            }
                        }
                        PlaybackState.IDLE -> {
                            Button(
                                onClick = {
                                    val textToRead = plainText.ifBlank { answerText }.ifBlank { inputText }
                                    if (textToRead.isNotBlank()) {
                                        voiceManager.speak(textToRead)
                                    } else {
                                        Toast.makeText(context, "暂无可朗读内容", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF436077))
                            ) {
                                Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("朗读全文")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. 课文/篇目全能输入卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        AnimatedVisibility(visible = selectedImageUri != null) {
                            val bitmap = rememberBitmapFromUri(context, selectedImageUri)
                            Box(modifier = Modifier.fillMaxWidth().height(140.dp).padding(bottom = 8.dp)) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "书籍原图",
                                        modifier = Modifier.fillMaxSize().background(Color(0xFFEEEEEE), RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        selectedImageUri = null
                                        selectedImageBase64 = null
                                    },
                                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "移除图片", tint = Color.Red)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("输入篇名、课本年级课号（如：小学三年级上册第十课、小石潭记、背影）或直接粘贴长短文...") },
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                            trailingIcon = {
                                if (inputText.isNotEmpty()) {
                                    IconButton(onClick = { inputText = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空输入框", tint = Color.Gray)
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 选项开关行：逐句精讲 与 R1 深度推导
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = isDetailedMode,
                                    onCheckedChange = { isDetailedMode = it }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("📜 逐句精讲与文白对照", fontSize = 12.sp, color = Color(0xFF2C3E50))
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🧠 R1深度思考", fontSize = 12.sp, color = if (enableR1) Color(0xFF1B3B5A) else Color.Gray)
                                Switch(
                                    checked = enableR1,
                                    onCheckedChange = { enableR1 = it },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 开始全景深度导读 / 停止推导按钮
                        if (solveStatus == SolveStatus.SOLVING) {
                            Button(
                                onClick = {
                                    bridgeManager.stopSolving()
                                    voiceManager.stopSpeaking()
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B))
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(8.dp))
                                Text("⏹️ 停止生成", fontSize = 16.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (authStatus != AuthStatus.LOGGED_IN) {
                                        currentViewMode = 1
                                        Toast.makeText(context, "请先在网页端登录 DeepSeek，登录后即可永久免费使用", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (inputText.isBlank() && selectedImageBase64 == null) {
                                        Toast.makeText(context, "请先输入课文篇名、年级课号或拍照书籍", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }

                                    val guidePrompt = ZhipuAiClient.buildGuidePrompt(isDetailedMode)
                                    val fullCustomPrompt = """
                                        $guidePrompt
                                        
                                        【请对以下篇目/课文/文本开展全景深度导读】：
                                        $inputText
                                    """.trimIndent()

                                    bridgeManager.solveProblem(
                                        rawProblemText = inputText,
                                        imageBase64 = selectedImageBase64,
                                        enableR1 = enableR1,
                                        customPrompt = fullCustomPrompt
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3B5A))
                            ) {
                                Icon(Icons.Default.AutoStories, null)
                                Spacer(Modifier.width(8.dp))
                                Text("开始全景深度导读 (DeepSeek 极速)", fontSize = 16.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. 当前导学正式生成结果卡片
                if (solveStatus == SolveStatus.SOLVING || answerText.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // 深度推导思维链折叠面板
                            if (thinkingText.isNotBlank()) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F5)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isThinkingExpanded = !isThinkingExpanded }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = if (isThinkingExpanded) "🧠 深度思考过程 (点击收起)" else "🧠 深度思考过程 (点击展开)",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF4A6572)
                                                )
                                                if (solveStatus == SolveStatus.SOLVING && answerText.isBlank()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                                }
                                            }
                                            Icon(
                                                if (isThinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        if (isThinkingExpanded) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = thinkingText,
                                                fontSize = 12.sp,
                                                lineHeight = 18.sp,
                                                color = Color(0xFF555555)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            // 标题与排版模式切换胶囊
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🎯 全景深度导读与核心考点清单", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B3B5A))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFF1F3F5)
                                ) {
                                    Row(modifier = Modifier.padding(2.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (displayMode == 0) Color(0xFF1B3B5A) else Color.Transparent)
                                                .clickable { displayMode = 0 }
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text("🌐 富文本", fontSize = 11.sp, color = if (displayMode == 0) Color.White else Color(0xFF555555))
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (displayMode == 1) Color(0xFF1B3B5A) else Color.Transparent)
                                                .clickable { displayMode = 1 }
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text("📝 纯排版", fontSize = 11.sp, color = if (displayMode == 1) Color.White else Color(0xFF555555))
                                        }
                                    }
                                }
                            }

                            Divider(modifier = Modifier.padding(vertical = 10.dp))

                            // 正文渲染
                            if (displayMode == 0 && answerHtml.isNotBlank()) {
                                HtmlAnswerView(htmlContent = answerHtml)
                            } else {
                                Text(
                                    text = answerText,
                                    fontSize = 15.sp,
                                    lineHeight = 24.sp,
                                    color = Color(0xFF222222)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 5. 多轮互动追问历史
                if (qaHistory.isNotEmpty()) {
                    Text("💬 伴读追问历史 (${qaHistory.size} 轮)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B3B5A))
                    Spacer(modifier = Modifier.height(6.dp))
                    qaHistory.forEachIndexed { index, item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("🙋 第 ${index + 1} 问：${item.question}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1B3B5A))
                                Divider(modifier = Modifier.padding(vertical = 6.dp))
                                if (displayMode == 0 && item.answerHtml.isNotBlank()) {
                                    HtmlAnswerView(htmlContent = item.answerHtml)
                                } else {
                                    Text(
                                        text = item.answer,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                        color = Color(0xFF222222)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 6. 伴读追问输入框
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EFE6)),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("💬 随时请教导师 (针对字词、句意、写作手法追问)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF335544))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = followUpQuestion,
                                onValueChange = { followUpQuestion = it },
                                placeholder = { Text("输入您的追问...", fontSize = 13.sp) },
                                modifier = Modifier.weight(1f).height(50.dp),
                                singleLine = true
                            )

                            IconButton(
                                onClick = {
                                    try {
                                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                                            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你的追问...")
                                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                                        }
                                        questionSpeechLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "未找到语音服务，可直接打字输入", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "语音提问")
                            }

                            Button(
                                onClick = {
                                    if (followUpQuestion.isNotBlank()) {
                                        bridgeManager.sendFollowUp(followUpQuestion)
                                        followUpQuestion = ""
                                    }
                                },
                                enabled = solveStatus != SolveStatus.SOLVING && followUpQuestion.isNotBlank(),
                                modifier = Modifier.height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3B5A))
                            ) {
                                Text("追问", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

