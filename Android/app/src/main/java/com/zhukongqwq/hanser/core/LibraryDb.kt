package com.zhukongqwq.hanser.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 文档库存取（对应 Python 版 utils/db.py）：documents 与 doc_tokens 两表，
 * 字段语义与 Python/Windows 版完全一致（filepath UNIQUE、tf 词频表）。
 */
class LibraryDb(val db: SqlDb) {

    init {
        db.execDdl(
            """
            CREATE TABLE IF NOT EXISTS documents (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                filename   TEXT    NOT NULL,
                filepath   TEXT    NOT NULL UNIQUE,
                content    TEXT    NOT NULL,
                mtime      REAL    NOT NULL,
                size       INTEGER NOT NULL,
                indexed_at TEXT    NOT NULL
            )
            """.trimIndent()
        )
        db.execDdl(
            """
            CREATE TABLE IF NOT EXISTS doc_tokens (
                doc_id INTEGER NOT NULL,
                token  TEXT    NOT NULL,
                tf     INTEGER NOT NULL,
                PRIMARY KEY (doc_id, token)
            )
            """.trimIndent()
        )
    }

    /** 按 filepath 查询文档记录（id/mtime/size/content），不存在返回 null。 */
    fun findDocument(filepath: String): SqlDb.Row? {
        val rows = db.query(
            "SELECT id, mtime, size, content FROM documents WHERE filepath = ?",
            listOf(filepath)
        )
        return rows.firstOrNull()
    }

    /** 插入或更新（ON CONFLICT(filepath) DO UPDATE），返回文档 id。 */
    fun upsertDocument(filename: String, filepath: String, content: String,
                       mtime: Double, size: Long): Long {
        db.exec(
            """
            INSERT INTO documents (filename, filepath, content, mtime, size, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(filepath) DO UPDATE SET
                filename   = excluded.filename,
                content    = excluded.content,
                mtime      = excluded.mtime,
                size       = excluded.size,
                indexed_at = excluded.indexed_at
            """.trimIndent(),
            listOf(filename, filepath, content, mtime, size,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        )
        return db.query("SELECT id FROM documents WHERE filepath = ?", listOf(filepath))
            .first()!!.getLong("id")
    }

    /** 删除库中不在 existingPaths 集合里的旧记录。 */
    fun removeMissing(existingPaths: Set<String>) {
        if (existingPaths.isEmpty()) {
            db.exec("DELETE FROM documents")
            return
        }
        val placeholders = existingPaths.joinToString(",") { "?" }
        db.exec(
            "DELETE FROM documents WHERE filepath NOT IN ($placeholders)",
            existingPaths.toList()
        )
    }

    /** 重建某文档的词频表（先删后写）。tokens 为完整分词输出。 */
    fun replaceDocTokens(docId: Long, tokens: List<String>) {
        db.exec("DELETE FROM doc_tokens WHERE doc_id = ?", listOf(docId))
        val tf = HashMap<String, Int>()
        for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
        tf.forEach { (token, count) ->
            db.exec(
                "INSERT INTO doc_tokens (doc_id, token, tf) VALUES (?, ?, ?)",
                listOf(docId, token, count)
            )
        }
    }

    /** 清理已不存在文档的 doc_tokens 记录。 */
    fun cleanupOrphanTokens() {
        db.exec("DELETE FROM doc_tokens WHERE doc_id NOT IN (SELECT id FROM documents)")
    }

    /** 库中文档总数。 */
    fun countDocuments(): Long =
        db.query("SELECT COUNT(*) AS c FROM documents").first().getLong("c")

    /** 库中全部文档（id/文件名/路径，按路径排序）。 */
    fun allDocuments(): List<SqlDb.Row> =
        db.query("SELECT id, filename, filepath FROM documents ORDER BY filepath")
}
