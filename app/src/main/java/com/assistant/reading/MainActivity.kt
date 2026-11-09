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
import com.assistant.reading.core.voice.VoiceManager
import com.assistant.reading.ui.ReadingSection
import com.assistant.reading.ui.ReadingViewModel
import com.assistant.reading.ui.UiChatMessage
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var voiceManager: VoiceManager

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceManager = VoiceManager(this)

        setContent {
            MaterialTheme {
                val vm: ReadingViewModel = viewModel()
                val uiState by vm.uiState.collectAsState()
                val context = LocalContext.current
                var showSettingsDialog by remember { mutableStateOf(false) }

                // 同步当前语速到 VoiceManager
                LaunchedEffect(uiState.speechRate) {
                    voiceManager.setSpeechRate(uiState.speechRate)
                }

                // 图片选择 / 拍照
                val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    uri?.let { vm.processImageUri(it) }
                }

                // 语音输入：短文篇名录入
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

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("📖 名师阅读伴读小助手", fontSize = 18.sp) },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFECE6D8)),
                            actions = {
                                IconButton(onClick = { vm.changeFontSize(-2) }) { Text("A-") }
                                IconButton(onClick = { vm.changeFontSize(2) }) { Text("A+") }
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "设置")
                                }
                            }
                        )
                    }
                ) { padding ->
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
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出想读的课文名或篇名...")
                                    }
                                    speechLauncher.launch(intent)
                                }
                            ) {
                                Icon(Icons.Default.Mic, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("按住说")
                            }

                            FilledTonalButton(onClick = { imgPicker.launch("image/*") }) {
                                Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("拍课本")
                            }

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

                            IconButton(onClick = { voiceManager.stopSpeaking() }) {
                                Icon(Icons.Default.Stop, null)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 2. 课文/短文全能输入框
                        OutlinedTextField(
                            value = uiState.inputText,
                            onValueChange = { vm.updateInputText(it) },
                            placeholder = { Text("输入篇名（如：3年级上册14课、古诗、文言文、小兴安岭）或粘贴文本...") },
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = uiState.fontSizeSp.sp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 【新增特性 1】：全文逐句精讲与文白对照开关
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
                                    Text("📜 开启全文逐句精讲与文白对照", fontSize = 13.sp, color = Color(0xFF2C3E50))
                                    Text("特别适合文言文、古诗词及深奥文本逐句通透解密", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (!vm.isKeyConfigured()) {
                                    showSettingsDialog = true
                                    Toast.makeText(context, "请先设置您的智谱 API Key 即可开始使用", Toast.LENGTH_LONG).show()
                                } else {
                                    vm.startReadingAssistant()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C6454))
                        ) {
                            Icon(Icons.Default.AutoStories, null)
                            Spacer(Modifier.width(8.dp))
                            Text("开始名师全景深度导读", fontSize = 16.sp)
                        }

                        if (uiState.isLoading) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        // 【新增特性 3】：全景导学按段落/章节拆分卡片，每段附带独立朗读功能
                        if (uiState.sections.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("🎯 名师全景深度导学与全套知识点：", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1B3B5A))
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
                            // 备用单卡片显示
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
                                    Text("💬 名师互动问答室", style = MaterialTheme.typography.titleMedium, color = Color(0xFF335544))
                                    if (uiState.chatHistory.isNotEmpty()) {
                                        TextButton(onClick = { vm.clearChatHistory() }) {
                                            Text("清空对话", fontSize = 12.sp)
                                        }
                                    }
                                }

                                Text(
                                    text = "读不懂哪里随时问！可追问文中句子词语，或自由请教写作、古诗、语文常识。",
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
                                        placeholder = { Text("向名师提问...", fontSize = 14.sp) },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        singleLine = true
                                    )

                                    IconButton(
                                        onClick = {
                                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                                                putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你的问题...")
                                            }
                                            questionSpeechLauncher.launch(intent)
                                        }
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = "语音提问")
                                    }

                                    Button(
                                        onClick = {
                                            if (!vm.isKeyConfigured()) {
                                                showSettingsDialog = true
                                                Toast.makeText(context, "请先设置您的智谱 API Key 即可开始使用", Toast.LENGTH_LONG).show()
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
                }

                // 5. 【增强型】设置对话框：API Key + 朗读语速精细调节
                if (showSettingsDialog) {
                    var tempKey by remember { mutableStateOf(vm.getApiKey()) }
                    var tempRate by remember { mutableStateOf(uiState.speechRate) }
                    var isPasswordVisible by remember { mutableStateOf(false) }

                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("⚙️ 伴学与朗读设置", fontSize = 18.sp, color = Color(0xFF1B3B5A)) },
                        text = {
                            Column {
                                // 语速设置区域
                                Text(
                                    text = "🔊 朗读语速调节 (当前: ${"%.2f".format(tempRate)}x)",
                                    fontSize = 14.sp,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF333333)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
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

                                Divider(modifier = Modifier.padding(vertical = 10.dp))

                                // API Key 设置区域
                                Text(
                                    text = "🔑 智谱 AI 密钥 (官方 GLM-4-Flash 永久免费)",
                                    fontSize = 14.sp,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF333333)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = tempKey,
                                    onValueChange = { tempKey = it },
                                    label = { Text("智谱 API Key") },
                                    placeholder = { Text("如 6xxx.xxx 格式") },
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
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "💡 访问 bigmodel.cn 免费领取，保存后本地持久化生效，无需重新打包 APK。",
                                    fontSize = 11.sp,
                                    color = Color(0xFF888888)
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    vm.saveApiKey(tempKey)
                                    vm.setSpeechRate(tempRate)
                                    voiceManager.setSpeechRate(tempRate)
                                    Toast.makeText(context, "设置已保存并立即生效！", Toast.LENGTH_SHORT).show()
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

                // 【新增特性 3】：每段卡片专属朗读按钮
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
                    text = if (isUser) "🙋 我问：" else "👨‍🏫 名师回答：",
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
