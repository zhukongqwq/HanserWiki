using System;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;

namespace HanserWpf;

/// <summary>应用版本检查：从 HanserWiki 仓库拉取 version.json 与本地版本号比对。</summary>
public static class AppUpdate
{
    private const string VersionUrl = "https://raw.githubusercontent.com/zhukongqwq/HanserWiki/main/version.json";
    public const string ReleaseUrl = "https://github.com/zhukongqwq/HanserWiki/releases";

    /// <summary>检查更新：返回 (是否有新版, 本地版本, 远程版本)。远程拉取失败时 Remote 为空串。</summary>
    public static async Task<(bool HasUpdate, string Local, string Remote)> CheckAsync(string? proxyPrefix)
    {
        var local = VersionInfo.Load().Version;
        var url = string.IsNullOrWhiteSpace(proxyPrefix)
            ? VersionUrl
            : proxyPrefix.TrimEnd('/') + "/" + VersionUrl;
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
            var json = await client.GetStringAsync(url);
            var remote = JsonSerializer.Deserialize<VersionInfo>(json,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true })?.Version ?? "";
            return (CompareVersions(remote, local) > 0, local, remote);
        }
        catch
        {
            return (false, local, "");
        }
    }

    /// <summary>语义化版本比较：a &gt; b 返回正数（按 . 分段数字比较）。</summary>
    private static int CompareVersions(string a, string b)
    {
        var pa = (a ?? "").Split('.', '-');
        var pb = (b ?? "").Split('.', '-');
        var n = Math.Max(pa.Length, pb.Length);
        for (var i = 0; i < n; i++)
        {
            var x = i < pa.Length && int.TryParse(pa[i], out var vx) ? vx : 0;
            var y = i < pb.Length && int.TryParse(pb[i], out var vy) ? vy : 0;
            if (x != y)
                return x.CompareTo(y);
        }
        return 0;
    }
}
