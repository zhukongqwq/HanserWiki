using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using Hanser.Core;

namespace HanserWpf;

/// <summary>存档中的一条消息（docs 为回答附带的相关文档文件名清单）。</summary>
public class ChatMessageRecord
{
    public string Role { get; set; } = "user"; // user / assistant
    public string Content { get; set; } = "";
    public List<string> Docs { get; set; } = new(); // 仅回答消息可能有
}

/// <summary>一个对话的存档记录。</summary>
public class ChatSessionRecord
{
    public string Title { get; set; } = "";
    public string Created { get; set; } = "";
    public string Updated { get; set; } = "";
    public List<ChatMessageRecord> Messages { get; set; } = new();
}

/// <summary>对话存档：chat-history/ 目录下一个对话一个 json 文件（标题默认取第一个问题）。</summary>
public static class ChatArchive
{
    private const int MaxTitleLength = 30;

    public static string ChatDir => Path.Combine(Paths.WpfRoot, "chat-history");

    /// <summary>对话摘要（用于历史栏列表）。</summary>
    public class ChatSummary
    {
        public string Path { get; set; } = "";
        public string Title { get; set; } = "";
        public string Updated { get; set; } = "";
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true, // 兼容历史 PascalCase 文件
    };

    /// <summary>列出全部对话（按更新时间降序）。</summary>
    public static List<ChatSummary> ListChats()
    {
        if (!Directory.Exists(ChatDir))
            return new List<ChatSummary>();
        return Directory.GetFiles(ChatDir, "*.json")
            .Select(p =>
            {
                try
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(p));
                    var root = doc.RootElement;
                    return new ChatSummary
                    {
                        Path = p,
                        Title = NormalizeTitle(GetString(root, "title")),
                        Updated = GetString(root, "updated"),
                    };
                }
                catch
                {
                    return null;
                }
            })
            .Where(s => s != null)
            .OrderByDescending(s => s!.Updated)
            .Select(s => s!)
            .ToList();
    }

    /// <summary>大小写不敏感地读取对象属性字符串。</summary>
    private static string GetString(JsonElement obj, string name)
    {
        foreach (var p in obj.EnumerateObject())
        {
            if (p.Name.Equals(name, StringComparison.OrdinalIgnoreCase) && p.Value.ValueKind == JsonValueKind.String)
                return p.Value.GetString() ?? "";
        }
        return "";
    }

    /// <summary>读取一个对话。</summary>
    public static ChatSessionRecord Load(string path)
    {
        try
        {
            return JsonSerializer.Deserialize<ChatSessionRecord>(File.ReadAllText(path), JsonOptions) ?? new ChatSessionRecord();
        }
        catch
        {
            return new ChatSessionRecord();
        }
    }

    /// <summary>保存一个对话。</summary>
    public static void Save(string path, ChatSessionRecord session)
    {
        Directory.CreateDirectory(ChatDir);
        session.Updated = DateTime.Now.ToString("yyyy-MM-ddTHH:mm:ss");
        File.WriteAllText(path, JsonSerializer.Serialize(session, JsonOptions));
    }

    /// <summary>新建对话文件（标题默认第一个问题，截断）。返回文件路径。</summary>
    public static string CreateNew(string title)
    {
        Directory.CreateDirectory(ChatDir);
        var now = DateTime.Now;
        var path = Path.Combine(ChatDir, $"chat-{now:yyyyMMdd-HHmmss}.json");
        var session = new ChatSessionRecord
        {
            Title = NormalizeTitle(title),
            Created = now.ToString("yyyy-MM-ddTHH:mm:ss"),
            Updated = now.ToString("yyyy-MM-ddTHH:mm:ss"),
        };
        Save(path, session);
        return path;
    }

    /// <summary>重命名对话（标题截断）。</summary>
    public static void Rename(string path, string title)
    {
        var session = Load(path);
        session.Title = NormalizeTitle(title);
        Save(path, session);
    }

    /// <summary>标题规范化（截断 30 字、去换行；空标题回退默认名）。</summary>
    public static string NormalizeTitle(string title)
    {
        var t = (title ?? "").Trim().Replace('\n', ' ');
        if (t.Length == 0)
            t = "（未命名对话）";
        return t.Length > MaxTitleLength ? t[..MaxTitleLength] + "…" : t;
    }

    /// <summary>删除一个对话文件。</summary>
    public static void Delete(string path)
    {
        if (File.Exists(path))
            File.Delete(path);
    }

    /// <summary>清空全部对话。</summary>
    public static void ClearAll()
    {
        if (Directory.Exists(ChatDir))
        {
            foreach (var f in Directory.GetFiles(ChatDir, "*.json"))
                File.Delete(f);
        }
    }
}
