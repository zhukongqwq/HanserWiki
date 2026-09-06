package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class AgentsPipelineTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadDictOnce() {
            TestDict.loadOnce()
        }
    }

    /** 造一个小文档库：a.docx 含直播内容，b.docx 无关。 */
    private fun lib(): LibraryDb {
        val lib = LibraryDb(JdbcSqlDb())
        seed(lib, "a.docx", "2023年小缘直播 海上油菜花演唱会 小缘 小缘 鸟鸟")
        seed(lib, "b.docx", "今天散步天气很好")
        return lib
    }

    private fun seed(lib: LibraryDb, name: String, content: String) {
        val id = lib.upsertDocument(name, "data/$name", content, 1.0, content.length.toLong())
        lib.replaceDocTokens(id, Tokenizer.tokenize(content))
    }

    @Test
    fun `Bunny 提取关键词`() {
        val client = LlmClient(dryRun = true, apiKey = "x", model = "m")
        val (keywords, _) = ProjectBunny().run(client, "海上油菜花2023年直播了多久")
        assertEquals(listOf("直播", "2023"), keywords)
    }

    @Test
    fun `Prometheus 两路检索合并锚定`() {
        val db = lib().db
        val client = LlmClient(dryRun = true, apiKey = "x", model = "m")
        val prometheus = Prometheus()
        val candidates = prometheus.combinedSearch(db, "海上油菜花直播", listOf("直播", "2023"))
        assertTrue("候选应非空", candidates.isNotEmpty())
        assertTrue("a.docx 应命中", candidates.any { it.filename == "a.docx" })
        // dry-run mock 返回候选列表第一条文件名 → 锚定该文件
        val (anchored, _) = prometheus.run(client, "海上油菜花直播", listOf("直播"), candidates)
        assertEquals(candidates.first().filename, anchored.first())
    }

    @Test
    fun `Hanser 基于锚定文档回答`() {
        val db = lib().db
        val client = LlmClient(dryRun = true, apiKey = "x", model = "m")
        val answer = HanserAgent().run(client, "海上油菜花是啥", listOf("a.docx"), db)
        assertTrue(answer.contains("模拟回答"))
        // 空锚定 → 提示未找到
        val empty = HanserAgent().run(client, "问", emptyList(), db)
        assertTrue(empty.contains("未找到"))
    }

    @Test
    fun `端到端 dry-run 流水线`() {
        val db = lib().db
        val client = LlmClient(dryRun = true, apiKey = "x", model = "m")
        val question = "海上油菜花2023年直播了多久"
        // 1. Bunny 关键词
        val (keywords, _) = ProjectBunny().run(client, question)
        // 2. 检索 + Prometheus 锚定
        val prometheus = Prometheus()
        val candidates = prometheus.combinedSearch(db, question, keywords)
        val (anchored, _) = prometheus.run(client, question, keywords, candidates)
        assertTrue(anchored.isNotEmpty())
        // 3. hanser 回答
        val answer = HanserAgent().run(client, question, anchored, db)
        assertTrue(answer.isNotBlank())
    }
}
