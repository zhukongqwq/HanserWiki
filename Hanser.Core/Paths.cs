using System;
using System.IO;

namespace Hanser.Core;

/// <summary>项目路径解析：C# 版复用 Python 目录下的数据（data / documents.db / userdict.txt）。</summary>
public static class Paths
{
    /// <summary>项目根：向上搜索同时含 Python 与 HanserWpf 的目录（开发环境）；发布版无该组合时回退到程序目录。</summary>
    public static string Root
    {
        get
        {
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir != null)
            {
                if (Directory.Exists(Path.Combine(dir.FullName, "Python"))
                    && Directory.Exists(Path.Combine(dir.FullName, "HanserWpf")))
                    return dir.FullName;
                dir = dir.Parent;
            }
            // 分发版：数据/词库/配置均在 exe 所在目录
            return AppContext.BaseDirectory.TrimEnd('\\', '/');
        }
    }

    /// <summary>C# 版根目录：开发环境为 Root/HanserWpf；发布版（无 HanserWpf 子目录）为程序目录本身（自包含）。</summary>
    public static string WpfRoot =>
        Directory.Exists(Path.Combine(Root, "HanserWpf"))
            ? Path.Combine(Root, "HanserWpf")
            : Root;

    /// <summary>docx 文档目录（C# 版本地数据，与 Python 版分离）。</summary>
    public static string DataDir => Path.Combine(WpfRoot, "data");

    /// <summary>SQLite 数据库文件（C# 版本地索引，表结构与 Python 版兼容）。</summary>
    public static string DbPath => Path.Combine(WpfRoot, "documents.db");

    /// <summary>jieba 自定义词典（C# 版本地副本，与 Python 版各自维护）。</summary>
    public static string UserDictPath => Path.Combine(Root, "HanserWpf", "userdict.txt");

    /// <summary>C# 版 AI 配置（本目录 config.yml，不存在则回退环境变量）。</summary>
    public static string ConfigYmlPath => Path.Combine(Root, "HanserWpf", "config.yml");
}
