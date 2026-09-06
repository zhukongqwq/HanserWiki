package com.zhukongqwq.hanser

import android.content.Context
import com.zhukongqwq.hanser.core.AiConfig
import com.zhukongqwq.hanser.core.ChatArchive
import com.zhukongqwq.hanser.core.GitHubSync
import com.zhukongqwq.hanser.core.Indexer
import com.zhukongqwq.hanser.core.LibraryDb
import com.zhukongqwq.hanser.core.Tokenizer
import com.zhukongqwq.hanser.db.AndroidSqlDb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 应用核心装配：以应用私有目录为根（数据本地化），
 * 初始化文档库目录 / 数据库 / 配置 / 对话存档 / 更新源 / 用户词典。
 */
object AppCore {

    lateinit var dataDir: File
        private set
    private lateinit var dbFile: File
    private lateinit var historyDir: File
    private lateinit var cacheFile: File
    lateinit var userdictFile: File
        private set
    private lateinit var appContext: android.content.Context

    lateinit var library: LibraryDb
        private set
    lateinit var config: AiConfig
        private set
    lateinit var archive: ChatArchive
        private set
    lateinit var githubSync: GitHubSync
        private set

    /** 单线程索引执行器：启动增量扫描 / 重建索引 / 文档更新后索引排队执行，避免并发写库导致 Cursor 越界。 */
    private val indexExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "hanser-index") }

    val isReady: Boolean get() = ::library.isInitialized

    /** 初始化（MainActivity.onCreate 调用一次）。 */
    fun init(context: Context) {
        if (isReady) return
        appContext = context.applicationContext
        val app = appContext
        dataDir = File(app.filesDir, "data").apply { mkdirs() }
        dbFile = File(app.filesDir, "documents.db")
        config = AiConfig(File(app.filesDir, "config.json"))
        historyDir = File(app.filesDir, "chat-history").apply { mkdirs() }
        archive = ChatArchive(historyDir)
        cacheFile = File(app.filesDir, ".sync-list.json")
        userdictFile = File(app.filesDir, "userdict.txt")

        // 首次启动把 assets 中与 Windows 同源的 userdict 拷到可写目录
        if (!userdictFile.exists()) {
            app.assets.open("userdict.txt").use { userdictFile.writeBytes(it.readBytes()) }
        }
        Tokenizer.loadUserdict(userdictFile.readText(Charsets.UTF_8))

        library = LibraryDb(AndroidSqlDb(dbFile))
        githubSync = GitHubSync(dataDir, cacheFile, onDownloaded = { indexInBackground() })
    }

    /** 后台增量扫描文档库（启动与文档更新后调用）：进入索引队列串行执行。 */
    fun indexInBackground() {
        indexExecutor.execute {
            runCatching {
                ensureDictLoaded()
                Indexer(library, dataDir).indexDocuments()
            }
        }
    }

    /** 重建索引（force 全量重分词）：排队执行并阻塞等待结果（供 UI 显示统计）。 */
    fun rebuildIndexNow(): Indexer.Stats {
        val task = java.util.concurrent.Callable<Indexer.Stats> {
            ensureDictLoaded()
            Indexer(library, dataDir).indexDocuments(force = true)
        }
        return indexExecutor.submit(task).get()
    }

    /** 确保词典已加载（从 assets 读 dict.txt + userdict；幂等，可在后台线程调用）。 */
    fun ensureDictLoaded() {
        if (Tokenizer.isDictLoaded) return
        val dictText = appContext.assets.open("dict.txt").use {
            String(it.readBytes(), Charsets.UTF_8)
        }
        Tokenizer.loadDict(dictText)
        if (userdictFile.exists()) Tokenizer.loadUserdict(userdictFile.readText(Charsets.UTF_8))
    }

    /** 保存 userdict（设置页编辑），立即生效。 */
    fun saveUserdict(text: String) {
        userdictFile.writeText(text, Charsets.UTF_8)
        Tokenizer.loadUserdict(text)
    }
}
