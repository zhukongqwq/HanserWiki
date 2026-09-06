package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IndexerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seedData(): File {
        val dataDir = tmp.newFolder("data")
        val sample = File("src/test/resources/sample.docx").readBytes()
        File(dataDir, "a.docx").writeBytes(sample)
        File(dataDir, "b.docx").writeBytes(sample)
        return dataDir
    }

    @Test
    fun `首建全入库 再跑全跳过`() {
        val dataDir = seedData()
        val lib = LibraryDb(JdbcSqlDb())
        val indexer = Indexer(lib, dataDir)

        val first = indexer.indexDocuments()
        assertEquals(2, first.added)
        assertEquals(0, first.failed)
        assertEquals(2L, first.total)

        val second = indexer.indexDocuments()
        assertEquals(2, second.skipped)
        assertEquals(0, second.added)
    }

    @Test
    fun `force 重分词已存在文档`() {
        val dataDir = seedData()
        val lib = LibraryDb(JdbcSqlDb())
        val indexer = Indexer(lib, dataDir)
        indexer.indexDocuments()

        val forced = indexer.indexDocuments(force = true)
        assertEquals(2, forced.reindexed)
        assertEquals(0, forced.added)
    }

    @Test
    fun `mtime 变化触发更新 删除触发清除`() {
        val dataDir = seedData()
        val lib = LibraryDb(JdbcSqlDb())
        val indexer = Indexer(lib, dataDir)
        indexer.indexDocuments()
        assertEquals(2L, lib.countDocuments())

        // 修改 a.docx（内容替换 → 大小可能变，mtime 一定变）
        File(dataDir, "a.docx").writeBytes(File("src/test/resources/sample.docx").readBytes())
        Thread.sleep(30)
        val upd = indexer.indexDocuments()
        assertEquals(1, upd.updated)

        // 删除 b.docx
        File(dataDir, "b.docx").delete()
        val rem = indexer.indexDocuments()
        assertEquals(1L, rem.total)
        assertTrue(rem.skipped >= 1)
    }
}
