using HanserWpf;

namespace Hanser.Tests;

/// <summary>ChatArchive 对话存档测试（公共接口 seam；使用临时目录隔离，不触碰真实 chat-history/）。</summary>
public class ChatArchiveTests : IDisposable
{
    private readonly string _dir;

    public ChatArchiveTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), $"hanser_chat_{Guid.NewGuid():N}");
        ChatArchive.SetChatDirForTest(_dir);
    }

    public void Dispose()
    {
        ChatArchive.SetChatDirForTest(null);
        if (Directory.Exists(_dir))
            Directory.Delete(_dir, recursive: true);
    }

    [Fact]
    public void CreateNew_creates_file_with_first_question_as_title()
    {
        var path = ChatArchive.CreateNew("海上油菜花是什么时候举办的？");

        Assert.True(File.Exists(path));
        var session = ChatArchive.Load(path);
        Assert.Equal("海上油菜花是什么时候举办的？", session.Title);
        Assert.NotEmpty(session.Created);
        Assert.NotEmpty(session.Updated);
    }

    [Theory]
    [InlineData("", "（未命名对话）")]
    [InlineData("   ", "（未命名对话）")]
    public void NormalizeTitle_empty_uses_fallback(string input, string expected)
        => Assert.Equal(expected, ChatArchive.NormalizeTitle(input));

    [Fact]
    public void NormalizeTitle_truncates_long_title()
    {
        var longTitle = new string('汉', 35);
        var result = ChatArchive.NormalizeTitle(longTitle);
        Assert.Equal(31, result.Length); // 30 字 + …
        Assert.EndsWith("…", result);
    }

    [Fact]
    public void NormalizeTitle_strips_newlines()
        => Assert.Equal("第一行 第二行", ChatArchive.NormalizeTitle("第一行\n第二行"));

    [Fact]
    public void Save_Load_roundtrip_preserves_messages_and_docs()
    {
        var path = ChatArchive.CreateNew("测试对话");
        var session = new ChatSessionRecord
        {
            Title = "测试对话",
            Created = "2026-08-31T10:00:00",
            Messages =
            {
                new ChatMessageRecord { Role = "user", Content = "今天直播了吗？" },
                new ChatMessageRecord
                {
                    Role = "assistant",
                    Content = "直播了，还唱了歌。",
                    Docs = { "2024年1月1日 星期一.docx" },
                },
            },
        };

        ChatArchive.Save(path, session);

        var loaded = ChatArchive.Load(path);
        Assert.Equal("测试对话", loaded.Title);
        Assert.Equal(2, loaded.Messages.Count);
        Assert.Equal("user", loaded.Messages[0].Role);
        Assert.Equal("今天直播了吗？", loaded.Messages[0].Content);
        Assert.Equal("assistant", loaded.Messages[1].Role);
        Assert.Single(loaded.Messages[1].Docs);
        Assert.Equal("2024年1月1日 星期一.docx", loaded.Messages[1].Docs[0]);
    }

    [Fact]
    public void ListChats_orders_by_updated_desc()
    {
        var first = ChatArchive.CreateNew("第一个问题");
        Thread.Sleep(1100); // updated 精确到秒，确保两次创建时间不同
        var second = ChatArchive.CreateNew("第二个问题");

        var chats = ChatArchive.ListChats();
        Assert.Equal(2, chats.Count);
        Assert.Equal("第二个问题", chats[0].Title); // 最新在前
        Assert.Equal("第一个问题", chats[1].Title);
        Assert.Equal(second, chats[0].Path);
        Assert.Equal(first, chats[1].Path);
    }

    [Fact]
    public void ListChats_reads_pascal_case_legacy_file()
    {
        // 回归：早期版本以 PascalCase 键保存（Title/Created/Updated/Messages），必须仍能读出标题
        Directory.CreateDirectory(_dir);
        var path = Path.Combine(_dir, "chat-legacy.json");
        File.WriteAllText(path, """
            { "Title": "旧对话", "Created": "2026-08-31T10:00:00", "Updated": "2026-08-31T10:00:00", "Messages": [] }
            """);

        var chats = ChatArchive.ListChats();
        Assert.Single(chats);
        Assert.Equal("旧对话", chats[0].Title);
    }

    [Fact]
    public void Rename_updates_title()
    {
        var path = ChatArchive.CreateNew("原标题");
        ChatArchive.Rename(path, "新标题");

        Assert.Equal("新标题", ChatArchive.Load(path).Title);
        var chats = ChatArchive.ListChats();
        Assert.Equal("新标题", chats[0].Title);
    }

    [Fact]
    public void Delete_removes_file()
    {
        var path = ChatArchive.CreateNew("待删除");
        ChatArchive.Delete(path);

        Assert.False(File.Exists(path));
        Assert.Empty(ChatArchive.ListChats());
    }

    [Fact]
    public void ClearAll_removes_all_chats()
    {
        ChatArchive.CreateNew("对话一");
        ChatArchive.CreateNew("对话二");
        ChatArchive.ClearAll();

        Assert.Empty(ChatArchive.ListChats());
        Assert.Empty(Directory.GetFiles(_dir, "*.json"));
    }
}
