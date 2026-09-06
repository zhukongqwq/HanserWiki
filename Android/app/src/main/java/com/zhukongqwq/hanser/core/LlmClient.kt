package com.zhukongqwq.hanser.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 对话消息（OpenAI 兼容格式）。 */
data class ChatMessage(val role: String, val content: String)

/**
 * OpenAI 兼容接口客户端（对应 Windows 版 LlmClient）：
 * - 配置优先级：显式参数 > agent 独立配置 > 全局配置 > 环境变量；
 * - max_tokens<=0 时自动取模型上限（[MaxOutputTokens]）；
 * - dry_run 返回固定模拟响应（不请求网络）；
 * - chatJson 输出容错解析（Markdown 包裹/解释文字）+ 失败重试一次。
 */
class LlmClient(
    baseUrl: String? = null,
    apiKey: String? = null,
    model: String? = null,
    val dryRun: Boolean = false,
    private val agentName: String? = null,
    config: AiConfig? = null
) {
    private val cfg = config

    /** 该 AI 的独立配置（agentName 指定且配置存在时）。 */
    private fun agentCfg(): EndpointConfig? = agentName?.let { cfg?.loadAgent(it) }

    val baseUrl: String
    val apiKey: String
    val model: String

    /** 生效的最大输出 token（AI 级 max_tokens > 全局 max_tokens > 模型映射表 > 保守默认）。 */
    val maxOutputTokens: Int

    init {
        this.baseUrl = firstNonEmpty(
            baseUrl, agentCfg()?.baseUrl ?: "", cfg?.loadGlobal()?.baseUrl ?: "",
            System.getenv("OPENAI_BASE_URL"), DEFAULT_BASE_URL
        )
        this.apiKey = firstNonEmpty(
            apiKey, agentCfg()?.apiKey ?: "", cfg?.loadGlobal()?.apiKey ?: "",
            System.getenv("OPENAI_API_KEY"), ""
        )
        this.model = firstNonEmpty(
            model, agentCfg()?.model ?: "", cfg?.loadGlobal()?.model ?: "",
            System.getenv("OPENAI_MODEL"), ""
        )
        this.maxOutputTokens = resolveMaxTokens(
            (agentCfg()?.maxTokens ?: 0).takeIf { it > 0 }
                ?: (cfg?.loadGlobal()?.maxTokens ?: 0),
            this.model
        )
        if (!dryRun) {
            require(this.apiKey.isNotEmpty()) { "未配置 api_key（请在设置中填写，或设置 OPENAI_API_KEY；可开启模拟模式运行）" }
            require(this.model.isNotEmpty()) { "未配置 model（请在设置中填写，或设置 OPENAI_MODEL；可开启模拟模式运行）" }
        }
    }

    /** 调用对话接口，返回文本。maxTokens<=0 时自动使用 [maxOutputTokens]。 */
    fun chat(messages: List<ChatMessage>, temperature: Double = 0.3,
             maxTokens: Int = 0, jsonMode: Boolean = false, topP: Double = 0.9): String {
        if (dryRun) return mockChat(messages)
        val payload = JSONObject()
        payload.put("model", model)
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        payload.put("messages", arr)
        payload.put("temperature", temperature)
        payload.put("max_tokens", if (maxTokens > 0) maxTokens else maxOutputTokens)
        payload.put("top_p", topP)
        if (jsonMode) payload.put("response_format", JSONObject().put("type", "json_object"))

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 600_000 // 长文本生成最多 10 分钟
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                val errBody = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                throw IllegalStateException("HTTP ${conn.responseCode}：${errBody.take(300)}")
            }
            val body = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val obj = JSONObject(body)
            obj.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** 调用并要求 JSON；返回 JSONObject/JSONArray（容错解析 + 失败自动重试一次）。 */
    fun chatJson(messages: List<ChatMessage>, temperature: Double = 0.2,
                 maxTokens: Int = 0, topP: Double = 0.9): Any {
        val text = chat(messages, temperature, maxTokens, jsonMode = true, topP)
        try {
            return parseJson(text)
        } catch (e: Exception) {
            val text2 = chat(messages, temperature, maxTokens, jsonMode = true, topP)
            try {
                return parseJson(text2)
            } catch (e2: Exception) {
                throw IllegalStateException(
                    "模型两次均未返回有效 JSON：首次原文 ${text.take(200)}，重试原文 ${text2.take(200)}",
                    e2
                )
            }
        }
    }

    /** 从模型输出容错解析 JSON：去 Markdown 代码块、提取 {..}/[..]。 */
    fun parseJson(text: String): Any {
        var t = text.trim()
        t = t.replace(Regex("^```[a-zA-Z]*\\s*"), "")
        t = t.replace(Regex("\\s*```$"), "").trim()
        val value = JSONTokenerCompat.parse(t)
        if (value != null) return value
        // 从解释性文字中提取首个 { 或 [ 到末尾 } 或 ]
        val opens = listOf(t.indexOf('{'), t.indexOf('[')).filter { it >= 0 }
        if (opens.isNotEmpty()) {
            val start = opens.min()
            val end = maxOf(t.lastIndexOf('}'), t.lastIndexOf(']'))
            if (end > start) {
                JSONTokenerCompat.parse(t.substring(start, end + 1))?.let { return it }
            }
        }
        throw IllegalArgumentException("无法解析 JSON（原文：${text.take(200)}）")
    }

    /** dry-run 模拟响应（与 Windows/Python 一致：按任务标记返回固定 JSON）。 */
    fun mockChat(messages: List<ChatMessage>): String {
        val combined = messages.joinToString("\n") { it.content }
        return when {
            combined.contains("检索关键词提取助手") -> """{"keywords": ["直播", "2023"]}"""
            combined.contains("资料锚定助手") -> {
                val m = Regex("文件名：([^\n]+)").find(combined)
                val name = m?.groupValues?.get(1)?.trim() ?: "unknown.docx"
                """["$name"]"""
            }
            else -> "（dry-run 模拟回答：已基于锚定资料完成作答。）"
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_OUTPUT_LIMIT = 8192

        /** 常见模型的最大输出 token 上限（前缀匹配，仍可被配置覆盖）。 */
        val MODEL_OUTPUT_LIMITS: List<Pair<String, Int>> = listOf(
            "deepseek-v4-flash" to 1_000_000,
            "deepseek-pro" to 1_000_000,
            "deepseek-flash-vision-exp" to 1_000_000,
            "gpt-4o" to 16384,
            "gpt-4" to 8192,
            "gpt-3.5" to 4096,
            "qwen" to 8192,
            "glm" to 8192,
            "kimi" to 8192,
            "moonshot" to 8192,
            "claude" to 8192,
            "gemini" to 8192,
            "ernie" to 8192,
        )

        private fun firstNonEmpty(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() } ?: ""

        /** 解析 max_tokens：显式配置 > 模型映射表 > 保守默认。 */
        fun resolveMaxTokens(configured: Int, model: String): Int {
            if (configured > 0) return configured
            val m = model.lowercase()
            for ((prefix, limit) in MODEL_OUTPUT_LIMITS) {
                if (m.contains(prefix)) return limit
            }
            return DEFAULT_OUTPUT_LIMIT
        }
    }
}

/** 解析首个 JSON 值（对象/数组/基本类型）；非法返回 null。 */
internal object JSONTokenerCompat {
    fun parse(text: String): Any? = try {
        val t = text.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
