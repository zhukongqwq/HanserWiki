using System;
using System.IO;

namespace Hanser.Core;

/// <summary>项目路径解析：C# 版复用 Python 目录下的数据（data / documents.db / userdict.txt）。</summary>
public static class Paths
{
    /// <summary>
    /// 项目根：发布版（DISTRIBUTION）恒为程序目录（数据自包含，不读开发环境数据）；
    /// 开发版向上搜索含 HanserWpf 子目录的仓库根（HanserWiki 仓库重组后布局），找不到回退程序目录。
    /// </summary>
    public static string Root
    {
        get
        {
#if DISTRIBUTION
            // 发布版：数据/词库/配置均在 exe 所在目录
            return AppContext.BaseDirectory.TrimEnd('\\', '/');
#else
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir != null)
            {
                if (Directory.Exists(Path.Combine(dir.FullName, "HanserWpf")))
                    return dir.FullName;
                dir = dir.Parent;
            }
            // 分发版：数据/词库/配置均在 exe 所在目录
            return AppContext.BaseDirectory.TrimEnd('\\', '/');
#endif
        }
    }

    /// <summary>
    /// 应用根：开发环境为仓库收纳根（HanserWiki/HanserWpf，含 data/userdict/config）；
    /// 发布版（DISTRIBUTION）恒为程序目录本身（自包含）。
    /// </summary>
    public static string WpfRoot
    {
        get
        {
#if DISTRIBUTION
            return Root; // 发布版：exe 目录即应用根（不判断 HanserWpf 子目录，避免误判）
#else
            return Root; // 开发版：收纳根即 C# 应用根（数据已并入）
#endif
        }
    }

    /// <summary>docx 文档目录（C# 版本地数据）。</summary>
    public static string DataDir => Path.Combine(WpfRoot, "data");

    /// <summary>SQLite 数据库文件（C# 版本地索引，表结构与 Python 版兼容）。</summary>
    public static string DbPath => Path.Combine(WpfRoot, "documents.db");

    /// <summary>jieba 自定义词典（C# 版本地副本）。</summary>
    public static string UserDictPath => Path.Combine(WpfRoot, "userdict.txt");

    /// <summary>C# 版 AI 配置（本目录 config.yml，不存在则回退环境变量）。</summary>
    public static string ConfigYmlPath => Path.Combine(WpfRoot, "config.yml");
}
