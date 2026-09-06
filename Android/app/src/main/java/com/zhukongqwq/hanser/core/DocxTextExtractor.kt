package com.zhukongqwq.hanser.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * docx 正文提取器（对齐 Python 版 indexer.extract_text / python-docx）。
 *
 * docx = zip，正文在 word/document.xml：顶层 <w:body> 的直接子段落 <w:p>
 * 收集其全部文本节点 <w:t>（忽略表格、图片）。仅取非空段落，按行合并。
 */
object DocxTextExtractor {

    private val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }

    /** 从 docx 字节流提取正文段落文本。 */
    fun extract(bytes: ByteArray): String {
        val documentXml = readEntry(bytes, "word/document.xml")
            ?: throw IllegalArgumentException("docx 中缺少 word/document.xml")
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(documentXml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val body = directChild(root, "body")
            ?: throw IllegalArgumentException("docx 中缺少 body")
        val paragraphs = ArrayList<String>()
        directChildren(body).forEach { child ->
            if (child.localName == "p") {
                val text = collectText(child).trim()
                if (text.isNotEmpty()) paragraphs.add(text)
            }
        }
        return paragraphs.joinToString("\n")
    }

    /** 读 zip 内指定条目文本（UTF-8）。 */
    private fun readEntry(bytes: ByteArray, entryName: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    val out = zip.readBytes()
                    return String(out, Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun directChild(e: Element, localName: String): Element? {
        var n = e.firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE && n.localName == localName)
                return n as Element
            n = n.nextSibling
        }
        return null
    }

    private fun directChildren(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var n = e.firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
            n = n.nextSibling
        }
        return out
    }

    /** 收集节点下全部 <w:t> 文本（含超链接内的文本节点）。 */
    private fun collectText(e: Element): String {
        val sb = StringBuilder()
        val nodes = e.getElementsByTagNameNS("*", "t")
        for (i in 0 until nodes.length) {
            sb.append(nodes.item(i).textContent)
        }
        return sb.toString()
    }
}
