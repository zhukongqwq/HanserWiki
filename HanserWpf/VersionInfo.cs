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
        var path = Path.Combine(Paths.WpfRoot, "version.json");
        if (!File.Exists(path))
            return new VersionInfo();
        try
        {
            return JsonSerializer.Deserialize<VersionInfo>(File.ReadAllText(path), JsonOptions) ?? new VersionInfo();
        }
        catch
        {
            return new VersionInfo();
        }
    }
}
