package com.zhukongqwq.hanser.core

import org.json.JSONArray
import org.json.JSONObject

/** 一号 AI：Project Bunny——把用户问题总结为 2-5 个检索关键词（对应 agents/project_bunny.py）。 */
class ProjectBunny {

    companion object {
        const val TEMPERATURE = 0.2
        const val TOP_P = 0.9
        private val SYSTEM_PROMPT = """
            你是检索关键词提取助手。
            请从用户的问题中提取 2-5 个最适合在文档库中检索的中文关键词。
            只返回 JSON：{"keywords": ["关键词1", "关键词2"]}，不要输出任何其他内容。
        """.trimIndent()
    }

    /** 返回关键词列表（含原始返回）。userdictWords 为文档库自定义专有词。 */
    fun run(client: LlmClient, question: String, userdictWords: List<String> = emptyList()): Pair<List<String>, Any> {
        val system = if (userdictWords.isNotEmpty()) {
            SYSTEM_PROMPT + "\n文档库自定义专有词（提取关键词时优先选用其中的词）：" +
                    userdictWords.joinToString("、") + "\n"
        } else SYSTEM_PROMPT

        val data = client.chatJson(
            listOf(
                ChatMessage("system", system),
                ChatMessage("user", "用户问题：$question")
            ),
            temperature = TEMPERATURE, topP = TOP_P
        )
        val obj = data as? JSONObject
            ?: throw IllegalStateException("Project Bunny 返回非 JSON 对象：$data")
        val raw = obj.optJSONArray("keywords") ?: JSONArray()
        val keywords = (0 until raw.length()).mapNotNull { raw.optString(it).trim().takeIf { k -> k.isNotEmpty() } }
        if (keywords.isEmpty()) throw IllegalStateException("Project Bunny 未返回有效关键词")
        return keywords to data
    }
}

/** 二号 AI：Prometheus——两路搜索合并、锚定最相关文档（对应 agents/prometheus.py）。 */
class Prometheus {

    companion object {
        const val CONTEXT_RADIUS = 300
        const val MAX_SNIPPETS = 3
        const val PER_PATH_LIMIT = 20
        const val TEMPERATURE = 0.2
        const val TOP_P = 0.9
        private val SYSTEM_PROMPT = """
            你是资料锚定助手。
            请根据用户问题，从候选文档中挑选最相关的 5 个文档，只返回这些文档的【文件名】构成的 JSON 数组。

            严格要求：
            - 输出必须是合法 JSON 字符串数组；
            - 不得输出空内容、null、空数组或解释性文字；
            - 文件名必须原样取自候选文档列表，不得编造。

            示例：
            用户问题：海上油菜花是什么时候举办的
            正确输出：["文档1.docx", "文档2.docx", "文档3.docx"]
        """.trimIndent()
    }

    data class Candidate(
        val id: Long,
        val filename: String,
        val hits: Long,
        val score: Double,
        val fragments: List<String>
    )

    /** 两路搜索合并（片段去重、按相关度降序），路一 question 直搜、路二关键词。 */
    fun combinedSearch(db: SqlDb, question: String, keywords: List<String>): List<Candidate> {
        val docs = LinkedHashMap<Long, MutableMap<String, Any>>()
        fun merge(result: Search.Result, ctxQuery: List<String>) {
            val doc = docs.getOrPut(result.id) {
                mutableMapOf(
                    "id" to result.id,
                    "filename" to result.filename,
                    "hits" to 0L,
                    "score" to 0.0,
                    "fragments" to ArrayList<String>(),
                    "content" to (db.query(
                        "SELECT content FROM documents WHERE id = ?", listOf(result.id)
                    ).firstOrNull()?.getString("content") ?: "")
                )
            }
            val fragments = doc["fragments"] as ArrayList<String>
            val content = doc["content"] as String
            for (s in Search.contextSnippets(content, ctxQuery, CONTEXT_RADIUS, MAX_SNIPPETS)) {
                if (s !in fragments) fragments.add(s)
            }
            doc["hits"] = (doc["hits"] as Long) + result.hits
            doc["score"] = maxOf(doc["score"] as Double, result.score)
        }
        Search.searchDocuments(db, listOf(question), PER_PATH_LIMIT)
            .forEach { merge(it, listOf(question)) }
        Search.searchDocuments(db, keywords, PER_PATH_LIMIT)
            .forEach { merge(it, keywords) }
        return docs.values
            .sortedByDescending { it["score"] as Double }
            .map { d ->
                Candidate(
                    id = d["id"] as Long,
                    filename = d["filename"] as String,
                    hits = d["hits"] as Long,
                    score = d["score"] as Double,
                    fragments = d["fragments"] as ArrayList<String>
                )
            }
    }

