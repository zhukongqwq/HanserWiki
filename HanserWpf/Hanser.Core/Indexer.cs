using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Microsoft.Data.Sqlite;

namespace Hanser.Core;

/// <summary>索引模块：扫描 data 目录，提取 docx 正文、分词并写入数据库（增量更新，对应 Python 版 utils/indexer.py）。</summary>
public static class Indexer
{
    /// <summary>计划范围内仅索引 docx；其他类型文件仅统计数量用于提示。</summary>
    /// <summary>支持索引的文件扩展名（docx / markdown / 纯文本）。</summary>
    private static readonly HashSet<string> SupportedExts = new(StringComparer.OrdinalIgnoreCase)
    { ".docx", ".md", ".txt" };

    /// <summary>计划范围内仅索引上述扩展名；其他类型文件仅统计数量用于提示。</summary>
    private static readonly HashSet<string> SkippedExts = new(StringComparer.OrdinalIgnoreCase)
    { ".doc" };

    /// <summary>索引统计信息（对应 Python 版返回的 dict）。</summary>
    public class IndexStats
    {
        public int Added { get; set; }
        public int Updated { get; set; }
        public int Reindexed { get; set; }
        public int Skipped { get; set; }
        public int Failed { get; set; }
        public long Total { get; set; }
    }

    /// <summary>提取文档正文：docx 用 OpenXML（body 直接子段落，与 python-docx 对齐）；.md/.txt 直接读文本（UTF-8）。</summary>
    public static string ExtractText(string path)
    {
        var ext = Path.GetExtension(path).ToLowerInvariant();
        if (ext == ".docx")
        {
            var paragraphs = new List<string>();
            using (var doc = WordprocessingDocument.Open(path, false))
            {
                var body = doc.MainDocumentPart?.Document?.Body;
                if (body == null)
                    return "";
                foreach (var para in body.Elements<Paragraph>())
                {
                    var text = para.InnerText.Trim();
                    if (text.Length > 0)
                        paragraphs.Add(text);
                }
            }
            return string.Join("\n", paragraphs);
        }
        return System.IO.File.ReadAllText(path, System.Text.Encoding.UTF8).Trim();
    }

    /// <summary>
    /// 扫描 data 目录下所有 docx，增量写入数据库，返回统计信息。
    /// 增量规则：文件 mtime 与 size 均未变化则跳过重新提取；
    /// force=True：对未变化的文档不重新解析 docx，但用库中正文重新分词（修改 userdict.txt 后需 force 重建）。
    /// progress：进度回调 (已处理数, 总数, 当前文件名)，用于 GUI 进度条展示。
    /// </summary>
    public static IndexStats IndexDocuments(Action<string>? log = null, bool force = false,
        Action<int, int, string>? progress = null)
    {
        var dataDir = Paths.DataDir;
        using var conn = Db.GetConnection();
        Db.InitDb(conn);

        var existing = new HashSet<string>();
        var stats = new IndexStats();

        // 先枚举全部支持的文件（docx/md/txt）得到总数，供进度计算
        var files = Directory.EnumerateFiles(dataDir, "*", SearchOption.AllDirectories)
            .Where(p => SupportedExts.Contains(Path.GetExtension(p)))
            .OrderBy(p => p).ToList();
        var total = files.Count;
        var processed = 0;

        foreach (var docxPath in files)
        {
            processed++;
            progress?.Invoke(processed, total, Path.GetFileName(docxPath));
            var rel = Path.GetRelativePath(Paths.WpfRoot, docxPath).Replace('\\', '/');
            existing.Add(rel);
            var stat = new FileInfo(docxPath);
            var mtime = stat.LastWriteTimeUtc.Ticks / (double)TimeSpan.TicksPerSecond;
            var size = stat.Length;

            long docId;
            var changed = true;
            var isNew = false;
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = "SELECT id, mtime, size FROM documents WHERE filepath = $path";
                cmd.Parameters.AddWithValue("$path", rel);
                using var reader = cmd.ExecuteReader();
                if (reader.Read())
                {
                    var oldId = reader.GetInt64(0);
                    var oldMtime = reader.GetDouble(1);
                    var oldSize = reader.GetInt64(2);
                    changed = Math.Abs(oldMtime - mtime) >= 0.01 || oldSize != size;
                    if (!changed)
                        docId = oldId; // 未变化
                    else
                        docId = oldId; // 变化时先沿用旧 id，下面更新记录
                    isNew = false;
                }
                else
                {
                    docId = 0;
                    isNew = true;
                }
            }

            if (!changed && !force)
            {
                stats.Skipped++;
                continue;
            }

            string content;
            if (!changed && force)
            {
                // force 模式：不重新解析 docx，用库中已有正文重新分词（词典可能已更新）
                content = Db.GetDocumentContent(conn, docId);
                stats.Reindexed++;
            }
            else
            {
                try
                {
                    content = ExtractText(docxPath);
                }
                catch (Exception exc)
                {
                    stats.Failed++;
                    log?.Invoke($"  [失败] {rel}: {exc.Message}");
                    continue;
                }
                Db.UpsertDocument(conn, Path.GetFileName(docxPath), rel, content, mtime, size);
                docId = GetDocIdByPath(conn, rel);
                if (isNew)
                    stats.Added++;
                else
                    stats.Updated++;
            }
            Db.ReplaceDocTokens(conn, docId, Tokenizer.Tokenize(content));
            log?.Invoke($"  [入库] {Path.GetFileName(docxPath)}");
        }

        Db.RemoveMissing(conn, existing);
        Db.CleanupOrphanTokens(conn);
        stats.Total = Db.CountDocuments(conn);

        // 统计未索引的非 docx 文件（提示用）
        var skippedFiles = Directory.EnumerateFiles(dataDir)
            .Where(p => File.Exists(p) && SkippedExts.Contains(Path.GetExtension(p)))
            .Select(Path.GetFileName)
            .ToList();

        log?.Invoke($"完成：新增 {stats.Added}，更新 {stats.Updated}，重分词 {stats.Reindexed}，" +
                    $"未变化跳过 {stats.Skipped}，失败 {stats.Failed}，库中总数 {stats.Total}");
        if (skippedFiles.Count > 0)
            log?.Invoke($"提示：{skippedFiles.Count} 个非 docx 文件未索引（{string.Join("、", skippedFiles.Take(5))}）");

        return stats;
    }

    private static long GetDocIdByPath(SqliteConnection conn, string rel)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT id FROM documents WHERE filepath = $path";
        cmd.Parameters.AddWithValue("$path", rel);
        return (long)(cmd.ExecuteScalar() ?? 0L);
    }
}
