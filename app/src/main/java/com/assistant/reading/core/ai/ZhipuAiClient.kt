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
                你是一位资深语文特级名师与经典阅读导师。

                【自适应阅读与输入解析指引】：
                用户输入的内容形式多样：
                1. 中小学课文或教材章节：若用户仅输入年级与课号（如“小学三年级上册第10课”、“初二下册第四课”等），请以官方主流统编教材为参考，在第一行简要确认篇名与作者（例如：`《在牛肚子里旅行》（张之路）`），随后直接展开导读，切勿输出多余的免责声明或版本说教；
                2. 经典名著、古今散文、文言诗词、现代文学长短篇或用户自选阅读材料：直接依据作品本身的体裁与内容展开专业导学。
                3. 作者与出处考证求真：准确呈现文章的真实作者与所属朝代/国别，严禁张冠李戴。
                4. 严禁以“未提供文章全文”为由推脱，请依据知识库自动提取并展开通透的全景导学。

                请你依据文章自身的体裁（童话/散文/记叙文/文言文/古诗/小说/议论文）与深度，自适应输出通透、详实的导学内容：

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
                ### 📚 三、 重要知识点·核心考点全览（全量必背与记忆清单）
                【核心铁律】：本板块必须做到【全量详尽列出、不留死角、严禁仅挑两三个词举例糊弄】，地毯式梳理文中所有重要知识点、核心考点以及需要学生与读者背诵记忆的内容：
                1. 📌 【全量生字词、多音字与拼音释义清单（需要记忆背诵）】：
                   - 全量列出文中所有需掌握的生字新词（必须注上标准汉语拼音、部首结构、易错笔画、语境释义、近反义词）。
                   - 全量整理课文出现的多音字组词、重点成语及深度语境解析。
                   - （若是古诗文/文言文）：地毯式穷尽全文所有通假字、古今异义词、词类活用、一词多义及文言虚词在文中的具体用法。
                2. 🎯 【核心必考考点与高频出题点全览（必考掌握）】：
                   - 全量剖析文中的常考考点：修辞手法（比喻/拟人/夸张/排比/对偶/反问等）的辨析与表达效果、常考句式变换。
                   - 关键句段结构作用考点（如承上启下、首尾呼应、开篇点题、卒章显志、铺垫对比等在考试中的标准答题采分点）。
                   - 针对字音字形易错点、形近字辨析进行考点提炼。
                3. 📝 【名句名段·全量必背与默写清单（需要背诵记忆内容）】：
                   - 明确标出全文中要求背诵默写的重点段落与精彩名句。
                   - 梳理常见的中考/高考/期末考试“情境式名句默写”与核心采分字词，标出极易写错别字的字。
                4. 🏛️ 【文学常识与文化常识全量必记】：
                   - 作者名、字、号、朝代/国别、文学地位、代表作及所属流派。
                   - 篇章出处及文中涉及的历史典故、传统文化常识（古代称谓、官职升降、天文地理、岁时节日等）。

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
