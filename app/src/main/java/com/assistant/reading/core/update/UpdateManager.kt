package com.assistant.reading.core.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseNotes: String,
    val officialDownloadUrl: String,
    val mirrorDownloadUrl: String
)

object UpdateManager {

    // 官方地址与国内备用镜像源列表
    private val API_ENDPOINTS = listOf(
        "https://api.github.com/repos/hutufeng/Elementary-Reading-Helper/releases/latest",
        "https://ghproxy.net/https://api.github.com/repos/hutufeng/Elementary-Reading-Helper/releases/latest"
    )

    // 国内常用的 GitHub Release 下载加速代理前缀
    private val DOWNLOAD_MIRROR_PREFIX = "https://ghproxy.net/"

    // 缩短超时时间为 6 秒，快速失败并自动切换下一个备用源
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 具备国内网络延时容错与多节点轮询的更新检测
     */
    suspend fun checkUpdate(currentVersion: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        for (endpoint in API_ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "Mozilla/5.0 (Android; StudentReadingAssistant)")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 404) {
                            return@withContext Result.failure(Exception("暂未发布任何 Release 安装包"))
                        }
                        lastError = Exception("请求更新接口响应异常：HTTP ${response.code}")
                        return@use // 尝试下一个镜像源
                    }

                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)

                    val tagName = json.optString("tag_name", "").trim()
                    val releaseNotes = json.optString("body", "暂无更新说明")
                    val htmlUrl = json.optString("html_url", "https://github.com/hutufeng/Elementary-Reading-Helper/releases")

                    // 寻找 APK 资产下载地址
                    var apkDownloadUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkDownloadUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    val officialUrl = if (apkDownloadUrl.isNotBlank()) apkDownloadUrl else htmlUrl
                    val mirrorUrl = if (officialUrl.startsWith("http")) {
                        DOWNLOAD_MIRROR_PREFIX + officialUrl
                    } else {
                        officialUrl
                    }

                    val hasUpdate = compareVersion(tagName, currentVersion) > 0

                    return@withContext Result.success(
                        UpdateInfo(
                            hasUpdate = hasUpdate,
                            currentVersion = currentVersion,
                            latestVersion = tagName,
                            releaseNotes = releaseNotes,
                            officialDownloadUrl = officialUrl,
                            mirrorDownloadUrl = mirrorUrl
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e
                // 当前节点超时或网络受阻，自动进入下一个节点重试
                continue
            }
        }

        // 所有节点均受阻时给出贴心的国内网络指导
        Result.failure(
            Exception(
                "检测更新超时。因国内网络连接 GitHub 存在波动，请稍后重试或开启网络加速工具。"
            )
        )
    }

    /**
     * 语义化版本号比较：v1.0.1 vs 1.0.0
     */
    fun compareVersion(v1: String, v2: String): Int {
        val clean1 = v1.removePrefix("v").removePrefix("V").trim()
        val clean2 = v2.removePrefix("v").removePrefix("V").trim()

        val parts1 = clean1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = clean2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = parts1.getOrElse(i) { 0 }
            val num2 = parts2.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }
        return 0
    }

    /**
     * 使用 Android 系统 DownloadManager 单任务下载最新版 APK (支持选择 GitHub 官方源或高速镜像源)
     */
    fun downloadLatestApk(context: Context, updateInfo: UpdateInfo, useMirror: Boolean = true) {
        val downloadUrl = if (useMirror && updateInfo.mirrorDownloadUrl.isNotBlank()) {
            updateInfo.mirrorDownloadUrl
        } else {
            updateInfo.officialDownloadUrl
        }
        val fileName = "StudentReadingAssistant-${updateInfo.latestVersion}.apk"
        val channelName = if (useMirror) "国内高速镜像" else "GitHub 官方源"

        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("正在下载最新版 ($fileName)")
                setDescription("下载通道：$channelName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "[$channelName] 已启动最新版下载 ($fileName)，请在通知栏查看进度", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // 降级兜底：调用浏览器单次打开单包直链
            openDownloadUrl(context, downloadUrl)
        }
    }

    /**
     * 调用系统浏览器或下载器打开下载链接
     */
    fun openDownloadUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
