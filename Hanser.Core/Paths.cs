using System;
using System.IO;

namespace Hanser.Core;

/// <summary>项目路径解析：C# 版复用 Python 目录下的数据（data / documents.db / userdict.txt）。</summary>
public static class Paths
{
    /// <summary>向上搜索含 Python 目录的项目根（AI Hanser 根目录）。</summary>
    public static string Root
    {
        get
        {
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir != null)
            {
                if (Directory.Exists(Path.Combine(dir.FullName, "Python")))
                    return dir.FullName;
                dir = dir.Parent;
            }
            throw new DirectoryNotFoundException("未找到项目根目录（应包含 Python/ 目录）");
        }
    }

    /// <summary>C# 版根目录（自包含：数据/词库/配置均在自身目录下，便于分发）。</summary>
    public static string WpfRoot => Path.Combine(Root, "HanserWpf");

    /// <summary>docx 文档目录（C# 版本地数据，与 Python 版分离）。</summary>
    public static string DataDir => Path.Combine(WpfRoot, "data");

    /// <summary>SQLite 数据库文件（C# 版本地索引，表结构与 Python 版兼容）。</summary>
    public static string DbPath => Path.Combine(WpfRoot, "documents.db");

    /// <summary>jieba 自定义词典（C# 版本地副本，与 Python 版各自维护）。</summary>
    public static string UserDictPath => Path.Combine(Root, "HanserWpf", "userdict.txt");

    /// <summary>C# 版 AI 配置（本目录 config.yml，不存在则回退环境变量）。</summary>
    public static string ConfigYmlPath => Path.Combine(Root, "HanserWpf", "config.yml");
}
