using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using Hanser.Core;

namespace HanserWpf;

/// <summary>某平台的应用版本信息（apps.windows / apps.android）。</summary>
public class AppVersionInfo
{
    public string Version { get; set; } = "";
    public string File { get; set; } = "";
}

/// <summary>
/// 应用版本信息与更新日志（读取本地 version.json；文件缺失时回退默认值）。
/// version.json 为双平台共享：顶层 version 兼容字段 + apps.{windows,android} 各平台段。
/// </summary>
public class VersionInfo
{
    public string Version { get; set; } = "1.0.0";
    public string Date { get; set; } = "";
    public List<string> Changelog { get; set; } = new();
    public Dictionary<string, AppVersionInfo> Apps { get; set; } = new();

    /// <summary>Windows 平台版本（apps.windows.version，缺省回退顶层 version）。</summary>
    public string WindowsVersion =>
        Apps.TryGetValue("windows", out var w) && !string.IsNullOrEmpty(w.Version)
            ? w.Version
            : Version;

    /// <summary>Android 平台版本（apps.android.version，缺省回退顶层 version）。</summary>
    public string AndroidVersion =>
        Apps.TryGetValue("android", out var a) && !string.IsNullOrEmpty(a.Version)
            ? a.Version
            : Version;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    public static VersionInfo Load()
    {
        // 版本文件在 exe 旁（发布 zip 内已随包），开发环境在仓库收纳根的上级（HanserWiki 仓库根）
        foreach (var candidate in new[]
                 {
                     Path.Combine(Paths.WpfRoot, "version.json"),
                     Path.Combine(Paths.Root, "version.json"),
                     Path.Combine(Directory.GetParent(Paths.Root)?.FullName ?? "", "version.json"),
                 })
        {
            if (!File.Exists(candidate))
                continue;
            try
            {
                var info = JsonSerializer.Deserialize<VersionInfo>(File.ReadAllText(candidate), JsonOptions);
                if (info != null)
                    return info;
            }
            catch
            {
                // 该候选损坏则继续尝试下一个
            }
        }
        return new VersionInfo();
    }
}
