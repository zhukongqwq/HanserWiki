package com.zhukongqwq.hanser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhukongqwq.hanser.core.ChatMessageRecord
import com.zhukongqwq.hanser.core.ChatSessionRecord
import com.zhukongqwq.hanser.core.DocInfo
import com.zhukongqwq.hanser.core.HanserAgent
import com.zhukongqwq.hanser.core.LlmClient
import com.zhukongqwq.hanser.core.ProjectBunny
import com.zhukongqwq.hanser.core.Prometheus
import com.zhukongqwq.hanser.core.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 界面消息（含折叠文档信息）；id 唯一，供 LazyColumn 稳定 key（防相同内容重复导致 Key 冲突崩溃）。 */
data class UiMessage(
    val role: String,               // user / assistant / system
    val content: String,
    val docs: List<UiDoc> = emptyList(),
    val keywords: List<String>? = null,
    val id: Long = 0L               // 唯一 id（追加时分配）
)

/** 折叠文档（文件名/命中数/摘要）。 */
data class UiDoc(val filename: String, val hits: Long = 0, val snippet: String = "")

/**
 * 主界面状态与问答流水线执行（对应 Windows 版 SendButton 逻辑）。
 */
class MainViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _stage = MutableStateFlow<String?>(null)
    val stage: StateFlow<String?> = _stage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _expandedDocs = MutableStateFlow<UiMessage?>(null)
    val expandedDocs: StateFlow<UiMessage?> = _expandedDocs.asStateFlow()

    private var nextId = 0L

    init {
        // 启动加载历史（AppCore 未就绪时跳过，避免未初始化访问）
        val session = if (AppCore.isReady) AppCore.archive.load() else null
        if (session != null && session.messages.isNotEmpty()) {
            _messages.value = session.messages.map {
                UiMessage(role = it.role, content = it.content, id = ++nextId,
                    docs = it.docInfos.map { d -> UiDoc(d.filename, d.hitText.toLongOrNull() ?: 0, d.snippet) },
                    keywords = it.keywords)
            }
        }
    }

    fun send(questionRaw: String) {
        val question = questionRaw.trim()
        if (question.isEmpty() || _busy.value) return
        val cfg = AppCore.config
        append(UiMessage("user", question))
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dryRun = cfg.loadDryRun()
                AppCore.ensureDictLoaded() // 兜底：确保切词可用（通常已由后台索引加载）
                // 1. 小兔：提取关键词
                _stage.value = "小兔正在提取检索关键词…"
                val bunny = LlmClient(dryRun = dryRun, agentName = "bunny", config = cfg)
                val (keywords, _) = ProjectBunny().run(bunny, question, Tokenizer.userdictWords)

                // 2. 检索 + 普罗米修斯：两路合并锚定
                _stage.value = "普罗米修斯正在锚定相关资料…"
                val db = AppCore.library.db
                val prometheus = Prometheus()
                val candidates = prometheus.combinedSearch(db, question, keywords)
                val (anchored, _) = prometheus.run(
                    LlmClient(dryRun = dryRun, agentName = "prometheus", config = cfg),
                    question, keywords, candidates
                )

                // 3. 憨憨：基于锚定文档作答
                _stage.value = "憨憨正在整理回答…"
                val answer = HanserAgent().run(
                    LlmClient(dryRun = dryRun, agentName = "hanser", config = cfg),
                    question, anchored, db
                )

                val docs = candidates.take(5).map {
                    UiDoc(it.filename, it.hits, it.fragments.firstOrNull().orEmpty().take(120))
                }
                append(UiMessage("assistant", answer, docs = docs, keywords = keywords))
            } catch (e: Exception) {
                append(UiMessage("system", "（出错了：${e.message}）"))
            } finally {
                _stage.value = null
                _busy.value = false
                saveArchive()
            }
        }
    }

    fun toggleDocs(message: UiMessage) {
        _expandedDocs.value = if (_expandedDocs.value == message) null else message
    }

    fun clearDocsOverlay() {
        _expandedDocs.value = null
    }

    fun clearHistory() {
        _messages.value = emptyList()
        AppCore.archive.clear()
    }

    private fun append(m: UiMessage) {
        // 分配唯一 id（防止相同内容消息 key 冲突）
        val withId = if (m.id == 0L) m.copy(id = ++nextId) else m
        _messages.value = _messages.value + withId
    }

    private fun saveArchive() {
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val firstUser = msgs.firstOrNull { it.role == "user" }?.content.orEmpty()
        val records = msgs.map { m ->
            ChatMessageRecord(
                role = m.role,
                content = m.content,
                docs = m.docs.map { it.filename },
                docInfos = m.docs.map { DocInfo(it.filename, it.hits.toString(), it.snippet) },
                keywords = m.keywords
            )
        }
        val existing = AppCore.archive.load()
        AppCore.archive.save(ChatSessionRecord(
            title = firstUser.take(30),
            created = existing?.created ?: "",
            messages = records.toMutableList()
        ))
    }
}
