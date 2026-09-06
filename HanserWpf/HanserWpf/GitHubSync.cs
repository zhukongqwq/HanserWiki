using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading.Tasks;
using Hanser.Core;
using YamlDotNet.Serialization;

namespace HanserWpf;

/// <summary>
/// GitHub 清单增量更新源：仓库 Release 维护 list.json（{路径: sha256}），
/// 检查更新时下载 list.json 与本地缓存比对（sha256）→ 本地文件逐个比对 → 只下载缺失/不一致的文件。
/// 仓库地址支持镜像前缀（如 https://gh-proxy.com/https://github.com/owner/repo.git）。
/// </summary>
public static class GitHubSync
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(5) };

    private const string ManifestFile = ".sync-list.json"; // 本地清单缓存
    private const string DefaultBranch = "main";

    /// <summary>更新源配置（config.yml 的 update_source 段，单一 url 字段）。</summary>
    public class Config
    {
        public string Url { get; set; } = "https://github.com/zhukongqwq/hanser-live-text.git"; // 仓库地址（可带镜像前缀 / .git 后缀）
    }

    public class SyncResult
    {
        public int Added { get; set; }
        public int Updated { get; set; }
        public int Failed { get; set; }
        public List<string> Errors { get; } = new();
    }

    /// <summary>从 config.yml 读取更新源配置。</summary>
    public static Config LoadConfig()
    {
        var path = Paths.ConfigYmlPath;
        if (!File.Exists(path))
            return new Config();
        try
        {
            var deserializer = new DeserializerBuilder().Build();
            var root = deserializer.Deserialize<Dictionary<string, object>>(File.ReadAllText(path));
            if (root != null && root.TryGetValue("update_source", out var o) && o is IDictionary<object, object> us)
            {
                return new Config
                {
                    Url = us.TryGetValue("url", out var v) ? v?.ToString()?.Trim() ?? "" : "",
                };
            }
            return new Config();
        }
        catch
        {
            return new Config();
        }
    }

    /// <summary>把更新源配置写入 config.yml 的 update_source 段（文本追加，保留 openai 段）。</summary>
    public static void SaveConfig(Config cfg)
    {
        var path = Paths.ConfigYmlPath;
        var lines = File.Exists(path) ? File.ReadAllLines(path).ToList() : new List<string>();
        for (var i = 0; i < lines.Count; i++)
        {
            if (lines[i].TrimStart().StartsWith("update_source:"))
            {
                lines.RemoveRange(i, lines.Count - i);
                break;
            }
        }
        lines.Add("update_source:");
        lines.Add($"  url: \"{Escape(cfg.Url)}\"");
        File.WriteAllText(path, string.Join("\n", lines) + "\n");
    }

    private static string Escape(string s)
        => s.Replace("\\", "\\\\").Replace("\"", "\\\"");

    /// <summary>解析仓库地址 → (镜像前缀, owner, repo, 去 .git 的仓库基址)。</summary>
    public static (string Proxy, string Owner, string Repo, string RepoBase) ParseUrl(string url)
    {
        const string marker = "https://github.com/";
        var trimmed = (url ?? "").Trim().TrimEnd('/');
        var idx = trimmed.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
        if (idx < 0)
            throw new InvalidOperationException("仓库地址无效：需包含 https://github.com/");
        var proxy = trimmed[..idx];
        var rest = trimmed[(idx + marker.Length)..];
        var parts = rest.Split('/');
        if (parts.Length < 2 || parts[0].Length == 0 || parts[1].Length == 0)
            throw new InvalidOperationException("仓库地址无效：应为 https://github.com/{owner}/{repo}[.git]");
        var owner = parts[0];
        var repo = parts[1];
        if (repo.EndsWith(".git", StringComparison.OrdinalIgnoreCase))
            repo = repo[..^4];
        var repoBase = proxy + marker + owner + "/" + repo;
        return (proxy, owner, repo, repoBase);
    }

    /// <summary>拼接仓库的 list.json 下载链接。</summary>
    public static string ListUrl(string repoUrl)
    {
        var (_, _, _, repoBase) = ParseUrl(repoUrl);
        return repoBase + "/releases/download/latest/list.json";
    }

    /// <summary>校验仓库 list.json 链接可达（保存设置时用）。</summary>
    public static async Task<(bool Ok, string Message)> ValidateListUrlAsync(string repoUrl)
    {
        try
        {
            var listUrl = ListUrl(repoUrl);
            using var req = new HttpRequestMessage(HttpMethod.Get, listUrl);
            req.Headers.TryAddWithoutValidation("User-Agent", "AI-Hanser");
            using var resp = await Http.SendAsync(req);
            if (resp.IsSuccessStatusCode)
                return (true, "");
            return (false,
                $"未找到 list.json（HTTP {(int)resp.StatusCode}）：\n{listUrl}\n\n请先在仓库 Release 中上传 list.json 资产，或检查仓库地址 / 网络 / 镜像前缀。");
        }
        catch (Exception exc)
        {
            return (false, $"校验失败：{exc.Message}");
        }
    }

    /// <summary>
    /// 执行检查更新：下载 list.json → 与本地缓存 sha256 比对（一致则无更新）→
    /// 本地文件逐个 sha256 对比 → 只下载缺失/不一致文件（raw + 镜像，下载后校验）→ 保存缓存 → 增量索引。
    /// </summary>
    public static async Task<SyncResult> RunAsync(Config cfg, Action<string>? log = null)
    {
        var result = new SyncResult();
        var (proxy, owner, repo, _) = ParseUrl(cfg.Url);
        var listUrl = ListUrl(cfg.Url);
        log?.Invoke($"  仓库：{owner}/{repo}，镜像：{(proxy.Length > 0 ? proxy.TrimEnd('/') : "（直连）")}");
        log?.Invoke($"  下载清单：{listUrl}…");

        // 1. 下载 list.json
        byte[] listBytes;
        using (var req = new HttpRequestMessage(HttpMethod.Get, listUrl))
        {
            req.Headers.TryAddWithoutValidation("User-Agent", "AI-Hanser");
            using var resp = await Http.SendAsync(req);
            if (!resp.IsSuccessStatusCode)
                throw new InvalidOperationException(
                    $"下载清单失败（HTTP {(int)resp.StatusCode}）：{listUrl}\n请先在仓库 Release 上传 list.json 资产，或检查仓库地址 / 网络 / 镜像前缀。");
            listBytes = await resp.Content.ReadAsByteArrayAsync();
        }

        // 2. 与本地缓存比对（sha256）
        var localListPath = Path.Combine(Paths.WpfRoot, ManifestFile);
        var listSha = Sha256Hex(listBytes);
        if (File.Exists(localListPath) && Sha256Hex(File.ReadAllBytes(localListPath)) == listSha)
        {
            log?.Invoke($"  清单无变化（sha256 {listSha[..12]}…），无需更新。");
            return result;
        }
        log?.Invoke($"  清单已更新（sha256 {listSha[..12]}…），开始比对本地文件…");

        // 3. 解析清单 {路径: sha256}
        var manifest = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            using var doc = JsonDocument.Parse(listBytes);
            foreach (var prop in doc.RootElement.EnumerateObject())
                manifest[prop.Name] = prop.Value.GetString() ?? "";
        }
        catch (Exception exc)
        {
            throw new InvalidOperationException($"list.json 解析失败：{exc.Message}");
        }
        log?.Invoke($"  清单条目：{manifest.Count} 个");

        // 4. 本地文件逐个 sha256 对比 → 收集缺失/不一致
        var toDownload = new List<(string Path, string Sha)>();
        var skipped = 0;
        foreach (var (path, sha) in manifest)
        {
            var rel = NormalizeRel(path);
            if (rel == null)
            {
                log?.Invoke($"  [跳过] {path}（路径不在 data/ 目录内）");
                continue;
            }
            var dest = Path.Combine(Paths.DataDir, rel);
            if (File.Exists(dest) && Sha256Hex(File.ReadAllBytes(dest)).Equals(sha, StringComparison.OrdinalIgnoreCase))
            {
                skipped++;
                continue;
            }
            toDownload.Add((path, sha));
        }
        log?.Invoke($"  本地一致跳过：{skipped} 个；需下载：{toDownload.Count} 个");

        // 5. 逐个下载（raw + 镜像），校验 sha256
        Directory.CreateDirectory(Paths.DataDir);
        var index = 0;
        foreach (var (path, sha) in toDownload)
        {
            index++;
            var rel = NormalizeRel(path)!;
            var dest = Path.Combine(Paths.DataDir, rel);
            try
            {
                // 仓库路径统一为 data/{rel}（兼容清单键 data/ 与 ../data/ 两种前缀）
                var encoded = string.Join("/", ("data/" + rel).Split('/').Select(Uri.EscapeDataString));
                var rawUrl = proxy + $"https://raw.githubusercontent.com/{owner}/{repo}/{DefaultBranch}/{encoded}";
                log?.Invoke($"  [下载] {rel}（{index}/{toDownload.Count}）…");
                var bytes = await Http.GetByteArrayAsync(rawUrl);
                var actualSha = Sha256Hex(bytes);
                if (!actualSha.Equals(sha, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException($"sha256 校验失败（期望 {sha[..Math.Min(12, sha.Length)]}…，实际 {actualSha[..12]}…）");
                var isUpdate = File.Exists(dest);
                Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                File.WriteAllBytes(dest, bytes);
                if (isUpdate)
                    result.Updated++;
                else
                    result.Added++;
                log?.Invoke($"  [完成] {rel}（{(isUpdate ? "更新" : "新增")}，{bytes.Length / 1024} KB，sha256 校验通过）");
            }
            catch (Exception exc)
            {
                result.Failed++;
                result.Errors.Add($"{rel}：{exc.Message}");
                log?.Invoke($"  [失败] {rel}：{exc.Message}");
            }
        }

        // 6. 保存清单缓存 + 增量索引
        File.WriteAllBytes(localListPath, listBytes);
        log?.Invoke($"  已保存更新清单（sha256 {listSha[..12]}…）");
        Indexer.IndexDocuments(log: msg => log?.Invoke(msg));
        return result;
    }

    /// <summary>把清单键规范化为本地 data 目录相对路径（去 data/ 或 ../data/ 前缀、防路径穿越）；不合法返回 null。</summary>
    private static string? NormalizeRel(string path)
    {
        var p = (path ?? "").Replace('\\', '/');
        // 支持 data/ 与 ../data/ 两种前缀（用户仓库清单使用 ../data/ 形式）
        if (p.StartsWith("../data/", StringComparison.OrdinalIgnoreCase))
            p = p[8..];
        else if (p.StartsWith("data/", StringComparison.OrdinalIgnoreCase))
            p = p[5..];
        if (p.Length == 0 || p.StartsWith("/") || p.Contains(".."))
            return null;
        return p;
    }

    private static string Sha256Hex(byte[] data)
        => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
}
