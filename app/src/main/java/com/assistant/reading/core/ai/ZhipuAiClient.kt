package com.assistant.reading.core.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

class ZhipuAiClient(
    private val apiKeyProvider: () -> String,
    private val baseUrlProvider: () -> String = { AppSettingsManager.DEFAULT_BASE_URL },
    private val modelNameProvider: () -> String = { AppSettingsManager.DEFAULT_MODEL_NAME }
) {

    constructor(staticApiKey: String) : this({ staticApiKey })

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    companion object {
        // 自适应通识文学导师人设 (不限于中小学，依据文本自身特性自适应)
        const val TEACHER_SYSTEM_PROMPT = """
            你是一位学贯中西、深谙文学鉴赏与语文教学规律的资深阅读导师与知心伴读者。
            请根据用户输入的具体课文、古诗文或阅读资料的内容特质与深浅层次，自适应调整你的导学深度与语言风格：
            1. 面对启蒙与低幼童话：语言亲切灵动、充满童趣，重在启发阅读兴趣；
            2. 面对基础教材与中小学课文：讲清中心思想推导、字词积累、修辞手法与写作借鉴；
            3. 面对文言经典、古诗词赋：剖析字词句法、词类活用、时代历史背景与文化思想内核；
            4. 面对严肃文学、散文小说与哲学经典：挖掘时代精神、象征隐喻、行文构思与人性深度。
            态度温和鼓励，讲深讲透，多用启发式引导读者举一反三。
        """

        /**
         * 构建自适应全景导学提示词
         * @param isDetailedMode 是否开启逐句精讲与文白对照 (默认关闭，按需开启)
         */
        fun buildGuidePrompt(isDetailedMode: Boolean): String {
            val detailedSection = if (isDetailedMode) """
                ---
                ### 📜 零障碍逐句精讲与文白全景对照
                （依据当前文本内容逐句拆解，特别适用于文言文、古诗词及深奥文本）：
                请将文章从头到尾按句或按节进行逐一精讲：
                - 【原文句子】：原汁原味句子
                - 📖 【白话全译】：通俗生动的逐句现代文白话直译
                - 🔍 【字词句微观解密】：详细指出本句中涉及的通假字、古今异义、词类活用、生僻字拼音或核心动词/形容词的语境含义
            """.trimIndent() else ""

            return """
                你是一位学贯古今的专业阅读导读专家。用户输入的内容可能是一段文字，也可能是一个篇名或出处（如《美丽的小兴安岭》、《岳阳楼记》、《百年孤独》选段、《论语》等）。
                请你自动识别并调出对应内容，依据文章自身的体裁（童话/散文/记叙文/文言文/古诗/小说/议论文）与深度，自适应输出通透、详实的导学内容：

                $detailedSection

                ---
                ### 📖 一、 这篇作品究竟讲了什么？（全景导读与创作背景）
                - 还原创作背景与时代风貌：作者是在怎样的历史时代与人生境遇下写下此文的？
                - 宏观脉络梳理：文章的核心线索与情节推进逻辑。

                ---
                ### 🎯 二、 中心思想与内核概括（手把手推导）
                1. 【核心主旨标准概括】：规范、精准提炼文章所表达的核心思想感情、哲理或社会意义。
                2. 【思维推导三步法】：从“抓主要事件/物象 ➔ 找关键主旨句/文眼 ➔ 提炼深层情感与哲理”，手把手呈现推导逻辑。

                ---
                ### ✨ 三、 核心字词句与全篇知识点清单（详尽归纳，拒绝仅举例）
                【重要要求】：请全面、详尽地一一列出文中的核心知识点，不要只挑1~2个词举例！
                1. 📌 【生字词、多音字与文言虚词全景库】：
                   - 详尽列出文中的生字、多音字（附标准汉语拼音与语境释义）、重点成语。
                   - （若是文言文/古诗词）：详尽整理所有通假字、古今异义、词类活用及关键文言虚词用法。
                2. 🌟 【全文修辞与精妙句式全景透视】：
                   - 详尽指出全文所运用的修辞手法（比喻、拟人、排比、对偶、借代、反问等）的具体句子与位置。
                   - 逐句品析：表达了怎样的情境，闭上眼睛呈现出怎样生动的画面。

                ---
                ### 💡 四、 表达方法与构思艺术深度说透
                1. 【表达方式配合】：剖析叙事、描写（人物/景物/细节）、抒情、议论之间的巧妙配合与转换。
                2. 【表现手法说透】：讲透文中所用的表现手法（如借景抒情、托物言志、欲扬先抑、动静结合、虚实相生、对比衬托、象征隐喻等），讲清作者为什么要这样写、达到怎样的艺术效果。

                ---
                ### ✍️ 五、 直接借鉴写作锦囊 & 思考考点
                1. 🖋️ 【直接搬进写作的锦囊】：
                   - 黄金开头/结尾构思模式（在实际写作中如何借鉴套用）。
                   - 佳句仿写积木（给出句式结构，并给出一个贴近现实生活的模仿范例）。
                   - 写作素材存钱罐（指出本文素材适合用于哪些常考或经典写作主题中）。
                2. 📋 【深度理解与考点思考题】：
                   - 提炼本文最值得深思的 2~3 道经典问题（词句理解、段落作用、主旨探究）。
                   - 提供规范的答题思路与采分要点。
            """.trimIndent()
        }
    }

    fun streamAsk(content: String, isDetailedMode: Boolean = false): Flow<String> {
        val messages = listOf(
            ChatMessage("system", buildGuidePrompt(isDetailedMode)),
            ChatMessage("user", content)
        )
        return streamChat(messages)
    }

    /**
     * 通用 OpenAI 兼容协议流式调用 (支持自定义 Base URL 与 Model)
     */
    fun streamChat(messages: List<ChatMessage>): Flow<String> = callbackFlow {
        val key = apiKeyProvider()
        if (key.isBlank()) {
            trySend("⚠️ 提示：您尚未配置 API Key。请点击右上角【设置 (⚙️)】按钮填入 API Key 即可使用！")
            close()
            return@callbackFlow
        }

        val rawBaseUrl = baseUrlProvider().trim().trimEnd('/')
        val endpoint = if (rawBaseUrl.endsWith("/chat/completions")) {
            rawBaseUrl
        } else {
            "$rawBaseUrl/chat/completions"
        }
        val currentModel = modelNameProvider().ifBlank { AppSettingsManager.DEFAULT_MODEL_NAME }

        val jsonMessages = JSONArray()
        for (msg in messages) {
            jsonMessages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        val requestBodyJson = JSONObject().apply {
            put("model", currentModel)
            put("stream", true)
            put("messages", jsonMessages)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val eventSource = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                try {
                    val delta = JSONObject(data).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("delta").optString("content", "")
                    if (delta.isNotEmpty()) trySend(delta)
                } catch (_: Exception) {}
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when (response?.code) {
                    401 -> "认证失败 (401)：请检查您的 API Key 是否正确填写。"
                    404 -> "接口地址错误 (404)：请检查自定义 Base URL 路径是否正确。"
                    else -> t?.localizedMessage ?: "网络请求发生异常"
                }
                trySend("\n\n❌ 请求出错：$errorMsg")
                close(t)
            }
        })

        awaitClose { eventSource.cancel() }
    }
}