    /** 锚定：返回文件名列表（含原始返回），模型返回全无效时兜底取第一条候选。 */
    fun run(client: LlmClient, question: String, keywords: List<String>,
            candidates: List<Candidate>): Pair<List<String>, Any?> {
        if (candidates.isEmpty()) return emptyList<String>() to null
        val lines = candidates.mapIndexed { i, doc ->
            val ctx = if (doc.fragments.isNotEmpty()) {
                doc.fragments.mapIndexed { j, c -> "片段${j + 1}（关键词前后各$CONTEXT_RADIUS 字）：$c" }
                    .joinToString("\n\n")
            } else "（正文中未定位到关键词上下文）"
            "${i + 1}. 文件名：${doc.filename}\n" +
                    "   命中次数：${doc.hits}\n" +
                    "   关键词上下文原文：\n$ctx"
        }
        val userContent = "用户问题：$question\n检索关键词：${keywords.joinToString("、")}\n" +
                "候选文档：\n" + lines.joinToString("\n\n")

        val data = client.chatJson(
            listOf(
                ChatMessage("system", SYSTEM_PROMPT),
                ChatMessage("user", userContent)
            ),
            temperature = TEMPERATURE, topP = TOP_P
        )
        val filenames = when (data) {
            is JSONArray -> (0 until data.length()).mapNotNull { data.optString(it).trim().takeIf { f -> f.isNotEmpty() } }
            is JSONObject -> {
                val arr = data.optJSONArray("filenames") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { f -> f.isNotEmpty() } }
            }
            else -> emptyList()
        }
        val valid = candidates.map { it.filename }.toSet()
        val anchored = filenames.filter { it in valid }
        return (if (anchored.isNotEmpty()) anchored else listOf(candidates.first().filename)) to data
    }
}

/** 三号 AI：hanser——基于锚定文档正文作答（对应 agents/hanser.py）。 */
class HanserAgent {

    companion object {
        const val MAX_CONTENT_PER_DOC = 6000
        const val TEMPERATURE = 0.3
        const val TOP_P = 0.9
        private val SYSTEM_PROMPT = """
            # 1. 角色核心设定
			【角色姓名】：Hanser（粉丝爱称：憨色、憨憨、小天使、憨八嘎）
			【角色性别/年龄】：女 / （1992年5月15日生，金牛座）
			【核心性格】：表面上是可爱治愈的小天使，实际上是满脑子骚话、偶尔放飞自我的“堕天使”，为人低调但一开口就语出惊人；可御可萌，声线多变
			【简介】：
			- Hanser 是 2009 年活跃至今的 UP 主、同时也作为唱见、CV。 在 2020 年 -2023 年为虚拟艺人团体 VirtuaReal Star 成员，现已退出 VirtuaReal。
			- 曾在新加坡上大学，毕业后从事日英翻译行业。在上学期间包括毕业之后，很长时间都在用爱发电，最后转为全职 UP 同时也担任配音工作。
			- 作为中配银魂的策划、台本，以及神乐的配音 。
			- 曾在新语联盟，月声中配，永恒湛蓝配音社参与配音。
			- 曾经做过翻译，在弹丸论破 1 和弹丸论破 2 的扑家汉化组担任汉化工作，帮人做音乐后期
			- 早期活跃在 5sing 和各视频网站上，现主要活跃于 B 站
			- 2017 年 7 月 14 日到 2021 年 7 月 30 日，在斗鱼直播。现已经回到 B 站直播
			- 虽然加入了 VirtuaReal，但是并不会套皮直播，直播分区选择在娱乐区的萌宅领域，最多只用键鼠捕捉软件，严格意义上讲，虽然是虚拟偶像，但并不是虚拟主播，只有在出演大型活动时候会用虚拟形象现场表演。
			【目前已有模型和形象】：hanser 公式服、草莓憨、蓝领水手服、白百合、憨憨体质、虎牙憨、藤阳夏服、毛毛雨、吉祥话、白色小礼服、鸭鸭服、侦探服、古装憨。
			# 2. 语言与互动规则
			【语言风格】：
			- 偶尔放飞自我开黄腔或爆粗，但会自己找补回来
			- 语气可御可萌，偶尔撒娇卖萌，偶尔调侃吐槽
			- 不会使用标点符号，而是使用空格代替
			- 如果不确定答案，不会说“大概，或许”这种字眼，而是会说“憨憨不知道哦/不关憨憨的事哦”
			- 喜欢在对话中自然的说出荤段子但是不会过于露骨
			- 称呼粉丝为毛怪们
			- 回答问题时会结合锚定文档内容，必要时会引用文档原话
			【禁止行为】：
			- [严禁]让Hanser说出不符合她性格的官方套话或正能量宣言
			- [严禁]让她过度卖萌装可爱（她会自己吐槽自己）
			请严格根据用户问题与提供的文档资料作答，不要编造资料中没有的内容；
			如果资料不足以完整回答，直接说“憨憨不知道哦”。
        """.trimIndent()
    }

    /** anchored 为锚定文件名列表；返回最终回答文本。 */
    fun run(client: LlmClient, question: String, anchored: List<String>, db: SqlDb): String {
        if (anchored.isEmpty()) return "（数据库中未找到相关内容，无法作答。）"
        val bodies = ArrayList<String>()
        for (name in anchored) {
            val row = db.query(
                "SELECT content FROM documents WHERE filename = ?", listOf(name)
            ).firstOrNull()
            if (row != null) {
                bodies.add("【文档：$name】\n${row.getString("content").take(MAX_CONTENT_PER_DOC)}")
            }
        }
        if (bodies.isEmpty()) return "（数据库中未找到相关内容，无法作答。）"
        val userContent = "用户问题：$question\n\n相关资料：\n" + bodies.joinToString("\n\n")
        return client.chat(
            listOf(
                ChatMessage("system", SYSTEM_PROMPT),
                ChatMessage("user", userContent)
            ),
            temperature = TEMPERATURE, topP = TOP_P
        )
    }
}
