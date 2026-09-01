// Hanser.SmokeTest：无 GUI 验证 Hanser.Core 核心逻辑（分词 / JSON 容错 / 临时库 BM25 / 现有库只读检查）。
using Hanser.Core;
using Microsoft.Data.Sqlite;

var failures = 0;

void Check(string name, bool ok, string detail = "")
{
    Console.WriteLine($"  [{(ok ? "通过" : "失败")}] {name}{(detail.Length > 0 ? "：" + detail : "")}");
    if (!ok) failures++;
}

Console.WriteLine("== 1. 分词（userdict 专有词 / 停用词过滤）==");
var tokens = Tokenizer.Tokenize("今天在B站看了七海和泠鸢的直播，海上油菜花真好玩。");
Console.WriteLine("  分词结果：" + string.Join(" / ", tokens));
Check("含专有词 七海", tokens.Contains("七海"));
Check("含专有词 海上油菜花", tokens.Contains("海上油菜花"));
Check("过滤停用词 的", !tokens.Contains("的"));
Check("过滤单字", !tokens.Contains("今"));

Console.WriteLine("== 2. JSON 容错解析（LLM 输出带 Markdown 代码块）==");
var parsed = LLMClient.ParseJson("```json\n{\"keywords\": [\"直播\", \"2023\"]}\n```");
Check("解析出 keywords 数组", parsed.TryGetProperty("keywords", out var arr) && arr.GetArrayLength() == 2,
    arr.GetArrayLength().ToString());

Console.WriteLine("== 3. dry-run 模拟响应 ==");
var mock = LLMClient.MockChat(new List<ChatMessage>
{
    new() { Role = "system", Content = "你是检索关键词提取助手" },
    new() { Role = "user", Content = "用户问题：xxx" },
});
Check("bunny 模拟返回关键词 JSON", mock.Contains("\"keywords\""));
var mockHanser = LLMClient.MockChat(new List<ChatMessage> { new() { Role = "user", Content = "问题" } });
Check("hanser 模拟返回回答文本", mockHanser.Contains("模拟回答"));

Console.WriteLine("== 4. 临时库：建表 / 写库 / BM25 检索 ==");
var tmpDb = Path.Combine(Path.GetTempPath(), $"hanser_smoke_{Guid.NewGuid():N}.db");
using (var conn = Db.GetConnection(tmpDb))
{
    Db.InitDb(conn);
    Db.UpsertDocument(conn, "甲.docx", "data/甲.docx",
        "今天直播了海上油菜花，观众很多。", 1000.0, 100);
    var idA = GetDocIdByFilename(conn, "甲.docx");
    Db.UpsertDocument(conn, "乙.docx", "data/乙.docx",
        "晚上吃了花生与葱花炒饭，味道不错。", 1000.0, 200);
    var idB = GetDocIdByFilename(conn, "乙.docx");
    Db.ReplaceDocTokens(conn, idA, Tokenizer.Tokenize("今天直播了海上油菜花，观众很多。"));
    Db.ReplaceDocTokens(conn, idB, Tokenizer.Tokenize("晚上吃了花生与葱花炒饭，味道不错。"));

    var results = Search.SearchDocuments(conn, new[] { "直播" });
    Check("BM25 命中 直播 文档", results.Count == 1 && results[0].Filename == "甲.docx",
        results.Count > 0 ? $"{results[0].Filename} score={results[0].Score}" : "无命中");
    Check("摘要截取", results.Count > 0 && results[0].Snippet.Contains("直播"));

    var ctx = Search.ContextSnippets("今天直播了海上油菜花，观众很多。", new[] { "海上油菜花" }, radius: 5);
    Check("上下文片段提取", ctx.Count == 1 && ctx[0].Contains("海上油菜花"), ctx.Count > 0 ? ctx[0] : "无片段");
}
try { File.Delete(tmpDb); } catch { /* 忽略清理失败 */ }

Console.WriteLine("== 5. 现有库只读检查（Python 共享的 documents.db）==");
try
{
    using var conn = Db.GetConnection();
    Db.InitDb(conn);
    var count = Db.CountDocuments(conn);
    Console.WriteLine($"  库中文档数：{count}");
    Check("现有库可读", count > 0, count.ToString());
    var results = Search.SearchDocuments(conn, new[] { "直播" }, 5);
    Check("现有库 BM25 检索", results.Count > 0, results.Count > 0 ? $"首条：{results[0].Filename}（score={results[0].Score}）" : "无命中");
}
catch (Exception exc)
{
    Check("现有库可读", false, exc.Message);
}

Console.WriteLine("== 6. txt/md 文本提取 ==");
var tmpTxt = Path.Combine(Path.GetTempPath(), $"hanser_txt_{Guid.NewGuid():N}.txt");
var tmpMd = Path.Combine(Path.GetTempPath(), $"hanser_md_{Guid.NewGuid():N}.md");
File.WriteAllText(tmpTxt, "今天的直播内容摘要。", System.Text.Encoding.UTF8);
File.WriteAllText(tmpMd, "# 标题\n海上油菜花相关记录。", System.Text.Encoding.UTF8);
try
{
    var txtText = Indexer.ExtractText(tmpTxt);
    Check("txt 提取", txtText.Contains("直播"), txtText);
    var mdText = Indexer.ExtractText(tmpMd);
    Check("md 提取", mdText.Contains("海上油菜花"), mdText);
}
finally
{
    File.Delete(tmpTxt);
    File.Delete(tmpMd);
}

Console.WriteLine();
Console.WriteLine(failures == 0 ? "全部检查通过。" : $"{failures} 项检查失败。");
return failures == 0 ? 0 : 1;

static long GetDocIdByFilename(SqliteConnection conn, string filename)
{
    using var cmd = conn.CreateCommand();
    cmd.CommandText = "SELECT id FROM documents WHERE filename = $name";
    cmd.Parameters.AddWithValue("$name", filename);
    return (long)(cmd.ExecuteScalar() ?? 0L);
}
