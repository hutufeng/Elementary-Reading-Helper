package com.assistant.reading.ui.solver

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.reading.core.bridge.AuthStatus
import com.assistant.reading.core.bridge.DeepSeekBridgeManager
import com.assistant.reading.core.bridge.SolveStatus
import com.assistant.reading.core.voice.VoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MathSolverScreen(
    bridgeManager: DeepSeekBridgeManager,
    voiceManager: VoiceManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authStatus by bridgeManager.authStatus.collectAsState()
    val solveStatus by bridgeManager.solveStatus.collectAsState()
    val thinkingText by bridgeManager.currentThinking.collectAsState()
    val answerText by bridgeManager.currentAnswer.collectAsState()
    val errorMessage by bridgeManager.errorMessage.collectAsState()
    val showCaptcha by bridgeManager.showCaptchaDialog.collectAsState()
    val qaHistory by bridgeManager.qaHistory.collectAsState()

    var showLoginDialog by remember { mutableStateOf(false) }
    var problemText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var enableR1 by remember { mutableStateOf(true) }
    var isThinkingExpanded by remember { mutableStateOf(false) }
    var followUpQuestion by remember { mutableStateOf("") }

    // 图片选取器 (带采样防 OOM 压缩)
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            val b64 = compressImageToBase64(context, it)
            if (b64 != null) {
                selectedImageBase64 = b64
            } else {
                Toast.makeText(context, "图片处理失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 错误信息提示
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    // 全屏登录弹窗
    if (showLoginDialog || authStatus == AuthStatus.NOT_LOGGED_IN) {
        DeepSeekLoginDialog(
            bridgeManager = bridgeManager,
            onDismiss = { showLoginDialog = false }
        )
    }

    // 验证码半屏天窗
    if (showCaptcha) {
        AlertDialog(
            onDismissRequest = { bridgeManager.dismissCaptcha() },
            title = { Text("⚠️ 需要进行安全验证", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text("官方网页正在请求人机验证，点击下方按钮将打开验证天窗，滑动滑块完成后即可自动继续解题。")
            },
            confirmButton = {
                Button(onClick = {
                    bridgeManager.dismissCaptcha()
                    showLoginDialog = true
                }) {
                    Text("打开验证天窗")
                }
            },
            dismissButton = {
                TextButton(onClick = { bridgeManager.dismissCaptcha() }) {
                    Text("稍后再试")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF6F8FA))
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 1. 顶部状态栏与账号管理卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            AuthStatus.LOGGED_IN -> "DeepSeek-R1 官方满血推理已就绪"
                            AuthStatus.CHECKING -> "正在校验登录状态..."
                            AuthStatus.NOT_LOGGED_IN -> "未登录 (点击登录)"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF333333),
                        modifier = Modifier.clickable {
                            if (authStatus != AuthStatus.LOGGED_IN) showLoginDialog = true
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { bridgeManager.startNewChat() }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("新一题", fontSize = 11.sp)
                    }

                    IconButton(
                        onClick = { showLoginDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "网页管理", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. 题目输入与图片直传卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 题目图片缩略图展示
                AnimatedVisibility(visible = selectedImageUri != null) {
                    val bitmap = rememberBitmapFromUri(context, selectedImageUri)
                    Box(modifier = Modifier.fillMaxWidth().height(160.dp).padding(bottom = 8.dp)) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "题目截图",
                                modifier = Modifier.fillMaxSize().background(Color(0xFFEEEEEE), RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
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

                // 题目文字补充框
                OutlinedTextField(
                    value = problemText,
                    onValueChange = { problemText = it },
                    placeholder = {
                        Text(
                            text = if (selectedImageUri != null)
                                "图片已就绪！可直接点击下方【开始深度推导】，或补充解题要求..."
                            else
                                "点击右下角【传题目原图】直发几何图/坐标系；或直接粘贴题目文字与公式...",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 控制按钮条：传图、R1 开关、求解
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { imgPicker.launch("image/*") },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (selectedImageUri == null) "拍题/传原图" else "重选原图", fontSize = 12.sp)
                    }

                    // R1 深度思考开关
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠 R1深度思考", fontSize = 11.sp, color = if (enableR1) Color(0xFF1B3B5A) else Color.Gray)
                        Switch(
                            checked = enableR1,
                            onCheckedChange = { enableR1 = it },
                            modifier = Modifier.scale(0.8f)
                        )
                    }

                    Button(
                        onClick = {
                            if (authStatus != AuthStatus.LOGGED_IN) {
                                showLoginDialog = true
                                Toast.makeText(context, "请先登录官方账号", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (problemText.isBlank() && selectedImageBase64 == null) {
                                Toast.makeText(context, "请先上传题目原图或输入题目内容", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            bridgeManager.solveProblem(problemText, selectedImageBase64, enableR1)
                        },
                        enabled = solveStatus != SolveStatus.SOLVING,
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3B5A))
                    ) {
                        if (solveStatus == SolveStatus.SOLVING) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("推导中...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("开始推导", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. 多轮问答历史
        qaHistory.forEachIndexed { index, item ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF3F8))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🙋 第 ${index + 1} 问：${item.question}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1B3B5A))
                    Divider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(item.answer, fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFF222222))
                }
            }
        }

        // 4. 当前正在推导中的结果展示
        if (solveStatus == SolveStatus.SOLVING || answerText.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // 可折叠思考过程 (Thinking Process)
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
                                        Text("🧠 深度推导逻辑链 (思维过程)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4A6572))
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
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = Color(0xFF555555)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 正式解答卡片标题与朗读按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📝 规范解题步骤与答案", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B3B5A))
                        if (answerText.isNotBlank()) {
                            IconButton(onClick = { voiceManager.speak(answerText) }) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "朗读答案", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    if (answerText.isBlank() && solveStatus == SolveStatus.SOLVING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在进行全推导演算，请稍候...", fontSize = 13.sp, color = Color.Gray)
                        }
                    } else {
                        Text(
                            text = answerText,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = Color(0xFF222222)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. 底部多轮连续追问框
        if (answerText.isNotBlank() || qaHistory.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EFE6)),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = followUpQuestion,
                        onValueChange = { followUpQuestion = it },
                        placeholder = { Text("追问：如‘第(2)小问为什么两边平方？’...", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            if (followUpQuestion.isNotBlank()) {
                                bridgeManager.sendFollowUp(followUpQuestion)
                                followUpQuestion = ""
                            }
                        },
                        enabled = solveStatus != SolveStatus.SOLVING,
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

// 辅助：从本地 Uri 加载 ImageBitmap

@Composable
fun rememberBitmapFromUri(context: Context, uri: Uri?): ImageBitmap? {
    if (uri == null) return null
    return produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            }
        }
    }.value
}

fun compressImageToBase64(context: Context, uri: Uri): String? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        val maxDim = maxOf(options.outWidth, options.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 2560) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos)
        val bytes = baos.toByteArray()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "data:image/jpeg;base64,$encoded"
    } catch (e: Exception) {
        null
    }
}
