package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictSegmenterTest {

    private fun seg(text: String): DictSegmenter {
        val s = DictSegmenter()
        s.loadDict(text)
        return s
    }

    @Test
    fun `词典整词优先切分`() {
        val s = seg("上海市 500\n上海 100\n今天 400\n天气 300\n非常 200\n很好 200")
        // 上海市 整体成词（词频更高），不切成 上海/市
        assertEquals(listOf("上海市"), s.cut("上海市"))
        val words = s.cut("今天天气非常好")
        assertTrue(words.contains("今天"))
        assertTrue(words.contains("天气"))
        assertTrue(words.contains("非常"))
    }

    @Test
    fun `userdict 注入整词不被切散`() {
        val s = seg("hans 100\ner 50")
        s.addWords(listOf("hanser"))
        val words = s.cut("hanser真可爱")
        assertTrue("hanser 应作为整词切出", words.contains("hanser"))
    }

    @Test
    fun `词典外文本退化为字`() {
        val s = seg("")
        // 空词典：未登录逐字
        val words = s.cut("abcd")
        assertEquals(listOf("a", "b", "c", "d"), words)
    }
}
