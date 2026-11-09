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

class ZhipuAiClient(private val apiKeyProvider: () -> String) {

    constructor(staticApiKey: String) : this({ staticApiKey })

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    companion object {
        const val TEACHER_SYSTEM_PROMPT = """
            你是一位深耕中小学语文教学20年、说话通俗幽默的特级语文老师兼知心大朋友。
            面向中小学生辅导阅读与写作：
            1. 语气亲切温暖，多用比喻、生活场景和鼓励式语言，严禁使用晦涩死板的学术术语；
            2. 回答简洁有条理，善于用“启发式”引导孩子思考，不仅给出解释，还要引导孩子举一反三；
            3. 如果孩子针对短文追问（如字词用法、句子含义、人物心理），结合上下文通俗讲透；
            4. 如果孩子询问写作技巧，提供具体、能直接模仿的小妙招和小例子。
        """

        /**
         * 构建全景深度导学提示词
         * @param isDetailedMode 是否开启逐句精读/文言全译模式
         */
        fun buildGuidePrompt(isDetailedMode: Boolean): String {
            val detailedSection = if (isDetailedMode) """
                ---
                ### 📜 零障碍逐句精讲与文白对照（全文逐句通透剖析）
                （特别适用于文言文、古诗词及重点短文，帮助孩子彻底扫清陌生词句与理解障碍）：
                请将文章从头到尾按句或按节进行逐一精讲：
                - 【原文句子】：原汁原味句子
                - 📖 【白话全译】：通俗生动的逐句现代文白话直译
                - 🔍 【字词句微观解密】：详细指出本句中涉及的通假字、古今异义、生僻字拼音、词类活用或重点动词/形容词的语境含义
            """.trimIndent() else ""

            return """
                你是一位深耕中小学语文教学20年、深谙中考命题规律且说话通俗幽默的特级语文教师。
                用户输入的内容可能是一段文字，也可能只是一个课文名或篇名（如“三年级上册第14课”、“美丽的小兴安岭”、“陈涉世家”、“陋室铭”等）。
                请你自动识别并调出对应内容，严格按照以下体系为中小学生输出通俗透彻、详尽详实的导学内容：

                $detailedSection

                ---
                ### 📖 一、 这篇文章究竟讲了什么？（通俗导读与时代背景）
                - 用讲故事的大白话告诉孩子：当时发生了什么事？作者是在怎样的时代背景和个人处境下写的？彻底消除历史时代与生活隔阂，帮孩子建立深层情感共鸣。

                ---
                ### 🎯 二、 中心思想与概括秘籍（手把手教会推导）
                1. 【中考标准满分中心思想】：规范答题句式（“本文通过记叙/描写……表现了……表达了作者对……之情”）。
                2. 【名师手把手推导三步法】：详细写出：
                   - 第1步抓什么事/对象 ➔
                   - 第2步找文中的哪句关键文眼/主旨句 ➔
                   - 第3步如何提炼情感与道理。
                   （教给孩子推导逻辑，考试自己也能概括出来）。

                ---
                ### ✨ 三、 重点字词与精彩句子全景清单（必须详尽列出，拒绝仅举例）
                【重要要求】：请全面、详尽地一一列出文中的所有关键知识点，不要只挑1~2个词举例，要让孩子彻底掌握整篇文章的所有重点！
                1. 📌 【生字词与通假虚词全景库】：
                   - 一一列出文中的所有生字、多音字（附标准汉语拼音）。
                   - 一一列出文中重点词语、成语解释。
                   - （若是文言文/古诗）：详尽列出所有通假字、古今异义、词类活用及关键文言虚词（之、乎、者、也、以、于）。
                2. 🌟 【全文修辞与精妙句式全景透视】：
                   - 详尽列出文中运用了比喻、拟人、排比、对偶、夸张、借代、反问等修辞的所有精彩句子。
                   - 标明句子位置，并用大白话逐句品析：作者把什么比作什么、闭上眼睛脑海里呈现了什么绝妙画面。

                ---
                ### 💡 四、 表达方法与写作手法全说透
                1. 【表达方式剖析】：点破文中的叙事、描写（人物肖像/动作/心理/环境）、抒情或议论是怎样配合推进的。
                2. 【表现手法说透】：用大白话彻底讲透文中所用的表现手法（如借景抒情、欲扬先抑、动静结合、托物言志、伏笔铺垫、虚实结合等），讲清楚作者为什么要用这个手法，达到怎样的震撼效果。

                ---
                ### ✍️ 五、 直接借鉴写作锦囊 & 全套必考点
                1. 🖋️ 【直接搬进作文的锦囊】：
                   - 黄金开头/结尾构思模式（考试写作文怎么套用）。
                   - 佳句仿写积木（给出句式结构，并手把手示范一个贴近校园或家庭生活的仿写例子）。
                   - 作文素材存钱罐（指出本文素材适合写哪些常考作文题目）。
                2. 📋 【中考/期末高频必考大题直击】：
                   - 详尽提炼本文最容易考的 2~3 道阅读真题（词句赏析题、作用题、主旨探究题）。
                   - 提供阅卷老师认可的满分采分点与答题公式。
            """.trimIndent()
        }
    }

    /**
     * 全景深度导读流式调用
     * @param content 短文内容或课文名
     * @param isDetailedMode 是否开启逐句精读/文言全译模式
     */
    fun streamAsk(content: String, isDetailedMode: Boolean = true): Flow<String> {
        val messages = listOf(
            ChatMessage("system", buildGuidePrompt(isDetailedMode)),
            ChatMessage("user", content)
        )
        return streamChat(messages)
    }

    /**
     * 自由多轮 AI 对话与问答流式调用
     */
    fun streamChat(messages: List<ChatMessage>): Flow<String> = callbackFlow {
        val key = apiKeyProvider()
        if (key.isBlank()) {
            trySend("⚠️ 提示：您尚未配置 API Key。请点击右上角【设置 (⚙️)】按钮填入免费领取的智谱 API Key 即可使用！")
            close()
            return@callbackFlow
        }

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
                close(t)
            }
        })

        awaitClose { eventSource.cancel() }
    }
}
