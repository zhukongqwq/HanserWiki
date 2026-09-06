package com.zhukongqwq.hanser.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 折叠文档详情（命中信息/摘要/关键词），字段对齐 Windows ChatArchive。 */
data class DocInfo(
    val filename: String,
    val hitText: String = "",
    val snippet: String = "",
    val keywords: List<String> = emptyList()
)

/** 一条对话消息（role: user/assistant）。 */
data class ChatMessageRecord(
    val role: String,
    val content: String,
    val docs: List<String> = emptyList(),
    val docInfos: List<DocInfo> = emptyList(),
    val keywords: List<String>? = null
)

/** 一个会话（标题默认取第一个问题）。 */
data class ChatSessionRecord(
    val title: String = "",
    val created: String = "",
    val updated: String = "",
    val messages: MutableList<ChatMessageRecord> = ArrayList()
)

/**
 * 对话存档（对应 Windows 版 ChatArchive）：chat-history 目录下一个 json 文件，
 * 结构字段与 Windows 版一致（Title/Created/Updated/Messages）。
 */
class ChatArchive(private val dir: File) {

    private val sessionFile: File = File(dir, "session.json")

    /** 读取会话存档；不存在或损坏返回 null。 */
    fun load(): ChatSessionRecord? {
        if (!sessionFile.exists()) return null
        return try {
            val obj = JSONObject(sessionFile.readText(Charsets.UTF_8))
            val messages = JSONArray()
            val arr = obj.optJSONArray("messages") ?: messages
            val list = ArrayList<ChatMessageRecord>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                list.add(ChatMessageRecord(
                    role = m.optString("role", "user"),
                    content = m.optString("content", ""),
                    docs = jsonStringList(m.optJSONArray("docs")),
                    docInfos = jsonDocInfos(m.optJSONArray("doc_infos")),
                    keywords = if (m.has("keywords") && !m.isNull("keywords"))
                        jsonStringList(m.optJSONArray("keywords")) else null
                ))
            }
            ChatSessionRecord(
                title = obj.optString("title", ""),
                created = obj.optString("created", ""),
                updated = obj.optString("updated", ""),
                messages = list
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 保存会话存档。 */
    fun save(session: ChatSessionRecord) {
        dir.mkdirs()
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val obj = JSONObject()
        obj.put("title", session.title)
        obj.put("created", session.created.ifEmpty { now })
        obj.put("updated", now)
        val arr = JSONArray()
        session.messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
                put("docs", JSONArray(m.docs))
                put("doc_infos", JSONArray(m.docInfos.map {
                    JSONObject().apply {
                        put("filename", it.filename)
                        put("hit_text", it.hitText)
                        put("snippet", it.snippet)
                        put("keywords", JSONArray(it.keywords))
                    }
                }))
                if (m.keywords != null) put("keywords", JSONArray(m.keywords))
            })
        }
        obj.put("messages", arr)
        sessionFile.writeText(obj.toString(2), Charsets.UTF_8)
    }

    /** 清空全部对话历史。 */
    fun clear() {
        sessionFile.delete()
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }

    private fun jsonDocInfos(arr: JSONArray?): List<DocInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                DocInfo(
                    filename = o.optString("filename", ""),
                    hitText = o.optString("hit_text", ""),
                    snippet = o.optString("snippet", ""),
                    keywords = jsonStringList(o.optJSONArray("keywords"))
                )
            } catch (_: Exception) { null }
        }
    }
}
