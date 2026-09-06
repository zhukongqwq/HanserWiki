package com.zhukongqwq.hanser.core

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * BM25 检索（对应 Python 版 utils/search.py，逐点对齐）：
 * 关键词经统一 tokenize 分词后查询 doc_tokens 表，
 * 按 BM25（k1=1.5, b=0.75）降序返回候选。
 */
object Search {

    const val SNIPPET_RADIUS = 40   // 摘要中关键词前后各保留字符数
    const val K1 = 1.5
    const val B = 0.75

    data class Result(
        val id: Long,
        val filename: String,
        val filepath: String,
        val hits: Long,
        val matched: List<String>,
        val snippet: String,
        val score: Double
    )

    /** 截取文本中关键词附近上下文作为摘要。 */
    fun makeSnippet(text: String, keyword: String, radius: Int = SNIPPET_RADIUS): String {
        val idx = text.indexOf(keyword)
        if (idx == -1) return ""
        val start = maxOf(0, idx - radius)
        val end = minOf(text.length, idx + keyword.length + radius)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).replace("\n", " ") + suffix
    }

    /** 关键词列表统一分词，去重保序。 */
    fun queryTokens(keywords: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (kw in keywords) {
            for (t in Tokenizer.tokenize(kw)) {
                if (seen.add(t)) out.add(t)
            }
        }
        return out
    }

    /** BM25 相关度排序检索。topN 为 null 返回全部。 */
    fun searchDocuments(db: SqlDb, keywords: List<String>, topN: Int? = null): List<Result> {
        val query = queryTokens(keywords)
        if (query.isEmpty()) return emptyList()

        val totalDocs = db.query("SELECT COUNT(*) AS c FROM documents")
            .firstOrNull()?.getLong("c") ?: 0
        if (totalDocs == 0L) return emptyList()

        val avgLen = db.query(
            "SELECT AVG(dl) AS a FROM (SELECT SUM(tf) AS dl FROM doc_tokens GROUP BY doc_id)"
        ).firstOrNull()?.getDouble("a") ?: 0.0

        // 逐 token 查命中文档与词频
        val tfByDoc = HashMap<Long, HashMap<String, Long>>()
        val matchedByDoc = HashMap<Long, MutableList<String>>()
        val hitsByDoc = HashMap<Long, Long>()
        val df = HashMap<String, Int>()
        for (qt in query) {
            val rows = db.query("SELECT doc_id, tf FROM doc_tokens WHERE token = ?", listOf(qt))
            df[qt] = rows.size
            for (r in rows) {
                val did = r.getLong("doc_id")
                tfByDoc.getOrPut(did) { HashMap() }[qt] = r.getLong("tf")
                matchedByDoc.getOrPut(did) { ArrayList() }.add(qt)
                hitsByDoc[did] = (hitsByDoc[did] ?: 0L) + r.getLong("tf")
            }
        }
        if (tfByDoc.isEmpty()) return emptyList()

        val docIds = tfByDoc.keys.toList()
        val ph = docIds.joinToString(",") { "?" }

        // 文档长度与元信息
        val docLen = HashMap<Long, Long>()
        db.query(
            "SELECT doc_id, SUM(tf) AS dl FROM doc_tokens WHERE doc_id IN ($ph) GROUP BY doc_id",
            docIds
        ).forEach { docLen[it.getLong("doc_id")] = it.getLong("dl") }

        val docs = HashMap<Long, SqlDb.Row>()
        db.query(
            "SELECT id, filename, filepath FROM documents WHERE id IN ($ph)", docIds
        ).forEach { docs[it.getLong("id")] = it }

        // BM25 打分：score = sum(idf * tf*(k1+1) / (tf + k1*(1-b+b*dl/avg)))
        val scored = ArrayList<Pair<Double, Long>>()
        for ((did, tfs) in tfByDoc) {
            val dl = docLen[did] ?: 0L
            var score = 0.0
            for ((qt, tf) in tfs) {
                val idf = ln(1.0 + (totalDocs - (df[qt] ?: 0) + 0.5) / ((df[qt] ?: 0) + 0.5))
                val denom = if (avgLen > 0)
                    tf + K1 * (1 - B + B * dl / avgLen)
                else tf + K1
                score += idf * tf * (K1 + 1) / denom
            }
            scored.add(score to did)
        }
        scored.sortByDescending { it.first }
        val top = if (topN != null) scored.take(topN) else scored

        // 生成摘要（批量取正文）
        val topIds = top.map { it.second }
        val contentMap = HashMap<Long, String>()
        if (topIds.isNotEmpty()) {
            val ph2 = topIds.joinToString(",") { "?" }
            db.query(
                "SELECT id, content FROM documents WHERE id IN ($ph2)", topIds
            ).forEach { contentMap[it.getLong("id")] = it.getString("content") }
        }

        return top.map { (score, did) ->
            val d = docs[did] ?: return@map null
            val first = matchedByDoc[did]!!.first()
            val snippet = makeSnippet(
                d.getString("filename") + "\n" + (contentMap[did] ?: ""), first
            )
            Result(
                id = did,
                filename = d.getString("filename"),
                filepath = d.getString("filepath"),
                hits = hitsByDoc[did] ?: 0L,
                matched = matchedByDoc[did] ?: emptyList(),
                snippet = snippet,
                score = (score * 10000).roundToInt() / 10000.0
            )
        }.filterNotNull()
    }

    /** 提取正文中关键词命中处前后 radius 字原始片段（合并重叠，最多 maxSnippets 段）。 */
    fun contextSnippets(content: String, keywords: List<String>,
                        radius: Int = 500, maxSnippets: Int = 3): List<String> {
        if (content.isEmpty()) return emptyList()
        val positions = HashSet<Int>()
        for (kw in keywords) {
            for (t in Tokenizer.tokenize(kw)) {
                var start = 0
                while (true) {
                    val idx = content.indexOf(t, start)
                    if (idx == -1) break
                    positions.add(idx)
                    start = idx + t.length
                }
            }
        }
        if (positions.isEmpty()) return emptyList()

        val spans = positions.map { pos ->
            maxOf(0, pos - radius) to minOf(content.length, pos + radius)
        }.sortedBy { it.first }

        // 合并重叠区间
        val merged = ArrayList<Pair<Int, Int>>()
        for ((s, e) in spans) {
            if (merged.isNotEmpty() && s <= merged.last().second) {
                val last = merged.removeAt(merged.size - 1)
                merged.add(last.first to maxOf(last.second, e))
            } else {
                merged.add(s to e)
            }
        }

        return merged.take(maxSnippets).map { (s, e) ->
            val prefix = if (s > 0) "…" else ""
            val suffix = if (e < content.length) "…" else ""
            prefix + content.substring(s, e) + suffix
        }
    }
}
