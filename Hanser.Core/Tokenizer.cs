using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using JiebaNet.Segmenter;

namespace Hanser.Core;

/// <summary>
/// 分词模块：统一加载 jieba 自定义词典并提供分词入口（对应 Python 版 utils/tokenizer.py）。
/// 索引与查询必须都走 Tokenize()，保证两边切词一致。
/// </summary>
public static class Tokenizer
{
    /// <summary>停用词：高频虚词等无检索价值的词（与 Python 版一致）。</summary>
    private static readonly HashSet<string> StopWords = new()
    {
        "的", "了", "是", "在", "和", "就", "都", "也", "不", "我", "你", "他", "她",
        "它", "我们", "你们", "他们", "她们", "这个", "那个", "什么", "怎么", "为什么",
        "然后", "现在", "可以", "没有", "自己", "这样", "那样", "一个", "一下", "还有",
        "知道", "觉得", "真的", "已经", "因为", "所以", "如果", "但是", "还是", "就是",
        "是不是", "什么", "这样", "那个", "感觉", "有点", "一下", "一会", "起来",
    };

    private static readonly Lazy<JiebaSegmenter> SegmenterLazy = new(() =>
    {
        // jieba.NET 的词典/HMM 模型资源目录（构建时由 Directory.Build.targets 从 NuGet 包复制；
        // 开发环境在 HanserWpf/jieba-resources，发布版在 exe 旁 jieba-resources）
        var resourcesDir = Path.Combine(Paths.WpfRoot, "jieba-resources");
        if (Directory.Exists(resourcesDir))
            ConfigManager.ConfigFileBaseDir = resourcesDir;
        var segmenter = new JiebaSegmenter();
        // 加载 userdict.txt 自定义词典（对应 Python 的 jieba.load_userdict，格式：词 词频 词性）
        if (File.Exists(Paths.UserDictPath))
            segmenter.LoadUserDict(Paths.UserDictPath);
        return segmenter;
    });

    private static JiebaSegmenter Segmenter => SegmenterLazy.Value;

    /// <summary>
    /// 分词并过滤：去空白、单字、停用词、纯符号，返回词列表。
    /// 索引与查询必须都走本函数，保证切分一致（对应 Python tokenize()）。
    /// </summary>
    public static List<string> Tokenize(string text)
    {
        var tokens = new List<string>();
        foreach (var word in Segmenter.Cut(text ?? ""))
        {
            var w = word.Trim();
            if (w.Length == 0)
                continue;
            if (w.Length == 1)
                continue; // 过滤单字
            if (StopWords.Contains(w))
                continue;
            if (!w.Any(char.IsLetterOrDigit))
                continue; // 过滤纯标点/符号
            tokens.Add(w);
        }
        return tokens;
    }
}
