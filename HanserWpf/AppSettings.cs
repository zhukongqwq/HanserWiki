using System;
using System.IO;
using System.Text.Json;
using Hanser.Core;

namespace HanserWpf;

/// <summary>应用本地设置（appsettings.json，不入库）。</summary>
public class AppSettings
{
    public bool AutoCheckUpdate { get; set; } = true; // 启动时自动检查更新（版本）

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    private static string FilePath => System.IO.Path.Combine(Paths.WpfRoot, "appsettings.json");

    public static AppSettings Load()
    {
        if (!File.Exists(FilePath))
            return new AppSettings();
        try
        {
            return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), JsonOptions) ?? new AppSettings();
        }
        catch
        {
            return new AppSettings();
        }
    }

    public static void Save(AppSettings settings)
    {
        try
        {
            File.WriteAllText(FilePath, JsonSerializer.Serialize(settings, JsonOptions));
        }
        catch
        {
            // 写入失败静默忽略
        }
    }
}
