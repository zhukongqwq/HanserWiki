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

/// <summary>LLM 客户端：OpenAI 兼容接口封装，支持 dry-run 模拟模式（对应 Python 版 utils/llm.py）。</summary>
public class LLMClient
{
    private const string DefaultBaseUrl = "https://api.openai.com/v1";

    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(10) };
    private readonly bool _dryRun;

    public string BaseUrl { get; }
    public string ApiKey { get; }
    public string Model { get; }

    public LLMClient(string? baseUrl = null, string? apiKey = null, string? model = null,
        bool dryRun = false, string? configPath = null)
    {
        var cfg = LoadConfig(configPath);
        BaseUrl = FirstNonEmpty(baseUrl, cfg.BaseUrl, Environment.GetEnvironmentVariable("OPENAI_BASE_URL"), DefaultBaseUrl);
        ApiKey = FirstNonEmpty(apiKey, cfg.ApiKey, Environment.GetEnvironmentVariable("OPENAI_API_KEY"), "");
        Model = FirstNonEmpty(model, cfg.Model, Environment.GetEnvironmentVariable("OPENAI_MODEL"), "");
        _dryRun = dryRun;
        if (!_dryRun)
        {
            if (string.IsNullOrEmpty(ApiKey))
                throw new InvalidOperationException("未配置 api_key（请在 config.yml 填写，或设置 OPENAI_API_KEY；可加 --dry-run 模拟运行）");
            if (string.IsNullOrEmpty(Model))
                throw new InvalidOperationException("未配置 model（请在 config.yml 填写，或设置 OPENAI_MODEL；可加 --dry-run 模拟运行）");
        }
    }

    private static string FirstNonEmpty(params string?[] values)
        => values.FirstOrDefault(v => !string.IsNullOrWhiteSpace(v)) ?? "";

    /// <summary>从 config.yml 读取 AI 配置（openai.base_url / openai.api_key / openai.model），失败时回退环境变量。</summary>
    public static (string BaseUrl, string ApiKey, string Model) LoadConfig(string? configPath = null)
    {
        var path = configPath ?? Paths.ConfigYmlPath;
        if (!File.Exists(path))
            return ("", "", "");
        try
        {
            var deserializer = new DeserializerBuilder().Build();
            var root = deserializer.Deserialize<Dictionary<string, object>>(File.ReadAllText(path));
            if (root != null && root.TryGetValue("openai", out var o) && o is IDictionary<object, object> od)
            {
                return (
                    (od.TryGetValue("base_url", out var b) ? b?.ToString()?.Trim() : "") ?? "",
                    (od.TryGetValue("api_key", out var k) ? k?.ToString()?.Trim() : "") ?? "",
                    (od.TryGetValue("model", out var m) ? m?.ToString()?.Trim() : "") ?? ""
                );
            }
            return ("", "", "");
        }
        catch (Exception exc)
        {
            Console.WriteLine($"警告：config.yml 读取失败（{exc.Message}），回退到环境变量。");
            return ("", "", "");
        }
    }

    /// <summary>调用对话接口，返回文本。dry_run 时返回模拟响应。</summary>
    public async Task<string> ChatAsync(List<ChatMessage> messages,
        double temperature = 0.3, int maxTokens = 255000, bool jsonMode = false, double topP = 0.9)
    {
        if (_dryRun)
            return MockChat(messages);

        var payload = new Dictionary<string, object>
        {
            ["model"] = Model,
            ["messages"] = messages.Select(m => new { role = m.Role, content = m.Content }),
            ["temperature"] = temperature,
            ["max_tokens"] = maxTokens,
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
        double temperature = 0.2, int maxTokens = 255000, double topP = 0.9)
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
