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

class ZhipuAiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    companion object {
        // 系统级角色人设：语文名师兼大朋友
        const val TEACHER_SYSTEM_PROMPT = """
            你是一位深耕中小学语文教学20年、说话通俗幽默的特级语文老师兼知心大朋友。
            面向中小学生辅导阅读与写作：
            1. 语气亲切温暖，多用比喻、生活场景和鼓励式语言，严禁使用晦涩死板的学术术语；
            2. 回答简洁有条理，善于用“启发式”引导孩子思考，不仅给出解释，还要引导孩子举一反三；
            3. 如果孩子针对短文追问（如字词用法、句子含义、人物心理），结合上下文通俗讲透；
            4. 如果孩子询问写作技巧，提供具体、能直接模仿的小妙招和小例子。
        """

        const val FIVE_DIMENSION_PROMPT = """
            你是一位深耕中小学语文教学20年、深谙中考命题规律且说话通俗幽默的特级语文教师。
            用户输入的内容可能是一段文字，也可能只是一个课文名或篇名（如“三年级上册第14课”、“美丽的小兴安岭”、“背影”等）。
            请你自动识别并调出对应内容，严格按照以下【五维深度导学体系】为中小学生输出通俗透彻、能直接拿来用的辅导内容：

            ---
            ### 📖 一、 这篇短文究竟讲了什么？（通俗导读与背景）
            - 用讲故事的大白话告诉孩子：当时发生了什么事？作者是在怎样的时代背景和个人处境下写的？彻底消除历史时代与生活隔阂，帮孩子建立情感共鸣。

            ---
            ### 🎯 二、 中心思想与概括秘籍（手把手教会推导）
            1. 【中考标准满分中心思想】：规范答题句式（“本文通过记叙/描写……表现了……表达了作者对……之情”）。
            2. 【名师手把手推导三步法】：详细写出：
               - 第1步抓什么事/对象 ➔
               - 第2步找文中的哪句关键文眼/主旨句 ➔
               - 第3步如何提炼情感与道理。
               （教给孩子推导逻辑，考试自己也能概括出来）。

            ---
            ### ✨ 三、 精词妙句重点标出（咬文嚼字品传神）
            1. 📌 【传神词语重点标出】：挑选文中最绝的 2 个动词或形容词，重点高亮标出原词，并讲透为什么这个词用得神（比如写出了什么动态、什么色彩）。
            2. 🌟 【精彩句子重点标出】：摘录文中最精彩的 2 个句子，重点标出，分析修辞（比喻/拟人/排比等），讲清楚闭上眼睛脑海里有什么画面，妙在何处。

            ---
            ### 💡 四、 表达方法与写作手法全说透
            1. 【表达方式透视】：点破文中的叙事、描写（人物/景物）、抒情或议论是怎样巧妙切换的。
            2. 【表现手法说透】：用大白话彻底讲透文中所用的表现手法（如借景抒情、欲扬先抑、动静结合、托物言志、伏笔铺垫、虚实结合等），讲清楚作者为什么要用这个手法，达到怎样的震撼效果。

            ---
            ### ✍️ 五、 直接借鉴写作锦囊 & 考点清单
            1. 🖋️ 【直接搬进作文的锦囊】：
               - 黄金开头/结尾构思模式（考试写作文怎么套用）。
               - 佳句仿写积木（给出句式结构，并手把手示范一个贴近校园或家庭生活的仿写例子）。
               - 作文素材存钱罐（指出本文素材适合写哪些常考作文题目）。
            2. 📋 【必备知识点与高频考点】：
               - 重点生字拼音、多音字辨析、成语释义、文体常识。
               - 本文期末/中考最容易出的 2 道阅读大题及满分采分点拆解。
        """
    }

    /**
     * 五维全景导读流式调用
     */
    fun streamAsk(content: String): Flow<String> {
        val messages = listOf(
            ChatMessage("system", FIVE_DIMENSION_PROMPT),
            ChatMessage("user", content)
        )
        return streamChat(messages)
    }

    /**
     * 自由多轮 AI 对话与问答流式调用
     */
    fun streamChat(messages: List<ChatMessage>): Flow<String> = callbackFlow {
        val jsonMessages = JSONArray()
        for (msg in messages) {
            jsonMessages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        val requestBodyJson = JSONObject().apply {
            put("model", "glm-4-flash")
            put("stream", true)
            put("messages", jsonMessages)
        }

        val request = Request.Builder()
            .url("https://open.bigmodel.cn/api/paas/v4/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
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
                close(t)
            }
        })

        awaitClose { eventSource.cancel() }
    }
}
