package com.assistant.reading

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assistant.reading.BuildConfig
import com.assistant.reading.core.ai.AppSettingsManager
import com.assistant.reading.core.bridge.DeepSeekBridgeManager
import com.assistant.reading.core.update.UpdateManager
import com.assistant.reading.core.voice.PlaybackState
import com.assistant.reading.core.voice.VoiceManager
import com.assistant.reading.ui.ReadingSection
import com.assistant.reading.ui.ReadingViewModel
import com.assistant.reading.ui.UiChatMessage
import com.assistant.reading.ui.solver.MathSolverScreen
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var voiceManager: VoiceManager
    private val bridgeManager by lazy { DeepSeekBridgeManager(this) }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceManager = VoiceManager(this)

        setContent {
            MaterialTheme {
                val vm: ReadingViewModel = viewModel()
                val uiState by vm.uiState.collectAsState()
                val context = LocalContext.current
                val playbackState by voiceManager.playbackState.collectAsState()
                var showSettingsDialog by remember { mutableStateOf(false) }

                // 关联全局 DeepSeek Bridge (实现理科与语文共享单例免登会话)
                LaunchedEffect(bridgeManager) {
                    vm.setBridgeManager(bridgeManager)
                }

                // 同步当前语速到 VoiceManager
                LaunchedEffect(uiState.speechRate) {
                    voiceManager.setSpeechRate(uiState.speechRate)
                }

                // 多图选择 / 拍照识别 (支持多选书籍与课文)
                val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) {
                        vm.processImageUris(uris)
                    }
                }

                // 语音输入：短文篇名录入 (宽限静音超时，避免说话慢报错)
                val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                    if (res.resultCode == Activity.RESULT_OK) {
                        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                            vm.updateInputText(spoken)
                            vm.startReadingAssistant(spoken)
                        }
                    }
                }

                // 语音输入：自由提问录入
                val questionSpeechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                    if (res.resultCode == Activity.RESULT_OK) {
                        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                            vm.updateChatQuestion(spoken)
                            vm.sendChatQuestion(spoken)
                        }
                    }
                }

                // 监听更新检测结果提示
                LaunchedEffect(uiState.updateMessage) {
                    uiState.updateMessage?.let { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        vm.dismissUpdateMessage()
                    }
                }

                // 新版本更新提示弹窗
                uiState.updateInfo?.let { updateInfo ->
                    AlertDialog(
                        onDismissRequest = { vm.dismissUpdateDialog() },
                        title = {
                            Text("🚀 发现新版本 ${updateInfo.latestVersion}", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Text("当前版本：v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Color.Gray)
                                Text("最新版本：${updateInfo.latestVersion}", fontSize = 12.sp, color = Color(0xFF1B3B5A), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("更新日志：", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                Text(
                                    text = updateInfo.releaseNotes.ifBlank { "包含多项性能优化与细节改进。" },
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = Color(0xFF333333)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("💡 目标单包：StudentReadingAssistant-${updateInfo.latestVersion}.apk", fontSize = 11.sp, color = Color(0xFF1B3B5A), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                Text("💡 支持 GitHub 官方直连与国内高速镜像双通道，单包下载绝不重复。", fontSize = 10.sp, color = Color(0xFF2E7D32))
                            }
                        },
                        confirmButton = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        UpdateManager.downloadLatestApk(context, updateInfo, useMirror = false)
                                        vm.dismissUpdateDialog()
                                    }
                                ) {
                                    Text("GitHub 官方源", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        UpdateManager.downloadLatestApk(context, updateInfo, useMirror = true)
                                        vm.dismissUpdateDialog()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3B5A))
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("🚀 高速下载")
                                }
                            }
                        },
                        dismissButton = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        UpdateManager.openDownloadUrl(context, updateInfo.officialDownloadUrl)
                                        vm.dismissUpdateDialog()
                                    }
                                ) {
                                    Text("浏览器打开", fontSize = 11.sp, color = Color.Gray)
                                }
                                TextButton(onClick = { vm.dismissUpdateDialog() }) {
                                    Text("稍后再说")
                                }
                            }
                        }
                    )
                }

                var selectedTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (selectedTab == 0) "📖 语文阅读伴读" else "🧠 理科深度解题 (R1)",
                                    fontSize = 18.sp
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFECE6D8)),
                            actions = {
                                if (selectedTab == 0) {
                                    IconButton(onClick = { vm.changeFontSize(-2) }) { Text("A-") }
                                    IconButton(onClick = { vm.changeFontSize(2) }) { Text("A+") }
                                }
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "设置")
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color(0xFFECE6D8),
                            tonalElevation = 8.dp
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = {
                                    if (selectedTab != 0) {
                                        voiceManager.stopSpeaking()
                                        selectedTab = 0
                                    }
                                },
                                icon = { Icon(Icons.Default.Book, contentDescription = "语文伴读") },
                                label = { Text("语文伴读", fontSize = 12.sp) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = {
                                    if (selectedTab != 1) {
                                        voiceManager.stopSpeaking()
                                        selectedTab = 1
                                        bridgeManager.checkLoginStatus(forceReload = false)
                                    }
                                },
                                icon = { Icon(Icons.Default.Edit, contentDescription = "理科解题") },
                                label = { Text("理科解题", fontSize = 12.sp) }
                            )
                        }
                    }
                ) { padding ->
                    if (selectedTab == 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF9F7F1)) // 柔光护眼米色
                                .padding(padding)
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                        // 1. 顶部全能快捷操作条
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    voiceManager.stopSpeaking()
                                    vm.startNewLesson()
                                    Toast.makeText(context, "已开启全新一课，会话已就绪", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("新一课")
                            }

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
                                        Toast.makeText(context, "未找到系统语音识别服务，您可直接打字或使用输入法自带语音输入", Toast.LENGTH_LONG).show()
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
                                Text("拍书籍")
                            }

                            // 朗读全文 / 暂停 / 继续 控制按钮
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
                                            val textToRead = uiState.readingResult.ifBlank { uiState.inputText }
                                            voiceManager.speak(textToRead)
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

                        // 2. 伴读引擎双模切换胶囊 (API 模式 ⇋ DeepSeek 网页免 Key 极速模式)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("伴读引擎：", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Color(0xFF1B3B5A))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = uiState.engineMode == "API",
                                    onClick = { vm.setReadingEngine("API") },
                                    label = { Text("⚡ API 模式", fontSize = 12.sp) },
                                    leadingIcon = if (uiState.engineMode == "API") {
                                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                    } else null
                                )
                                FilterChip(
                                    selected = uiState.engineMode == "DEEPSEEK_WEB",
                                    onClick = { vm.setReadingEngine("DEEPSEEK_WEB") },
                                    label = { Text("🐋 DeepSeek 网页 (免 Key)", fontSize = 12.sp) },
                                    leadingIcon = if (uiState.engineMode == "DEEPSEEK_WEB") {
                                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                    } else null
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. 课文/短文全能输入框 (支持一键清空)
                        OutlinedTextField(
                            value = uiState.inputText,
                            onValueChange = { vm.updateInputText(it) },
                            placeholder = { Text("输入篇名、课本年级课号（如：小学三年级上册第十课、小石潭记、背影）或直接粘贴长短文...") },
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = uiState.fontSizeSp.sp),
                            trailingIcon = {
                                if (uiState.inputText.isNotEmpty()) {
                                    IconButton(onClick = { vm.clearInputText() }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空输入框", tint = Color.Gray)
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 【需求 1 & 2】：逐句精讲与文白全景对照 (默认关闭，依据文本按需开启)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = uiState.isDetailedMode,
                                    onCheckedChange = { vm.toggleDetailedMode(it) }
                                    )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("📜 开启全文逐句精讲与文白对照 (默认关闭)", fontSize = 13.sp, color = Color(0xFF2C3E50))
                                    Text("依据文章内容逐句通透解密，特别适合古诗词、文言文及深奥经典", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 开始全景导读 / 停止生成 按钮 (防重入 + 支持停止未完成任务)
                        if (uiState.isLoading) {
                            Button(
                                onClick = {
                                    vm.stopReadingAssistant()
                                    voiceManager.stopSpeaking()
                                    Toast.makeText(context, "已停止导读生成", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B)) // 红色醒目停止按钮
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(8.dp))
                                Text("⏹️ 停止生成", fontSize = 16.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (uiState.engineMode == "API" && !vm.isKeyConfigured()) {
                                        showSettingsDialog = true
                                        Toast.makeText(context, "当前为 API 模式，请先配置 API Key；或切换为 DeepSeek 网页免 Key 模式！", Toast.LENGTH_LONG).show()
                                    } else {
                                        vm.startReadingAssistant()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.engineMode == "DEEPSEEK_WEB") Color(0xFF1B3B5A) else Color(0xFF2C6454)
                                )
                            ) {
                                Icon(Icons.Default.AutoStories, null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (uiState.engineMode == "DEEPSEEK_WEB") "开始全景导读 (DeepSeek 极速)" else "开始全景深度导读",
                                    fontSize = 16.sp
                                )
                            }
                        }

                        if (uiState.isLoading) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        // 3. 全景导读按段落/章节拆分卡片，每段附带独立朗读功能
                        if (uiState.sections.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("🎯 全景深度导读与全套知识点清单：", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1B3B5A))
                            Spacer(modifier = Modifier.height(8.dp))

                            uiState.sections.forEach { section ->
                                SectionCardItem(
                                    section = section,
                                    fontSizeSp = uiState.fontSizeSp,
                                    onSpeakSection = {
                                        voiceManager.speak("${section.title}。\n${section.content}")
                                    }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        } else if (uiState.readingResult.isNotBlank()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = uiState.readingResult,
                                        fontSize = uiState.fontSizeSp.sp,
                                        lineHeight = (uiState.fontSizeSp + 8).sp,
                                        color = Color(0xFF2D2D2D)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 4. 自由 AI 互动问答室 (随问随答)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EFE6)),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("💬 伴读互动问答室", style = MaterialTheme.typography.titleMedium, color = Color(0xFF335544))
                                    if (uiState.chatHistory.isNotEmpty()) {
                                        TextButton(onClick = { vm.clearChatHistory() }) {
                                            Text("清空对话", fontSize = 12.sp)
                                        }
                                    }
                                }

                                Text(
                                    text = "随时针对文中字词、句意、背景追问，或自由请教写作灵感、古文典故与文学常识。",
                                    fontSize = 12.sp,
                                    color = Color(0xFF666666)
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // 对话历史气泡
                                uiState.chatHistory.forEach { chat ->
                                    ChatBubbleItem(
                                        chat = chat,
                                        fontSizeSp = uiState.fontSizeSp,
                                        onSpeak = { voiceManager.speak(chat.content) }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (uiState.isChatLoading) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                // 问答输入框与语音追问按钮
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = uiState.chatQuestion,
                                        onValueChange = { vm.updateChatQuestion(it) },
                                        placeholder = { Text("向导师提问...", fontSize = 14.sp) },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        singleLine = true,
                                        trailingIcon = {
                                            if (uiState.chatQuestion.isNotEmpty()) {
                                                IconButton(onClick = { vm.updateChatQuestion("") }) {
                                                    Icon(Icons.Default.Clear, contentDescription = "清空提问", tint = Color.Gray)
                                                }
                                            }
                                        }
                                    )

                                    IconButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你的问题...")
                                                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                                                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                                                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                                                }
                                                questionSpeechLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "未找到系统语音识别服务，您可直接打字或使用输入法自带语音提问", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = "语音提问")
                                    }

                                    Button(
                                        onClick = {
                                            if (uiState.engineMode == "API" && !vm.isKeyConfigured()) {
                                                showSettingsDialog = true
                                                Toast.makeText(context, "当前为 API 模式，请先配置 API Key；或切换为 DeepSeek 网页免 Key 模式！", Toast.LENGTH_LONG).show()
                                            } else {
                                                vm.sendChatQuestion()
                                            }
                                        },
                                        enabled = uiState.chatQuestion.isNotBlank() && !uiState.isChatLoading
                                    ) {
                                        Text("提问")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 选项卡 2：理科深度解题 (内置 DeepSeek-R1 满血图文推理)
                    MathSolverScreen(
                        bridgeManager = bridgeManager,
                        voiceManager = voiceManager,
                        modifier = Modifier.padding(padding)
                    )
                }
                }

                // 5. 【需求 3】：支持自定义 API 地址、模型切换、语速设置与详尽使用说明对话框
                if (showSettingsDialog) {
                    var tempKey by remember { mutableStateOf(vm.getApiKey()) }
                    var tempBaseUrl by remember { mutableStateOf(uiState.apiBaseUrl) }
                    var tempModel by remember { mutableStateOf(uiState.modelName) }
                    var tempRate by remember { mutableStateOf(uiState.speechRate) }
                    var isPasswordVisible by remember { mutableStateOf(false) }
                    var showDocSection by remember { mutableStateOf(false) }

                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚙️ 服务配置与个性化", fontSize = 18.sp, color = Color(0xFF1B3B5A))
                                TextButton(
                                    onClick = {
                                        vm.resetAiConfigToDefaults()
                                        tempBaseUrl = AppSettingsManager.DEFAULT_BASE_URL
                                        tempModel = AppSettingsManager.DEFAULT_MODEL_NAME
                                        Toast.makeText(context, "已恢复智谱 GLM-4-Flash 官方默认配置", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("恢复默认", fontSize = 11.sp)
                                }
                            }
                        },
                        text = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                // 语速设置
                                Text(
                                    text = "🔊 朗读语速调节 (${"%.2f".format(tempRate)}x)",
                                    fontSize = 13.sp,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF333333)
                                )
                                Slider(
                                    value = tempRate,
                                    onValueChange = { tempRate = it },
                                    valueRange = 0.5f..1.5f,
                                    steps = 9
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { tempRate = 0.65f }) { Text("0.65x 慢速拼读", fontSize = 10.sp) }
                                    TextButton(onClick = { tempRate = 0.88f }) { Text("0.88x 标准伴读", fontSize = 10.sp) }
                                    TextButton(onClick = { tempRate = 1.15f }) { Text("1.15x 快速复习", fontSize = 10.sp) }
                                }

                                Divider(modifier = Modifier.padding(vertical = 8.dp))

                                // AI 接口地址 (Base URL)
                                Text("🌐 API 接口地址 (Base URL)", fontSize = 13.sp, style = MaterialTheme.typography.titleSmall, color = Color(0xFF333333))
                                OutlinedTextField(
                                    value = tempBaseUrl,
                                    onValueChange = { tempBaseUrl = it },
                                    placeholder = { Text("如 https://open.bigmodel.cn/api/paas/v4") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                // 模型名称 (Model)
                                Text("🤖 模型名称 (Model Name)", fontSize = 13.sp, style = MaterialTheme.typography.titleSmall, color = Color(0xFF333333))
                                OutlinedTextField(
                                    value = tempModel,
                                    onValueChange = { tempModel = it },
                                    placeholder = { Text("如 glm-4-flash / deepseek-chat") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                // API Key
                                Text("🔑 API Key 密钥", fontSize = 13.sp, style = MaterialTheme.typography.titleSmall, color = Color(0xFF333333))
                                OutlinedTextField(
                                    value = tempKey,
                                    onValueChange = { tempKey = it },
                                    placeholder = { Text("粘贴对应平台提供的 API Key") },
                                    singleLine = true,
                                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                            Icon(
                                                if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = "查看密钥"
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // 详细使用说明与第三方模型配置帮助 (点击展开/折叠)
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0EAE1)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().clickable { showDocSection = !showDocSection }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("📖 配置说明与模型切换指引", fontSize = 12.sp, style = MaterialTheme.typography.titleSmall, color = Color(0xFF1B3B5A))
                                            Icon(
                                                if (showDocSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        if (showDocSection) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = """
                                                    1. 官方推荐 (零成本首选):
                                                       • 平台: 智谱 BigModel (open.bigmodel.cn)
                                                       • 接口: https://open.bigmodel.cn/api/paas/v4
                                                       • 模型: glm-4-flash (官方永久免费调用)
                                                       • 密钥: 注册后在开放平台控制台免费创建即可。

                                                    2. 扩充支持其他 OpenAI 兼容大模型:
                                                       本应用基于标准 OpenAI API 规范设计，您可自由切换为其它主流服务商：
                                                       • DeepSeek:
                                                         接口: https://api.deepseek.com
                                                         模型: deepseek-chat
                                                       • 阿里通义千问 (DashScope):
                                                         接口: https://dashscope.aliyuncs.com/compatible-mode/v1
                                                         模型: qwen-turbo 或 qwen-plus
                                                       • 自建反向代理 / One-API:
                                                         直接填入您的代理 URL 与对应模型名即可。

                                                    3. 即调即生效:
                                                       保存后直接写入手机本地安全存储，无需重新编译 APK。
                                                """.trimIndent(),
                                                fontSize = 11.sp,
                                                lineHeight = 16.sp,
                                                color = Color(0xFF444444)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // 软件版本与手动检查升级
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEBF1F6)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("软件版本", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Color(0xFF1B3B5A))
                                            Text("v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Color.Gray)
                                        }

                                        FilledTonalButton(
                                            onClick = { vm.checkForUpdate(BuildConfig.VERSION_NAME) },
                                            enabled = !uiState.isCheckingUpdate,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            if (uiState.isCheckingUpdate) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("检测中...", fontSize = 11.sp)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = "检查更新", modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("检查更新", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    vm.saveAiConfig(tempKey, tempBaseUrl, tempModel)
                                    vm.setSpeechRate(tempRate)
                                    voiceManager.setSpeechRate(tempRate)
                                    Toast.makeText(context, "配置已保存并立即生效！", Toast.LENGTH_SHORT).show()
                                    showSettingsDialog = false
                                }
                            ) {
                                Text("保存生效")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSettingsDialog = false }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.destroy()
        bridgeManager.destroy()
    }
}

/**
 * 分段/章节导读卡片 (带专属独立朗读按钮)
 */
@Composable
fun SectionCardItem(
    section: ReadingSection,
    fontSizeSp: Int,
    onSpeakSection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section.title,
                    fontSize = (fontSizeSp + 1).sp,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1B3B5A),
                    modifier = Modifier.weight(1f)
                )

                FilledTonalButton(
                    onClick = onSpeakSection,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "朗读此段", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("听此段", fontSize = 12.sp)
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = section.content,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp + 8).sp,
                color = Color(0xFF2D2D2D)
            )
        }
    }
}

@Composable
fun ChatBubbleItem(
    chat: UiChatMessage,
    fontSizeSp: Int,
    onSpeak: () -> Unit
) {
    val isUser = chat.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bgColor = if (isUser) Color(0xFFDDEEE3) else Color.White
    val textColor = if (isUser) Color(0xFF1B432C) else Color(0xFF222222)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = if (isUser) "🙋 我问：" else "👨‍🏫 导师回答：",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = chat.content.ifBlank { "正在认真思考中..." },
                    fontSize = (fontSizeSp - 2).sp,
                    lineHeight = (fontSizeSp + 4).sp,
                    color = textColor
                )

                if (!isUser && chat.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(
                        onClick = onSpeak,
                        modifier = Modifier.size(24.dp).align(Alignment.End)
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "朗读回答", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
