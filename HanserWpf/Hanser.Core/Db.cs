using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.Data.Sqlite;

namespace Hanser.Core;

/// <summary>数据库模块：SQLite 连接、建表与文档记录读写（对应 Python 版 utils/db.py）。</summary>
public static class Db
{
    /// <summary>documents 表结构（与 Python 版完全一致）。</summary>
    public const string SchemaDocuments = """
        CREATE TABLE IF NOT EXISTS documents (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            filename   TEXT    NOT NULL,
            filepath   TEXT    NOT NULL UNIQUE,
            content    TEXT    NOT NULL,
            mtime      REAL    NOT NULL,
            size       INTEGER NOT NULL,
            indexed_at TEXT    NOT NULL
        )
        """;

    /// <summary>doc_tokens 表结构（与 Python 版完全一致）。</summary>
    public const string SchemaTokens = """
        CREATE TABLE IF NOT EXISTS doc_tokens (
            doc_id INTEGER NOT NULL,
            token  TEXT    NOT NULL,
            tf     INTEGER NOT NULL,
            PRIMARY KEY (doc_id, token)
        )
        """;

    public static SqliteConnection GetConnection(string? dbPath = null)
    {
        var conn = new SqliteConnection($"Data Source={dbPath ?? Paths.DbPath}");
        conn.Open();
        return conn;
    }

    /// <summary>建表（已存在则跳过）。</summary>
    public static void InitDb(SqliteConnection conn)
    {
        using var c1 = conn.CreateCommand();
        c1.CommandText = SchemaDocuments;
        c1.ExecuteNonQuery();
        using var c2 = conn.CreateCommand();
        c2.CommandText = SchemaTokens;
        c2.ExecuteNonQuery();
    }

    /// <summary>按 filepath 插入或更新一条文档记录。</summary>
    public static void UpsertDocument(SqliteConnection conn, string filename, string filepath,
        string content, double mtime, long size)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO documents (filename, filepath, content, mtime, size, indexed_at)
            VALUES ($filename, $filepath, $content, $mtime, $size, $indexed_at)
            ON CONFLICT(filepath) DO UPDATE SET
                filename   = excluded.filename,
                content    = excluded.content,
                mtime      = excluded.mtime,
                size       = excluded.size,
                indexed_at = excluded.indexed_at
            """;
        cmd.Parameters.AddWithValue("$filename", filename);
        cmd.Parameters.AddWithValue("$filepath", filepath);
        cmd.Parameters.AddWithValue("$content", content);
        cmd.Parameters.AddWithValue("$mtime", mtime);
        cmd.Parameters.AddWithValue("$size", size);
        cmd.Parameters.AddWithValue("$indexed_at", DateTime.Now.ToString("yyyy-MM-ddTHH:mm:ss"));
        cmd.ExecuteNonQuery();
    }

    /// <summary>删除数据库中已不在 data 目录下的旧记录。</summary>
    public static void RemoveMissing(SqliteConnection conn, ICollection<string> existingPaths)
    {
        if (existingPaths.Count == 0)
        {
            using var c = conn.CreateCommand();
            c.CommandText = "DELETE FROM documents";
            c.ExecuteNonQuery();
            return;
        }
        var ph = string.Join(",", existingPaths.Select((_, i) => $"$p{i}"));
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $"DELETE FROM documents WHERE filepath NOT IN ({ph})";
        var i = 0;
        foreach (var p in existingPaths)
            cmd.Parameters.AddWithValue($"$p{i++}", p);
        cmd.ExecuteNonQuery();
    }

    /// <summary>重建某文档的词频表：先删除旧记录，再按分词结果批量写入 token 与词频。</summary>
    public static void ReplaceDocTokens(SqliteConnection conn, long docId, ICollection<string> tokens)
    {
        using (var del = conn.CreateCommand())
        {
            del.CommandText = "DELETE FROM doc_tokens WHERE doc_id = $id";
            del.Parameters.AddWithValue("$id", docId);
            del.ExecuteNonQuery();
        }
        var tf = tokens.GroupBy(t => t).ToDictionary(g => g.Key, g => g.Count());
        using var tx = conn.BeginTransaction();
        using var ins = conn.CreateCommand();
        ins.Transaction = tx;
        ins.CommandText = "INSERT INTO doc_tokens (doc_id, token, tf) VALUES ($id, $token, $tf)";
        var pId = ins.Parameters.Add("$id", SqliteType.Integer);
        var pToken = ins.Parameters.Add("$token", SqliteType.Text);
        var pTf = ins.Parameters.Add("$tf", SqliteType.Integer);
        foreach (var kv in tf)
        {
            pId.Value = docId;
            pToken.Value = kv.Key;
            pTf.Value = kv.Value;
            ins.ExecuteNonQuery();
        }
        tx.Commit();
    }

    /// <summary>清理已不存在文档的 doc_tokens 记录（documents 删除后调用）。</summary>
    public static void CleanupOrphanTokens(SqliteConnection conn)
    {
        using var c = conn.CreateCommand();
        c.CommandText = "DELETE FROM doc_tokens WHERE doc_id NOT IN (SELECT id FROM documents)";
        c.ExecuteNonQuery();
    }

    /// <summary>返回库中文档总数。</summary>
    public static long CountDocuments(SqliteConnection conn)
    {
        using var c = conn.CreateCommand();
        c.CommandText = "SELECT COUNT(*) FROM documents";
        return (long)(c.ExecuteScalar() ?? 0L);
    }

    /// <summary>按 id 读取文档正文。</summary>
    public static string GetDocumentContent(SqliteConnection conn, long id)
    {
        using var c = conn.CreateCommand();
        c.CommandText = "SELECT content FROM documents WHERE id = $id";
        c.Parameters.AddWithValue("$id", id);
        return c.ExecuteScalar() as string ?? "";
    }

    /// <summary>按文件名读取文档正文（hanser 锚定用）。</summary>
    public static string GetDocumentContentByFilename(SqliteConnection conn, string filename)
    {
        using var c = conn.CreateCommand();
        c.CommandText = "SELECT content FROM documents WHERE filename = $name";
        c.Parameters.AddWithValue("$name", filename);
        return c.ExecuteScalar() as string ?? "";
    }
}
