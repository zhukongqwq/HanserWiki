using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.Data.Sqlite;

namespace Hanser.Core;

/// <summary>单条搜索结果（对应 Python 版 search_documents 返回的 dict）。</summary>
public class SearchResult
{
    public long Id { get; set; }
    public string Filename { get; set; } = "";
    public string Filepath { get; set; } = "";
    public int Hits { get; set; }
    public List<string> Matched { get; set; } = new();
    public string Snippet { get; set; } = "";
    public double Score { get; set; }
}

/// <summary>搜索模块：jieba 分词 + BM25 相关度排序检索（对应 Python 版 utils/search.py）。</summary>
public static class Search
{
    private const int SnippetRadius = 40;  // 摘要中关键词前后各保留的字符数
    private const double K1 = 1.5;         // BM25 词频饱和参数
    private const double B = 0.75;         // BM25 长度归一化参数

    /// <summary>截取文本中关键词附近的一段上下文作为摘要。</summary>
    public static string MakeSnippet(string text, string keyword, int radius = SnippetRadius)
    {
        var idx = text.IndexOf(keyword, StringComparison.Ordinal);
        if (idx == -1)
            return "";
        var start = Math.Max(0, idx - radius);
        var end = Math.Min(text.Length, idx + keyword.Length + radius);
        var prefix = start > 0 ? "…" : "";
        var suffix = end < text.Length ? "…" : "";
        return prefix + text[start..end].Replace("\n", " ") + suffix;
    }

    /// <summary>关键词列表统一分词，去重保序。</summary>
    public static List<string> QueryTokens(IEnumerable<string> keywords)
    {
        var seen = new HashSet<string>();
        var outList = new List<string>();
        foreach (var kw in keywords)
        {
            foreach (var t in Tokenizer.Tokenize(kw))
            {
                if (seen.Add(t))
                    outList.Add(t);
            }
        }
        return outList;
    }

    /// <summary>执行单值查询（Microsoft.Data.Sqlite 的 SqliteConnection 无 ExecuteScalar 扩展）。</summary>
    private static object? ExecuteScalar(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        return cmd.ExecuteScalar();
    }

    /// <summary>
    /// BM25 相关度排序检索（与 Python 版公式一致：idf * tf*(k1+1) / (tf + k1*(1-b+b*dl/avg))）。
    /// topN 为 null 返回全部命中文档，否则只返回前 topN 条。
    /// </summary>
    public static List<SearchResult> SearchDocuments(SqliteConnection conn,
        IEnumerable<string> keywords, int? topN = null)
    {
        var queryTokens = QueryTokens(keywords);
        if (queryTokens.Count == 0)
            return new List<SearchResult>();

        var totalDocs = Convert.ToInt64(ExecuteScalar(conn, "SELECT COUNT(*) FROM documents") ?? 0L);
        if (totalDocs == 0)
            return new List<SearchResult>();

        var avgLen = Convert.ToDouble(
            ExecuteScalar(conn, "SELECT AVG(dl) FROM (SELECT SUM(tf) AS dl FROM doc_tokens GROUP BY doc_id)") ?? 0.0);

        // 逐 token 查命中文档与词频
        var tfByDoc = new Dictionary<long, Dictionary<string, int>>();   // doc_id -> {token: tf}
        var matchedByDoc = new Dictionary<long, List<string>>();         // doc_id -> [命中 token]
        var hitsByDoc = new Dictionary<long, int>();                     // doc_id -> 总命中次数
        var df = new Dictionary<string, int>();                          // token -> 文档频率

        foreach (var qt in queryTokens)
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "SELECT doc_id, tf FROM doc_tokens WHERE token = $token";
            cmd.Parameters.AddWithValue("$token", qt);
            var rows = new List<(long DocId, int Tf)>();
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
                rows.Add(((long)reader.GetInt64(0), reader.GetInt32(1)));
            df[qt] = rows.Count;
            foreach (var (did, tf) in rows)
            {
                if (!tfByDoc.TryGetValue(did, out var tfs))
                    tfByDoc[did] = tfs = new Dictionary<string, int>();
                tfs[qt] = tf;
                if (!matchedByDoc.TryGetValue(did, out var matched))
                    matchedByDoc[did] = matched = new List<string>();
                matched.Add(qt);
                hitsByDoc[did] = hitsByDoc.GetValueOrDefault(did) + tf;
            }
        }

        if (tfByDoc.Count == 0)
            return new List<SearchResult>();

