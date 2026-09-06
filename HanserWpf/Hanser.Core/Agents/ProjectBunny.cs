using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;

namespace Hanser.Core;

/// <summary>一号 AI：Project Bunny——将用户问题总结为搜索关键词（对应 Python 版 agents/project_bunny.py）。</summary>
public class ProjectBunny
{
    public const string Name = "Project Bunny";

    private const string SystemPrompt = """
        你是检索关键词提取助手。
        请从用户的问题中提取 2-5 个最适合在文档库中检索的中文关键词。
        只返回 JSON：{"keywords": ["关键词1", "关键词2"]}，不要输出任何其他内容。
        """;

    private const double Temperature = 0.2; // 采样温度：越低越稳定
    private const double TopP = 0.9;        // 核采样阈值

    /// <summary>从 userdict.txt 读取自定义专有词（每行第一列为词语；运行时动态读取，对应 Python 版 _load_special_words）。</summary>
    private static List<string> LoadSpecialWords()
    {
        if (!File.Exists(Paths.UserDictPath))
            return new List<string>();
        return File.ReadAllLines(Paths.UserDictPath)
            .Select(l => l.Trim())
            .Where(l => l.Length > 0)
            .Select(l => l.Split(' ', '\t')[0])
            .ToList();
    }

    /// <summary>返回关键词列表（含原始返回 JSON）。</summary>
    public async Task<(List<string> Keywords, JsonElement Raw)> RunAsync(LLMClient client, string question)
    {
        var systemPrompt = SystemPrompt;
        var specialWords = LoadSpecialWords();
        if (specialWords.Count > 0)
            systemPrompt += "\n文档库自定义专有词（提取关键词时优先选用其中的词）：" + string.Join("、", specialWords) + "\n";

        var data = await client.ChatJsonAsync(new List<ChatMessage>
        {
            new() { Role = "system", Content = systemPrompt },
            new() { Role = "user", Content = $"用户问题：{question}" },
        }, temperature: Temperature, topP: TopP);

        var keywords = new List<string>();
        if (data.TryGetProperty("keywords", out var kwArr) && kwArr.ValueKind == JsonValueKind.Array)
        {
            foreach (var k in kwArr.EnumerateArray())
            {
                var s = k.GetString()?.Trim() ?? "";
                if (s.Length > 0)
                    keywords.Add(s);
            }
        }
        if (keywords.Count == 0)
            throw new InvalidOperationException("Project Bunny 未返回有效关键词");
        return (keywords, data);
    }
}
