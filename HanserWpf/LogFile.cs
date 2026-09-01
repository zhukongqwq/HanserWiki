using System;
using System.IO;
using System.Linq;
using System.Text;
using Hanser.Core;

namespace HanserWpf;

/// <summary>
/// 本地日志文件：每次运行会话一个 logs/hanser-时间戳.log（UTF-8），实时追加；
/// 目录内最多保留 5 个，超出自动删除最旧文件。
/// </summary>
public static class LogFile
{
    private const int MaxFiles = 5;

    private static readonly string LogDir = Path.Combine(Paths.Root, "HanserWpf", "logs");

    /// <summary>会话日志文件路径（首次写入时创建）。</summary>
    private static readonly Lazy<string> SessionFile = new(() =>
    {
        Directory.CreateDirectory(LogDir);
        return Path.Combine(LogDir, $"hanser-{DateTime.Now:yyyyMMdd-HHmmss}.log");
    });

    /// <summary>追加一行到本地日志文件，并做数量滚动（写入失败不影响主程序）。</summary>
    public static void Append(string line)
    {
        try
        {
            File.AppendAllText(SessionFile.Value, line + Environment.NewLine, new UTF8Encoding(false));
            Rotate();
        }
        catch
        {
            // 日志写入失败静默忽略，不中断程序
        }
    }

    /// <summary>最多保留 MaxFiles 个日志文件，超出删除最旧的（文件名按时间戳字典序）。</summary>
    private static void Rotate()
    {
        try
        {
            var files = Directory.GetFiles(LogDir, "hanser-*.log").OrderBy(f => f, StringComparer.Ordinal).ToList();
            while (files.Count > MaxFiles)
            {
                File.Delete(files[0]);
                files.RemoveAt(0);
            }
        }
        catch
        {
            // 清理失败静默忽略
        }
    }
}