        // 文档长度与元信息
        var docIds = tfByDoc.Keys.ToList();
        var ph = string.Join(",", docIds.Select((_, i) => $"$d{i}"));
        var docLen = new Dictionary<long, long>();
        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText = $"SELECT doc_id, SUM(tf) AS dl FROM doc_tokens WHERE doc_id IN ({ph}) GROUP BY doc_id";
            for (var i = 0; i < docIds.Count; i++)
                cmd.Parameters.AddWithValue($"$d{i}", docIds[i]);
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
                docLen[reader.GetInt64(0)] = reader.GetInt64(1);
        }
        var docs = new Dictionary<long, (long Id, string Filename, string Filepath)>();
        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText = $"SELECT id, filename, filepath FROM documents WHERE id IN ({ph})";
            for (var i = 0; i < docIds.Count; i++)
                cmd.Parameters.AddWithValue($"$d{i}", docIds[i]);
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
                docs[reader.GetInt64(0)] = (reader.GetInt64(0), reader.GetString(1), reader.GetString(2));
        }

        // BM25 打分
        var scored = new List<(double Score, long DocId)>();
        foreach (var (did, tfs) in tfByDoc)
        {
            var dl = docLen.GetValueOrDefault(did, 0);
            double score = 0;
            foreach (var (qt, tf) in tfs)
            {
                var idf = Math.Log(1 + (totalDocs - df[qt] + 0.5) / (df[qt] + 0.5));
                var denom = avgLen > 0 ? tf + K1 * (1 - B + B * dl / avgLen) : tf + K1;
                score += idf * tf * (K1 + 1) / denom;
            }
            scored.Add((score, did));
        }
        scored.Sort((a, b) => b.Score.CompareTo(a.Score));
        if (topN is not null && scored.Count > topN)
            scored = scored.GetRange(0, topN.Value);

        // 为返回结果生成摘要（批量取正文）
        var topIds = scored.Select(s => s.DocId).ToList();
        var contentMap = new Dictionary<long, string>();
        var ph2 = string.Join(",", topIds.Select((_, i) => $"$c{i}"));
        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText = $"SELECT id, content FROM documents WHERE id IN ({ph2})";
            for (var i = 0; i < topIds.Count; i++)
                cmd.Parameters.AddWithValue($"$c{i}", topIds[i]);
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
                contentMap[reader.GetInt64(0)] = reader.GetString(1);
        }

        var results = new List<SearchResult>();
        foreach (var (score, did) in scored)
        {
            var d = docs[did];
            var first = matchedByDoc[did][0];
            var snippet = MakeSnippet(d.Filename + "\n" + (contentMap.GetValueOrDefault(did) ?? ""), first);
            results.Add(new SearchResult
            {
                Id = did,
                Filename = d.Filename,
                Filepath = d.Filepath,
                Hits = hitsByDoc.GetValueOrDefault(did),
                Matched = matchedByDoc[did],
                Snippet = snippet,
                Score = Math.Round(score, 4),
            });
        }
        return results;
    }

    /// <summary>
    /// 提取文档正文中关键词（分词后 token）命中处的前后各 radius 字原始片段
    /// （对应 Python 版 context_snippets：重叠区间合并、最多 maxSnippets 个）。
    /// </summary>
    public static List<string> ContextSnippets(string content, IEnumerable<string> keywords,
        int radius = 500, int maxSnippets = 3)
    {
        content ??= "";
        var positions = new List<int>();
        foreach (var kw in keywords)
        {
            foreach (var t in Tokenizer.Tokenize(kw))
            {
                var start = 0;
                while (true)
                {
                    var idx = content.IndexOf(t, start, StringComparison.Ordinal);
                    if (idx == -1)
                        break;
                    positions.Add(idx);
                    start = idx + t.Length;
                }
            }
        }
        if (positions.Count == 0)
            return new List<string>();

        // 计算每个命中位置的上下文区间，并按位置排序
        var spans = positions.Distinct()
            .Select(pos => (Start: Math.Max(0, pos - radius), End: Math.Min(content.Length, pos + radius)))
            .OrderBy(s => s.Start)
            .ToList();

        // 合并重叠区间
        var merged = new List<(int Start, int End)>();
        foreach (var (s, e) in spans)
        {
            if (merged.Count > 0 && s <= merged[^1].End)
                merged[^1] = (merged[^1].Start, Math.Max(merged[^1].End, e));
            else
                merged.Add((s, e));
        }

        var snippets = new List<string>();
        foreach (var (s, e) in merged.Take(maxSnippets))
        {
            var prefix = s > 0 ? "…" : "";
            var suffix = e < content.Length ? "…" : "";
            snippets.Add(prefix + content[s..e] + suffix);
        }
        return snippets;
    }
}
