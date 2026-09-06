package com.zhukongqwq.hanser.core

import org.json.JSONObject
import java.io.File

/**
 * AI 配置存储（Android 端 config.json，替代 Windows 的 config.yml）：
 * openai 全局默认 + agents（bunny/prometheus/hanser）独立配置 + update_source + dry_run。
 * 结构字段与 Windows config.yml 保持一致（base_url/api_key/model/max_tokens/url）。
 */
data class EndpointConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val maxTokens: Int = 0
)

class AiConfig(private val file: File) {

    companion object {
        /** 默认文档更新源（与 Windows 版 GitHubSync.Config 默认一致）。 */
        const val DEFAULT_UPDATE_URL = "https://github.com/zhukongqwq/hanser-live-text.git"
    }

    private fun root(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun readEndpoint(section: JSONObject): EndpointConfig =
        EndpointConfig(
            baseUrl = section.optString("base_url").trim(),
            apiKey = section.optString("api_key").trim(),
            model = section.optString("model").trim(),
            maxTokens = section.optInt("max_tokens", 0)
        )

    /** 全局默认（openai 段）；无配置返回全空。 */
    fun loadGlobal(): EndpointConfig {
        val root = root()
        return if (root.has("openai")) readEndpoint(root.getJSONObject("openai"))
        else EndpointConfig()
    }

    /** 某 AI 独立配置（agents 段）；未配置/全空返回 null。 */
    fun loadAgent(name: String): EndpointConfig? {
        val root = root()
        val agents = root.optJSONObject("agents") ?: return null
        val section = agents.optJSONObject(name) ?: return null
        val cfg = readEndpoint(section)
        val hasAny = cfg.baseUrl.isNotEmpty() || cfg.apiKey.isNotEmpty() ||
                cfg.model.isNotEmpty() || cfg.maxTokens > 0
        return if (hasAny) cfg else null
    }

    /** 更新源仓库 url（update_source.url）；未配置时返回 Windows 版同款默认。 */
    fun loadUpdateUrl(): String {
        val url = root().optJSONObject("update_source")?.optString("url", "").orEmpty()
        return if (url.isBlank()) DEFAULT_UPDATE_URL else url
    }

    /** 模拟模式开关。 */
    fun loadDryRun(): Boolean = root().optBoolean("dry_run", false)

    /** 整体保存（覆盖写）。 */
    fun save(global: EndpointConfig,
             bunny: EndpointConfig = EndpointConfig(),
             prometheus: EndpointConfig = EndpointConfig(),
             hanser: EndpointConfig = EndpointConfig(),
             updateUrl: String = "",
             dryRun: Boolean = false) {
        fun section(c: EndpointConfig) = JSONObject().apply {
            put("base_url", c.baseUrl)
            put("api_key", c.apiKey)
            put("model", c.model)
            put("max_tokens", c.maxTokens)
        }
        val root = JSONObject()
        root.put("openai", section(global))
        root.put("agents", JSONObject().apply {
            put("bunny", section(bunny))
            put("prometheus", section(prometheus))
            put("hanser", section(hanser))
        })
        root.put("update_source", JSONObject().put("url", updateUrl))
        root.put("dry_run", dryRun)
        file.parentFile?.mkdirs()
        file.writeText(root.toString(2), Charsets.UTF_8)
    }
}
