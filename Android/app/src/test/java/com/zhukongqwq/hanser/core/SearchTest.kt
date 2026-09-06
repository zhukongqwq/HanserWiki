package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SearchTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadDictOnce() {
            TestDict.loadOnce()
        }
    }

    /** 造一个 3 篇文档的小库。 */
    private fun lib(): LibraryDb {
        val lib = LibraryDb(JdbcSqlDb())
        seed(lib, "a.docx", "小缘 小缘 演唱会 开心")
        seed(lib, "b.docx", "小缘 演唱会")
        seed(lib, "c.docx", "鸟鸟 散步")
        return lib
    }

    private fun seed(lib: LibraryDb, name: String, content: String) {
        val id = lib.upsertDocument(name, "data/$name", content, 1.0, content.length.toLong())
        lib.replaceDocTokens(id, content.split(" ").filter { it.isNotBlank() })
    }

    @Test
    fun `BM25 排序命中与摘要`() {
        val lib = lib()
        val results = Search.searchDocuments(lib.db, listOf("小缘"))
        assertEquals(2, results.size)
        // a 词频 2 > b 词频 1，a 应排前
        assertEquals("a.docx", results[0].filename)
        assertEquals("b.docx", results[1].filename)
        assertEquals(2L, results[0].hits)
        assertTrue(results[0].score > results[1].score)
        assertTrue(results[0].snippet.contains("小缘"))
        // c 不命中
        assertTrue(results.none { it.filename == "c.docx" })
    }

    @Test
    fun `topN 截断`() {
        val lib = lib()
        val one = Search.searchDocuments(lib.db, listOf("演唱会"), topN = 1)
        assertEquals(1, one.size)
    }

    @Test
    fun `无命中返回空`() {
        val lib = lib()
        assertTrue(Search.searchDocuments(lib.db, listOf("不存在词xyz")).isEmpty())
    }

    @Test
    fun `makeSnippet 截取命中上下文`() {
        val text = "今天天气很好，我们一起去小缘家听演唱会，非常开心。"
        val snippet = Search.makeSnippet(text, "小缘")
        assertTrue(snippet.contains("小缘"))
        // 全文短于 radius*2：返回含命中词的全文本（无省略号也算命中）
        // 超长文本验证省略号
        val long = ("今天天气很好，我们一起去小缘家听演唱会，非常开心。" * 20)
        val longSnippet = Search.makeSnippet(long, "小缘")
        assertTrue(longSnippet.contains("小缘"))
        assertTrue(longSnippet.contains("…"))
    }

    private operator fun String.times(n: Int): String = buildString { repeat(n) { append(this@times) } }

    @Test
    fun `contextSnippets 返回上下文片段且合并`() {
        val lib = lib()
        val content = "今天小缘去听演唱会，回来路上小缘买了鸟鸟玩偶。"
        val snippets = Search.contextSnippets(content, listOf("小缘"), radius = 4)
        assertTrue(snippets.isNotEmpty())
        assertTrue(snippets.size <= 3)
        assertTrue(snippets.any { it.contains("小缘") })
    }

    @Test
    fun `queryTokens 去重保序`() {
        val tokens = Search.queryTokens(listOf("小缘", "小缘 演唱会"))
        assertEquals("实际分词：$tokens", listOf("小缘", "演唱会"), tokens)
    }
}
