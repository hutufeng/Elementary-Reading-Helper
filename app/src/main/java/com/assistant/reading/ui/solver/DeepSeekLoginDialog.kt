package com.assistant.reading.ui.solver

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.assistant.reading.core.bridge.DeepSeekBridgeManager

@Composable
fun DeepSeekLoginDialog(
    bridgeManager: DeepSeekBridgeManager,
    onDismiss: () -> Unit
) {
    val progress by bridgeManager.webProgress.collectAsState()

    // 优先让网页回退，若无法回退则关闭弹窗
    val handleBack = {
        if (bridgeManager.webView.canGoBack()) {
            bridgeManager.webView.goBack()
        } else {
            onDismiss()
        }
    }

    BackHandler(onBack = handleBack)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 原生顶部防卡死控制条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B3B5A))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "DeepSeek 官方登录",
                            color = Color.White,
                            fontSize = 15.sp,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { bridgeManager.checkLoginStatus(forceReload = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color.White)
                        }
                        IconButton(onClick = { bridgeManager.logoutAndClear() }) {
                            Icon(Icons.Default.Delete, contentDescription = "清理登出", tint = Color.White)
                        }
                    }
                }

                // 网页加载进度条
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Color(0xFF4A90E2)
                    )
                }

                // 官方登录网页容器
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
            }
        }
    }
}

