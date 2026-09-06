package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryDbTest {

    private fun newDb(): LibraryDb =
        LibraryDb(JdbcSqlDb()) // 内存 sqlite

    @Test
    fun `建表与空库计数`() {
        val lib = newDb()
        assertEquals(0L, lib.countDocuments())
    }

    @Test
    fun `upsert 插入与同路径更新`() {
        val lib = newDb()
        val id1 = lib.upsertDocument("a.docx", "data/a.docx", "内容一", 100.0, 10L)
        assertTrue(id1 > 0)
        // 同 filepath 再插入应更新同一条（数量不变）
        val id2 = lib.upsertDocument("a.docx", "data/a.docx", "内容二", 200.0, 20L)
        assertEquals(id1, id2)
        assertEquals(1L, lib.countDocuments())
    }

    @Test
    fun `replaceDocTokens 重建词频`() {
        val lib = newDb()
        val id = lib.upsertDocument("a.docx", "data/a.docx", "小缘 小缘 鸟鸟", 1.0, 10L)
        lib.replaceDocTokens(id, listOf("小缘", "小缘", "鸟鸟"))
        val rows = lib.db.query(
            "SELECT token, tf FROM doc_tokens WHERE doc_id = ? ORDER BY token", listOf(id)
        )
        assertEquals(2, rows.size)
        assertEquals("小缘", rows[0].getString("token"))
        assertEquals(2L, rows[0].getLong("tf"))
        assertEquals("鸟鸟", rows[1].getString("token"))
        assertEquals(1L, rows[1].getLong("tf"))
        // 重建后旧词消失
        lib.replaceDocTokens(id, listOf("小缘"))
        assertEquals(1L, lib.db.query("SELECT COUNT(*) AS c FROM doc_tokens WHERE doc_id = ?", listOf(id))[0].getLong("c"))
    }

    @Test
    fun `removeMissing 清除不存在的路径`() {
        val lib = newDb()
        lib.upsertDocument("a.docx", "data/a.docx", "A", 1.0, 1L)
        lib.upsertDocument("b.docx", "data/b.docx", "B", 2.0, 2L)
        lib.removeMissing(setOf("data/b.docx"))
        val docs = lib.allDocuments()
        assertEquals(1, docs.size)
        assertEquals("data/b.docx", docs[0].getString("filepath"))
    }

    @Test
    fun `cleanupOrphanTokens 清理孤儿词频`() {
        val lib = newDb()
        val id = lib.upsertDocument("a.docx", "data/a.docx", "内容", 1.0, 1L)
        lib.replaceDocTokens(id, listOf("小缘", "鸟鸟"))
        lib.removeMissing(emptySet()) // 删除全部文档
        lib.cleanupOrphanTokens()
        assertEquals(0L, lib.db.query("SELECT COUNT(*) AS c FROM doc_tokens")[0].getLong("c"))
    }
}
