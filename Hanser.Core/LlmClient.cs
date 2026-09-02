using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using YamlDotNet.Serialization;

namespace Hanser.Core;

/// <summary>对话消息（OpenAI 兼容格式）。</summary>
public class ChatMessage
{
    public string Role { get; set; } = "user";
    public string Content { get; set; } = "";
}

/// <summary>LLM 客户端：OpenAI 兼容接口封装，支持 dry-run 模拟与按 AI（agent）独立配置（对应 Python 版 utils/llm.py）。</summary>
public class LLMClient
{
    private const string DefaultBaseUrl = "https://api.openai.com/v1";
    private const int DefaultOutputLimit = 8192; // 未知名模型的保守输出上限

    /// <summary>常见模型的最大输出 token 上限（前缀匹配，仍可被配置值覆盖）。</summary>
    private static readonly (string Prefix, int Limit)[] ModelOutputLimits =
    {
        ("deepseek-v4-flash", 1000000),
        ("deepseek-pro", 1000000),
        ("deepseek-flash-vision-exp", 1000000),
        ("gpt-4o", 16384),
        ("gpt-4", 8192),
        ("gpt-3.5", 4096),
        ("qwen", 8192),
        ("glm", 8192),
        ("kimi", 8192),
        ("moonshot", 8192),
        ("claude", 8192),
        ("gemini", 8192),
        ("ernie", 8192),
    };

    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(10) };
    private readonly bool _dryRun;

    public string BaseUrl { get; }
    public string ApiKey { get; }
    public string Model { get; }

    /// <summary>该模型本次会话生效的最大输出 token（配置 &gt; agent 配置 &gt; 全局配置 &gt; 模型映射表 &gt; 保守默认）。</summary>
    public int MaxOutputTokens { get; }

    public LLMClient(string? baseUrl = null, string? apiKey = null, string? model = null,
        bool dryRun = false, string? configPath = null, string? agentName = null, int? maxTokens = null)
    {
        var cfg = LoadConfig(configPath);                                   // 全局默认（openai 段）
        var agent = agentName == null ? null : LoadAgentConfig(agentName, configPath); // 该 AI 的独立配置（可空）

        BaseUrl = FirstNonEmpty(baseUrl, agent?.BaseUrl, cfg.BaseUrl,
            Environment.GetEnvironmentVariable("OPENAI_BASE_URL"), DefaultBaseUrl);
        ApiKey = FirstNonEmpty(apiKey, agent?.ApiKey, cfg.ApiKey,
            Environment.GetEnvironmentVariable("OPENAI_API_KEY"), "");
        Model = FirstNonEmpty(model, agent?.Model, cfg.Model,
            Environment.GetEnvironmentVariable("OPENAI_MODEL"), "");
        MaxOutputTokens = ResolveMaxTokens(maxTokens ?? agent?.MaxTokens ?? cfg.MaxTokens, Model);
        _dryRun = dryRun;
        if (!_dryRun)
        {
            if (string.IsNullOrEmpty(ApiKey))
                throw new InvalidOperationException("未配置 api_key（请在设置中填写，或设置 OPENAI_API_KEY；可加 --dry-run 模拟运行）");
            if (string.IsNullOrEmpty(Model))
                throw new InvalidOperationException("未配置 model（请在设置中填写，或设置 OPENAI_MODEL；可加 --dry-run 模拟运行）");
        }
    }

    /// <summary>解析 max_tokens：显式配置 &gt; 0 用配置值，否则按模型映射表，未知名用保守默认。</summary>
    private static int ResolveMaxTokens(int configured, string model)
    {
        if (configured > 0)
            return configured;
        var m = (model ?? "").ToLowerInvariant();
        foreach (var (prefix, limit) in ModelOutputLimits)
        {
            if (m.Contains(prefix, StringComparison.Ordinal))
                return limit;
        }
        return DefaultOutputLimit;
    }

    private static string FirstNonEmpty(params string?[] values)
        => values.FirstOrDefault(v => !string.IsNullOrWhiteSpace(v)) ?? "";

    /// <summary>从 config.yml 读取全局 AI 配置（openai 段）。</summary>
    public static (string BaseUrl, string ApiKey, string Model, int MaxTokens) LoadConfig(string? configPath = null)
    {
        var root = ReadConfigRoot(configPath);
        if (root != null && root.TryGetValue("openai", out var o) && o is IDictionary<object, object> od)
        {
            return (
                Get(od, "base_url"),
                Get(od, "api_key"),
                Get(od, "model"),
                int.TryParse(Get(od, "max_tokens"), out var mt) ? mt : 0
            );
        }
        return ("", "", "", 0);
    }

    /// <summary>读取某 AI（agent）的独立配置（config.yml 的 agents 段）；未配置返回 null。</summary>
    public static (string BaseUrl, string ApiKey, string Model, int MaxTokens)? LoadAgentConfig(
        string agentName, string? configPath = null)
    {
        var root = ReadConfigRoot(configPath);
        if (root != null && root.TryGetValue("agents", out var a) && a is IDictionary<object, object> agents
            && agents.TryGetValue(agentName, out var av) && av is IDictionary<object, object> ad)
        {
            var hasAny = Get(ad, "base_url").Length > 0 || Get(ad, "api_key").Length > 0
                         || Get(ad, "model").Length > 0 || Get(ad, "max_tokens").Length > 0;
            if (!hasAny)
                return null;
            return (
                Get(ad, "base_url"),
                Get(ad, "api_key"),
                Get(ad, "model"),
                int.TryParse(Get(ad, "max_tokens"), out var mt) ? mt : 0
            );
        }
        return null;
    }

    private static Dictionary<string, object>? ReadConfigRoot(string? configPath)
    {
        var path = configPath ?? Paths.ConfigYmlPath;
        if (!File.Exists(path))
            return null;
        try
        {
            return new DeserializerBuilder().Build()
                .Deserialize<Dictionary<string, object>>(File.ReadAllText(path));
        }
        catch (Exception exc)
        {
            Console.WriteLine($"警告：config.yml 读取失败（{exc.Message}），回退到环境变量。");
            return null;
        }
    }

    private static string Get(IDictionary<object, object> d, string key)
        => d.TryGetValue(key, out var v) ? v?.ToString()?.Trim() ?? "" : "";

    /// <summary>调用对话接口，返回文本。dry_run 时返回模拟响应。maxTokens&lt;=0 时自动使用模型上限（MaxOutputTokens）。</summary>
    public async Task<string> ChatAsync(List<ChatMessage> messages,
        double temperature = 0.3, int maxTokens = 0, bool jsonMode = false, double topP = 0.9)
    {
        if (_dryRun)
            return MockChat(messages);

        var mt = maxTokens > 0 ? maxTokens : MaxOutputTokens;
        var payload = new Dictionary<string, object>
        {
            ["model"] = Model,
            ["messages"] = messages.Select(m => new { role = m.Role, content = m.Content }),
            ["temperature"] = temperature,
            ["max_tokens"] = mt,
            ["top_p"] = topP,
        };
        if (jsonMode)
            payload["response_format"] = new { type = "json_object" };

        var request = new HttpRequestMessage(HttpMethod.Post, BaseUrl.TrimEnd('/') + "/chat/completions");
        request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {ApiKey}");
        request.Content = new StringContent(JsonSerializer.Serialize(payload), System.Text.Encoding.UTF8, "application/json");

        using var resp = await _http.SendAsync(request);
        var body = await resp.Content.ReadAsStringAsync();
        if (!resp.IsSuccessStatusCode)
            throw new InvalidOperationException($"LLM 接口调用失败（{(int)resp.StatusCode}）：{body}");

        using var doc = JsonDocument.Parse(body);
        return doc.RootElement.GetProperty("choices")[0].GetProperty("message").GetProperty("content").GetString()?.Trim() ?? "";
    }

    /// <summary>调用接口并要求返回 JSON，返回解析后的对象（解析失败时自动重试一次）。</summary>
    public async Task<JsonElement> ChatJsonAsync(List<ChatMessage> messages,
        double temperature = 0.2, int maxTokens = 0, double topP = 0.9)
    {
        var text = await ChatAsync(messages, temperature, maxTokens, jsonMode: true, topP);
        try
        {
            return ParseJson(text);
        }
        catch (JsonException)
        {
            // 偶发空返回/非 JSON：重试一次
            var text2 = await ChatAsync(messages, temperature, maxTokens, jsonMode: true, topP);
            try
            {
                return ParseJson(text2);
            }
            catch (JsonException exc)
            {
                throw new JsonException(
                    $"模型两次均未返回有效 JSON：首次原文 {Truncate(text)}，重试原文 {Truncate(text2)}", exc);
            }
        }
    }

    private static string Truncate(string s) => s.Length > 200 ? s[..200] : s;

    /// <summary>从模型输出中解析 JSON：去 Markdown 代码块、前后解释文字，提取首个 { 或 [。</summary>
    public static JsonElement ParseJson(string text)
    {
        var t = text.Trim();
        t = Regex.Replace(t, @"^```[a-zA-Z]*\s*", "");
        t = Regex.Replace(t, @"\s*```$", "").Trim();
        try
        {
            using var doc = JsonDocument.Parse(t);
            return doc.RootElement.Clone();
        }
        catch (JsonException)
        {
            var opens = new List<int>();
            var iBrace = t.IndexOf('{');
            var iBracket = t.IndexOf('[');
            if (iBrace != -1) opens.Add(iBrace);
            if (iBracket != -1) opens.Add(iBracket);
            if (opens.Count > 0)
            {
                var start = opens.Min();
                var end = Math.Max(t.LastIndexOf('}'), t.LastIndexOf(']'));
                if (end > start)
                {
                    try
                    {
                        using var doc = JsonDocument.Parse(t[start..(end + 1)]);
                        return doc.RootElement.Clone();
                    }
                    catch (JsonException)
                    {
                        // 继续抛出原始错误
                    }
                }
            }
            throw new JsonException($"无法解析 JSON（原文：{Truncate(text)}）");
        }
    }

    /// <summary>dry-run：根据任务标记返回模拟响应（system 与 user 均参与判断，对应 Python 版）。</summary>
    public static string MockChat(List<ChatMessage> messages)
    {
        var combined = string.Join("\n", messages.Select(m => m.Content));
        if (combined.Contains("检索关键词提取助手"))
            return "{\"keywords\": [\"直播\", \"2023\"]}";
        if (combined.Contains("资料锚定助手"))
        {
            // 提取候选列表中的第一个真实文件名，使锚定环节贴近真实流程
            var m = Regex.Match(combined, "文件名：([^\n]+)");
            var name = m.Success ? m.Groups[1].Value.Trim() : "unknown.docx";
            return $"[{JsonSerializer.Serialize(name)}]";
        }
        return "（dry-run 模拟回答：已基于锚定资料完成作答。）";
    }
}
