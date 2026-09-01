using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Data.Sqlite;

namespace Hanser.Core;

/// <summary>二号 AI：Prometheus——在搜索结果中锚定最相关的文档（对应 Python 版 agents/prometheus.py）。</summary>
public class Prometheus
{
    public const string Name = "Prometheus";

    private const string SystemPrompt = """
        你是资料锚定助手。
        请根据用户问题，从候选文档中挑选最相关的 5 个文档，只返回这些文档的【文件名】构成的 JSON 数组。

        严格要求：
        - 输出必须是合法 JSON 字符串数组；
        - 不得输出空内容、null、空数组或解释性文字；
        - 文件名必须原样取自候选文档列表，不得编造。

        示例：
        用户问题：海上油菜花是什么时候举办的
        正确输出：["文档1.docx", "文档2.docx", "文档3.docx"]
        """;

    private const int ContextRadius = 300; // 关键词上下文各保留的字数
    private const int MaxSnippets = 3;     // 每路搜索对同一文档最多附带的上下文片段数
    private const int PerPathLimit = 20;   // 每路搜索最多取多少条候选（两路各取 20，合并去重后最多 40 条）
    private const double Temperature = 0.2;
    private const double TopP = 0.9;

    private class DocCandidate
    {
        public long Id;
        public string Filename = "";
        public string Content = "";
        public int Hits;
        public double Score;
        public readonly List<string> Fragments = new();
    }

    /// <summary>从两路搜索（用户输入 + 关键词）结果中锚定最相关的文档（对应 Python 版 run）。</summary>
    public async Task<(List<string> Anchored, JsonElement Raw)> RunAsync(
        LLMClient client, string question, List<string> keywords, List<SearchResult> results)
    {
        var docs = await Task.Run(() => CombinedSearch(question, keywords));
        if (docs.Count == 0)
            return (new List<string>(), default);

        var lines = new List<string>();
        for (var i = 0; i < docs.Count; i++)
        {
            var doc = docs[i];
            string ctxText;
            if (doc.Fragments.Count > 0)
                ctxText = string.Join("\n\n", doc.Fragments.Select((f, j) =>
                    $"片段{j + 1}（关键词前后各{ContextRadius}字）：{f}"));
            else
                ctxText = "（正文中未定位到关键词上下文）";
            lines.Add($"{i + 1}. 文件名：{doc.Filename}\n   命中次数：{doc.Hits}\n   关键词上下文原文：\n{ctxText}");
        }

        var userContent = $"用户问题：{question}\n" +
                          $"检索关键词：{string.Join("、", keywords)}\n" +
                          "候选文档：\n" + string.Join("\n\n", lines);

        var data = await client.ChatJsonAsync(new List<ChatMessage>
        {
            new() { Role = "system", Content = SystemPrompt },
            new() { Role = "user", Content = userContent },
        }, temperature: Temperature, topP: TopP);

        var filenames = new List<string>();
        if (data.ValueKind == JsonValueKind.Array)
        {
            foreach (var e in data.EnumerateArray())
            {
                var s = e.GetString();
                if (s != null)
                    filenames.Add(s);
            }
        }
        else if (data.TryGetProperty("filenames", out var arr) && arr.ValueKind == JsonValueKind.Array)
        {
            foreach (var e in arr.EnumerateArray())
            {
                var s = e.GetString();
                if (s != null)
                    filenames.Add(s);
            }
        }

        var valid = docs.Select(d => d.Filename).ToHashSet();
        var anchored = filenames.Where(f => valid.Contains(f)).ToList();
        // 兜底：模型返回的文件名全部无效时取第一条候选
        if (anchored.Count == 0)
            anchored.Add(docs[0].Filename);
        return (anchored, data);
    }

    /// <summary>两路搜索（每路各取 20 条）+ 片段级合并去重，按相关度降序返回（对应 Python 版 _combined_search）。</summary>
    private static List<DocCandidate> CombinedSearch(string question, List<string> keywords)
    {
        using var conn = Db.GetConnection();
        var docs = new Dictionary<long, DocCandidate>();
        // 路一：用户输入直搜
        foreach (var r in Search.SearchDocuments(conn, new[] { question }, PerPathLimit))
            MergeInto(docs, conn, r, new[] { question });
        // 路二：bunny 关键词搜索
        foreach (var r in Search.SearchDocuments(conn, keywords, PerPathLimit))
            MergeInto(docs, conn, r, keywords);
        return docs.Values.OrderByDescending(d => d.Score).ToList();
    }

    /// <summary>把一路搜索的一条结果合并进 docs（按 doc_id 聚合，片段按文本去重）。</summary>
    private static void MergeInto(Dictionary<long, DocCandidate> docs, SqliteConnection conn,
        SearchResult r, IReadOnlyCollection<string> ctxQuery)
    {
        if (!docs.TryGetValue(r.Id, out var doc))
        {
            doc = new DocCandidate
            {
                Id = r.Id,
                Filename = r.Filename,
                Content = Db.GetDocumentContent(conn, r.Id),
            };
            docs[r.Id] = doc;
        }
        // 用该路查询词定位上下文片段（同一文档两路定位词不同，片段可能不同）
        foreach (var s in Search.ContextSnippets(doc.Content, ctxQuery,
                     radius: ContextRadius, maxSnippets: MaxSnippets))
        {
            if (!doc.Fragments.Contains(s)) // 片段文本去重，不同片段全部保留
                doc.Fragments.Add(s);
        }
        doc.Hits += r.Hits;
        doc.Score = Math.Max(doc.Score, r.Score);
    }
}
