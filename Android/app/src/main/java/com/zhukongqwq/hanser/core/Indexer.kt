package com.zhukongqwq.hanser.core

import java.io.File

/**
 * 文档索引器（对应 Python 版 utils/indexer.py 语义）：
 * 扫描 data 目录全部 *.docx，按 mtime/size 增量入库并分词写词频表。
 *
 * - 增量：文件 mtime（秒）与 size 均未变化则跳过；
 * - force=true：不重解析 docx，用库中已有正文重新分词（改 userdict 后重建词频）；
 * - 已不在目录中的记录与孤儿词频会被清除。
 */
class Indexer(
    private val lib: LibraryDb,
    private val dataDir: File
) {

    data class Stats(
        val added: Int,
        val updated: Int,
        val reindexed: Int,
        val skipped: Int,
        val failed: Int,
        val total: Long
    )

    private val root: File = dataDir.parentFile ?: dataDir

    /** 扫描并索引，返回统计。 */
    fun indexDocuments(force: Boolean = false, log: (String) -> Unit = {},
                       onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }): Stats {
        val files = dataDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("docx", ignoreCase = true) }
            .sortedBy { it.absolutePath }
            .toList()
        val totalFiles = files.size

        var added = 0; var updated = 0; var reindexed = 0
        var skipped = 0; var failed = 0

        val existing = HashSet<String>()
        var batch = 0
        files.forEachIndexed { index, file ->
            val rel = relPath(file)
            existing.add(rel)
            val statMtime = file.lastModified() / 1000.0
            val statSize = file.length()
            val row = lib.findDocument(rel)
            val changed = row == null ||
                    kotlin.math.abs(row.getDouble("mtime") - statMtime) >= 0.01 ||
                    row.getLong("size") != statSize
            try {
                when {
                    !changed && !force -> skipped++
                    !changed && force -> {
                        // force：用库中已有正文重新分词
                        reindexed++
                        replaceTokens(row!!, rel)
                    }
                    else -> {
                        val content = DocxTextExtractor.extract(file.readBytes())
                        val newId = lib.upsertDocument(
                            file.name, rel, content, statMtime, statSize
                        )
                        if (row == null) added++ else updated++
                        lib.replaceDocTokens(newId, Tokenizer.tokenize(content))
                    }
                }
                if (++batch % 50 == 0) log("已处理 $batch 篇…")
                log("[入库] ${file.name}")
            } catch (e: Exception) {
                failed++
                log("  [失败] $rel：${e.message}")
            }
            // 进度：每处理完一篇回调一次（done 为已处理数，total 为扫描到的 docx 总数）
            onProgress(index + 1, totalFiles, file.name)
        }

        lib.db.transaction {
            lib.removeMissing(existing)
            lib.cleanupOrphanTokens()
        }
        val total = lib.countDocuments()
        return Stats(added, updated, reindexed, skipped, failed, total)
    }

    private fun replaceTokens(row: SqlDb.Row, rel: String) {
        val content = row.getString("content")
        lib.replaceDocTokens(row.getLong("id"), Tokenizer.tokenize(content))
        // 保留原 mtime/size（force 不改文件元信息），content 沿用库中正文
        lib.db.exec(
            "UPDATE documents SET content = ? WHERE filepath = ?",
            listOf(content, rel)
        )
    }

    /** 相对根目录路径（data/xxx.docx），与 Python 版 filepath 语义一致。 */
    private fun relPath(file: File): String {
        val rel = file.relativeTo(root).invariantSeparatorsPath
        return if (rel.startsWith("..")) file.absolutePath else rel
    }
}
