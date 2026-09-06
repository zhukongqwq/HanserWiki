package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `保存读取往返`() {
        val dir = tmp.newFolder("chat-history")
        val archive = ChatArchive(dir)
        val session = ChatSessionRecord(
            title = "第一个问题",
            created = "2026-01-01T00:00:00",
            messages = mutableListOf(
                ChatMessageRecord("user", "问：海上油菜花", keywords = listOf("海上油菜花")),
                ChatMessageRecord("assistant", "答：资料如下", docs = listOf("a.docx"),
                    docInfos = listOf(DocInfo("a.docx", hitText = "2", snippet = "片段…",
                        keywords = listOf("海上油菜花"))))
            )
        )
        archive.save(session)
        val loaded = archive.load()
        assertEquals("第一个问题", loaded!!.title)
        assertEquals(2, loaded.messages.size)
        assertEquals("user", loaded.messages[0].role)
        assertEquals(listOf("海上油菜花"), loaded.messages[0].keywords)
        assertEquals("a.docx", loaded.messages[1].docs[0])
        assertEquals("片段…", loaded.messages[1].docInfos[0].snippet)
        assertEquals("海上油菜花", loaded.messages[1].docInfos[0].keywords[0])
    }

    @Test
    fun `不存在返回 null 清空后 null`() {
        val dir = tmp.newFolder("chat-history")
        val archive = ChatArchive(dir)
        assertNull(archive.load())
        archive.save(ChatSessionRecord(title = "t"))
        assertTrue(archive.load() != null)
        archive.clear()
        assertNull(archive.load())
    }

    @Test
    fun `损坏文件返回 null`() {
        val dir = tmp.newFolder("chat-history")
        val archive = ChatArchive(dir)
        java.io.File(dir, "session.json").writeText("{not json")
        assertNull(archive.load())
    }
}
