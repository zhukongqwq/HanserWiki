package com.zhukongqwq.hanser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocxTextExtractorTest {

    @Test
    fun `docx 正文提取非空且段落分行`() {
        val bytes = File("src/test/resources/sample.docx").readBytes()
        val text = DocxTextExtractor.extract(bytes)
        assertTrue("提取文本不应为空", text.isNotBlank())
        // 首段应为日记标题日期（与文件名 2021年10月10日 对应）
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        assertTrue("首段含日期：$firstLine", firstLine.contains("2021"))
    }

    @Test
    fun `无 document_xml 报错`() {
        try {
            DocxTextExtractor.extract(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // 空 zip 头
            // 不应到达（空 zip 无条目 → 应抛异常）
            assertTrue(false)
        } catch (expected: Exception) {
            // 预期：空 zip 读取时抛异常
        }
    }
}
