package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class TokenizerTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadDictOnce() {
            TestDict.loadOnce()
        }
    }

    @Test
    fun `分词过滤停用词与单字`() {
        val tokens = Tokenizer.tokenize("今天的天气非常好")
        // 不含单字（如「好」）、不含停用词（如「的」），保留实词
        assertTrue("不应含单字：$tokens", tokens.none { it.length == 1 })
        assertTrue("的 应被过滤：$tokens", "的" !in tokens)
        assertTrue("应含 今天：$tokens", tokens.contains("今天"))
        assertTrue("应含 天气：$tokens", tokens.contains("天气"))
    }

    @Test
    fun `userdict 命中词被补充`() {
        Tokenizer.loadUserdict("hanser 100 nz\n海上油菜花 100 nz\n")
        val tokens = Tokenizer.tokenize("hanser 很喜欢海上油菜花")
        assertTrue("userdict 词 hanser 应补入", tokens.contains("hanser"))
        assertTrue("userdict 词 海上油菜花 应补入", tokens.contains("海上油菜花"))
    }

    @Test
    fun `纯标点被过滤`() {
        val tokens = Tokenizer.tokenize("，，，！！！。")
        assertEquals(emptyList<String>(), tokens)
    }

    @Test
    fun `空输入返回空`() {
        assertEquals(emptyList<String>(), Tokenizer.tokenize(""))
        assertEquals(emptyList<String>(), Tokenizer.tokenize(null))
    }
}
