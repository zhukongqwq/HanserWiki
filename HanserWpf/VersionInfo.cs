using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using Hanser.Core;

namespace HanserWpf;

/// <summary>应用版本信息与更新日志（读取本地 version.json；文件缺失时回退默认值）。</summary>
public class VersionInfo
{
    public string Version { get; set; } = "1.0.0";
    public string Date { get; set; } = "";
    public List<string> Changelog { get; set; } = new();

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
