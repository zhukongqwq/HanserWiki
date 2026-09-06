package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LlmClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dry-run 关键词 mock`() {
        val client = LlmClient(dryRun = true, apiKey = "x", model = "deepseek-v4-flash")
        val data = client.chatJson(listOf(ChatMessage("system", "你是检索关键词提取助手"), ChatMessage("user", "问题")))
        val arr = (data as org.json.JSONObject).getJSONArray("keywords")
        assertEquals(listOf("直播", "2023"), (0 until arr.length()).map { arr.getString(it) })
    }

    @Test
    fun `dry-run 回答 mock`() {
        val client = LlmClient(dryRun = true, apiKey = "x", model = "m")
        val text = client.chat(listOf(ChatMessage("user", "随便问")))
        assertTrue(text.contains("模拟回答"))
    }

    @Test
    fun `模型映射表自动上限`() {
        assertEquals(1_000_000, LlmClient.resolveMaxTokens(0, "deepseek-v4-flash"))
        assertEquals(1_000_000, LlmClient.resolveMaxTokens(0, "deepseek-pro"))
        assertEquals(1_000_000, LlmClient.resolveMaxTokens(0, "deepseek-flash-vision-exp"))
        assertEquals(8192, LlmClient.resolveMaxTokens(0, "未知模型"))
        assertEquals(5000, LlmClient.resolveMaxTokens(5000, "deepseek-v4-flash"))
    }

    @Test
    fun `parseJson 容错 markdown 与解释文字`() {
        val client = LlmClient(dryRun = true, apiKey = "x", model = "m")
        val obj = client.parseJson("```json\n{\"keywords\": [\"a\"]}\n```") as org.json.JSONObject
        assertEquals("a", obj.getJSONArray("keywords").getString(0))
        val arr = client.parseJson("好的，结果如下：[\"x.docx\", \"y.docx\"]") as org.json.JSONArray
        assertEquals("x.docx", arr.getString(0))
    }

    @Test
    fun `agent 配置覆盖全局`() {
        val cfgFile = File(tmp.root, "config.json")
        val ai = AiConfig(cfgFile)
        ai.save(
            global = EndpointConfig(baseUrl = "https://global/v1", apiKey = "gkey", model = "g-model"),
            hanser = EndpointConfig(baseUrl = "https://hanser/v1", apiKey = "hkey", model = "h-model")
        )
        val globalClient = LlmClient(dryRun = true, config = ai)
        assertEquals("https://global/v1", globalClient.baseUrl)
        assertEquals("gkey", globalClient.apiKey)
        val hansClient = LlmClient(dryRun = true, agentName = "hanser", config = ai)
        assertEquals("https://hanser/v1", hansClient.baseUrl)
        assertEquals("hkey", hansClient.apiKey)
        assertEquals("h-model", hansClient.model)
        // bunny 未单独配置 → 回退全局
        val bunny = LlmClient(dryRun = true, agentName = "bunny", config = ai)
        assertEquals("https://global/v1", bunny.baseUrl)
    }

    @Test
    fun `未配置密钥抛错（非 dry-run）`() {
        try {
            LlmClient(apiKey = "", model = "m")
            assertTrue("应抛异常", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("api_key"))
        }
    }

    @Test
    fun `agent maxTokens 覆盖全局映射`() {
        val cfgFile = File(tmp.root, "config.json")
        val ai = AiConfig(cfgFile)
        ai.save(
            global = EndpointConfig(model = "deepseek-v4-flash"),
            hanser = EndpointConfig(model = "deepseek-v4-flash", maxTokens = 2000)
        )
        val global = LlmClient(dryRun = true, config = ai)
        assertEquals(1_000_000, global.maxOutputTokens)
        val hanser = LlmClient(dryRun = true, agentName = "hanser", config = ai)
        assertEquals(2000, hanser.maxOutputTokens)
    }
}
