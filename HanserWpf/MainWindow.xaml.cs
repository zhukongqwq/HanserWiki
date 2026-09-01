using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using Hanser.Core;
using Markdig;
using Markdig.Wpf;

namespace HanserWpf;

/// <summary>聊天消息项（User / Assistant / Streaming / Flow / DocList，Content 支持动态更新以配合打字机与流式）。</summary>
public class ChatMessageVm : INotifyPropertyChanged
{
    public string Type { get; }

    private string? _content;
    public string? Content
    {
        get => _content;
        set
        {
            if (_content == value)
                return;
            _content = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Content)));
        }
    }

    public FlowDocument? Document { get; }
    public string? Header { get; }
    public List<DocListItemVm>? Results { get; }

    public event PropertyChangedEventHandler? PropertyChanged;

    private ChatMessageVm(string type, string? content, FlowDocument? document, string? header,
        List<DocListItemVm>? results)
    {
        Type = type;
        _content = content;
        Document = document;
        Header = header;
        Results = results;
    }

    public static ChatMessageVm User(string text) => new("User", text, null, null, null);
    public static ChatMessageVm Assistant(string text, FlowDocument document) => new("Assistant", text, document, null, null);
    public static ChatMessageVm Streaming(string text) => new("Streaming", text, null, null, null);
    public static ChatMessageVm Flow(string text) => new("Flow", text, null, null, null);
    public static ChatMessageVm DocList(string header, List<DocListItemVm> results)
        => new("DocList", null, null, header, results);
}

/// <summary>折叠文档列表中的一行（携带关键词，供点击后段落高亮）。</summary>
public class DocListItemVm
{
    public string Filename { get; }
    public string HitText { get; }
    public string Snippet { get; }
    public List<string> Keywords { get; }

    public DocListItemVm(SearchResult r, List<string> keywords)
    {
        Filename = r.Filename;
        HitText = $"命中 {r.Hits} 次 · {string.Join("、", r.Matched)}";
        Snippet = r.Snippet;
        Keywords = keywords;
    }

    /// <summary>存档加载用：只有文件名（无命中信息与关键词）。</summary>
    public DocListItemVm(string filename)
    {
        Filename = filename;
        HitText = "";
        Snippet = "";
        Keywords = new List<string>();
    }
}

/// <summary>结果列表项（文件名 + 相关度 + 命中词 + 摘要）。</summary>
public class SearchResultVm
{
    public long Id { get; }
    public string Filename { get; }
    public string ScoreText { get; }
    public string HitText { get; }
    public string Snippet { get; }

    public SearchResultVm(SearchResult r)
    {
        Id = r.Id;
        Filename = r.Filename;
        ScoreText = $"相关度 {r.Score:F4}";
        HitText = $"命中 {r.Hits} 次 · {string.Join("、", r.Matched)}";
        Snippet = r.Snippet;
    }
}

public partial class MainWindow : Window
{
    private const int TopN = 20; // 搜索结果候选条数（与 Python 版一致）

    // 趣味流程文案（以打字机小气泡出现在聊天流，后者出现时前者淡出消失）
    private const string StageBunny = "重装小兔正在翻找数据库…";
    private const string StagePrometheus = "普罗米修斯正在校对…";
    private const string StageHanser = "憨憨正在疯狂烧烤…";

    private static readonly MarkdownPipeline MarkdownPipeline =
        new MarkdownPipelineBuilder().UseSupportedExtensions().Build();

    private readonly ObservableCollection<object> _chatItems = new();
    private readonly ObservableCollection<SearchResultVm> _results = new();
    private ChatMessageVm? _flowBubble; // 当前流程状态气泡
    private int _flowToken;            // 打字机令牌：阶段切换时递增以取消旧打字
    private string? _currentChatPath;  // 当前对话存档文件路径（null = 尚未建对话）
    private bool _suppressChatSelection; // 程序化刷新历史列表时抑制选择事件
    private bool _busy;

    public MainWindow()
    {
        InitializeComponent();
        ChatItems.ItemsSource = _chatItems;
        ResultList.ItemsSource = _results;
        Loaded += async (_, _) => await OnLoadedAsync();
    }

    // ---------- 初始化 ----------

    private async Task OnLoadedAsync()
    {
        // 后台预热分词器（jieba 首次加载词典较慢，避免首次搜索卡顿）
        await Task.Run(() => Tokenizer.Tokenize("预热分词器"));
        await RefreshCountAsync();
        LoadChatHistory();
        MainTabs.SelectedIndex = 0; // 默认 AI 问答（聊天）界面
        StartIncrementalScan();
        // 启动自动检查应用版本更新（设置开关开启时）
        if (AppSettings.Load().AutoCheckUpdate)
            _ = CheckAppVersionAsync(silentOnNoUpdate: true);
    }

    /// <summary>检查应用版本更新（从 HanserWiki 拉取 version.json 比对）；有新版本时提示并可跳转 Release。</summary>
    private async Task CheckAppVersionAsync(bool silentOnNoUpdate)
    {
        // 镜像前缀复用更新源配置（若配置了仓库地址）
        var cfg = GitHubSync.LoadConfig();
        var proxy = string.IsNullOrEmpty(cfg.Url) ? "" : GitHubSync.ParseUrl(cfg.Url).Proxy;
        var (hasUpdate, local, remote) = await AppUpdate.CheckAsync(proxy);
        if (hasUpdate)
        {
            AppendGlobalLog($"[版本] 发现新版本 {remote}（当前 {local}）");
            if (AppDialog.Confirm(this, $"发现新版本 v{remote}（当前 v{local}）。\n是否前往 GitHub Releases 下载？", "发现新版本"))
                OpenBrowser(AppUpdate.ReleaseUrl);
        }
        else if (!silentOnNoUpdate)
        {
            if (remote.Length == 0)
            {
                AppendGlobalLog("[版本] 检查更新失败（网络问题或仓库不可达）");
                AppDialog.Info(this, "检查更新失败：无法获取版本信息（网络问题或仓库不可达）。", "检查更新");
            }
            else
            {
                AppendGlobalLog($"[版本] 已是最新版本（{local}）");
                AppDialog.Info(this, $"已是最新版本（v{local}）。", "检查更新");
            }
        }
    }

    /// <summary>用系统浏览器打开链接。</summary>
    private static void OpenBrowser(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch
        {
            // 打开失败静默忽略
        }
    }

    /// <summary>启动后台静默增量扫描：只处理新增/变更文档，完成后刷新计数（无感，不打扰）。</summary>
    private void StartIncrementalScan()
    {
        _ = Task.Run(() =>
        {
            try
            {
                var stats = Indexer.IndexDocuments(log: null);
                Dispatcher.Invoke(() =>
                {
                    AppendGlobalLog($"启动增量扫描完成：新增 {stats.Added}，更新 {stats.Updated}，重分词 {stats.Reindexed}，跳过 {stats.Skipped}，库中总数 {stats.Total}");
                    _ = RefreshCountAsync();
                });
            }
            catch (Exception exc)
            {
                Dispatcher.Invoke(() => AppendGlobalLog($"[错误] 启动增量扫描失败：{exc.Message}"));
            }
        });
    }

    private async Task RefreshCountAsync()
    {
        var count = await Task.Run(() =>
        {
            using var conn = Db.GetConnection();
            Db.InitDb(conn);
            return Db.CountDocuments(conn);
        });
        CountText.Text = $"库文档数：{count}";
        StatusText.Text = "就绪";
    }

    // ---------- 历史对话 ----------

    /// <summary>启动时加载历史对话列表并打开最近的一个对话。</summary>
    private void LoadChatHistory()
    {
        var chats = ChatArchive.ListChats();
        var last = chats.FirstOrDefault();
        if (last != null)
            LoadConversation(last.Path);
        RefreshChatList(last?.Path);
    }

    /// <summary>把存档对话加载进聊天流。</summary>
    private void LoadConversation(string path)
    {
        ResetChatFlow();
        var session = ChatArchive.Load(path);
        _chatItems.Clear();
        foreach (var m in session.Messages)
        {
            if (m.Role == "user")
            {
                _chatItems.Add(ChatMessageVm.User(m.Content));
            }
            else
            {
                _chatItems.Add(ChatMessageVm.Assistant(m.Content, BuildDocument(m.Content)));
                if (m.Docs is { Count: > 0 })
                    _chatItems.Add(ChatMessageVm.DocList(
                        $"📄 相关文档（{m.Docs.Count} 篇，点击展开）",
                        m.Docs.Select(d => new DocListItemVm(d)).ToList()));
            }
        }
        _currentChatPath = path;
        ScrollChatToEnd();
    }

    /// <summary>把当前聊天流保存到存档（首次保存时新建对话，标题取第一个问题）。</summary>
    private void SaveCurrentConversation()
    {
        if (_chatItems.Count == 0)
            return;
        var session = new ChatSessionRecord();
        ChatMessageRecord? currentAssistant = null;
        var firstQuestion = "";
        foreach (var item in _chatItems)
        {
            if (item is not ChatMessageVm m)
                continue;
            switch (m.Type)
            {
                case "User":
                    var um = new ChatMessageRecord { Role = "user", Content = m.Content ?? "" };
                    session.Messages.Add(um);
                    if (firstQuestion.Length == 0)
                        firstQuestion = um.Content;
                    currentAssistant = null;
                    break;
                case "Streaming":
                case "Assistant":
                    currentAssistant = new ChatMessageRecord { Role = "assistant", Content = m.Content ?? "" };
                    session.Messages.Add(currentAssistant);
                    break;
                case "DocList" when currentAssistant != null:
                    currentAssistant.Docs = (m.Results ?? new List<DocListItemVm>())
                        .Select(r => r.Filename).ToList();
                    break;
            }
        }
        var isNew = _currentChatPath == null;
        var title = firstQuestion.Length > 0 ? firstQuestion : "新对话";
        if (isNew)
            _currentChatPath = ChatArchive.CreateNew(title);
        var path = _currentChatPath ?? ChatArchive.CreateNew(title);
        session.Title = ChatArchive.NormalizeTitle(title);
        if (session.Created.Length == 0)
            session.Created = DateTime.Now.ToString("yyyy-MM-ddTHH:mm:ss");
        ChatArchive.Save(path, session);
        RefreshChatList(_currentChatPath);
    }

    /// <summary>刷新历史对话列表（可选选中某对话）。</summary>
    private void RefreshChatList(string? selectPath)
    {
        var chats = ChatArchive.ListChats();
        _suppressChatSelection = true;
        ChatHistoryList.ItemsSource = chats;
        if (selectPath != null)
        {
            var match = chats.FirstOrDefault(c => c.Path == selectPath);
            if (match != null)
                ChatHistoryList.SelectedItem = match;
        }
        else
        {
            ChatHistoryList.SelectedItem = null;
        }
        _suppressChatSelection = false;
    }

    /// <summary>取消进行中的流程气泡打字并清引用（切换/清空对话时调用）。</summary>
    private void ResetChatFlow()
    {
        _flowToken++;
        _flowBubble = null;
    }

    private void NewChatButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;
        ResetChatFlow();
        _currentChatPath = null;
        _chatItems.Clear();
        _suppressChatSelection = true;
        ChatHistoryList.SelectedItem = null;
        _suppressChatSelection = false;
        ChatInputBox.Focus();
    }

    private void ChatHistoryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressChatSelection || _busy)
            return;
        if (ChatHistoryList.SelectedItem is ChatArchive.ChatSummary s)
        {
            LoadConversation(s.Path);
            MainTabs.SelectedIndex = 0; // 切到 AI 问答
        }
    }

    /// <summary>右键时选中被点项（供上下文菜单操作）。</summary>
    private void ChatHistoryList_PreviewMouseRightButtonDown(object sender, MouseButtonEventArgs e)
    {
        var item = ItemsControl.ContainerFromElement(ChatHistoryList, e.OriginalSource as DependencyObject) as ListBoxItem;
        if (item != null)
        {
            item.IsSelected = true;
            ChatHistoryList.SelectedItem = item.DataContext;
        }
    }

    private void RenameChatMenuItem_Click(object sender, RoutedEventArgs e)
    {
        if (ChatHistoryList.SelectedItem is not ChatArchive.ChatSummary s)
            return;
        var newTitle = AppDialog.Prompt(this, "输入新的对话标题：", s.Title, "重命名对话");
        if (newTitle == null)
            return;
        ChatArchive.Rename(s.Path, newTitle);
        RefreshChatList(_currentChatPath);
    }

    private void DeleteChatMenuItem_Click(object sender, RoutedEventArgs e)
    {
        if (ChatHistoryList.SelectedItem is not ChatArchive.ChatSummary s)
            return;
        if (!AppDialog.Confirm(this, $"确定删除对话「{s.Title}」？", "删除对话"))
            return;
        ChatArchive.Delete(s.Path);
        if (_currentChatPath == s.Path)
        {
            ResetChatFlow();
            _currentChatPath = null;
            _chatItems.Clear();
        }
        RefreshChatList(null);
    }

    // ---------- 自绘标题栏 ----------

    /// <summary>标题栏拖拽移动窗口（按钮点击不触发拖拽）。</summary>
    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is System.Windows.Controls.Primitives.ButtonBase)
            return;
        if (e.ButtonState == MouseButtonState.Pressed)
            DragMove();
    }

    private void MinimizeButton_Click(object sender, RoutedEventArgs e)
        => WindowState = WindowState.Minimized;

    private void CloseButton_Click(object sender, RoutedEventArgs e)
        => Close();

    // ---------- 文档检索区收起/展开 ----------

    /// <summary>左列标题行"◀ 收起"按钮。</summary>
    private void CollapseDocButton_Click(object sender, RoutedEventArgs e)
        => SetDocPanelVisible(false);

    /// <summary>左边缘"▶ 展开"按钮。</summary>
    private void ExpandDocButton_Click(object sender, RoutedEventArgs e)
        => SetDocPanelVisible(true);

    /// <summary>切换左侧文档检索区可见性：收起时左列隐藏、左边缘出现展开条，聊天区占满；展开恢复 400px。</summary>
    private void SetDocPanelVisible(bool visible)
    {
        if (visible)
        {
            DocPanel.Visibility = Visibility.Visible;
            LeftCol.Width = new GridLength(400);
            ExpandStripCol.Width = new GridLength(0);
            ExpandStrip.Visibility = Visibility.Collapsed;
        }
        else
        {
            DocPanel.Visibility = Visibility.Collapsed;
            LeftCol.Width = new GridLength(0);
            ExpandStripCol.Width = new GridLength(26);
            ExpandStrip.Visibility = Visibility.Visible;
        }
    }

    // ---------- 搜索（搜索框与聊天框同一水平面，结果进左侧列表） ----------

    private void SearchBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
            SearchButton_Click(sender, e);
    }

    private async void SearchButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;
        var keywords = SearchBox.Text.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (keywords.Length == 0)
            return;

        SetBusy(true, "正在搜索…");
        try
        {
            var results = await Task.Run(() =>
            {
                using var conn = Db.GetConnection();
                Db.InitDb(conn);
                return Search.SearchDocuments(conn, keywords, TopN);
            });

            _results.Clear();
            foreach (var r in results)
                _results.Add(new SearchResultVm(r));
            StatusText.Text = results.Count > 0
                ? $"找到 {results.Count} 个相关文档（按相关度排序）"
                : $"未找到与 {string.Join(" ", keywords)} 相关的文档";
        }
        catch (Exception exc)
        {
            StatusText.Text = "搜索失败";
            AppendGlobalLog($"[错误] 搜索失败：{exc.Message}");
        }
        finally
        {
            SetBusy(false);
        }
    }

    // ---------- 重建索引（自绘确认框 + 进度条） ----------

    private async void RebuildButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;
        if (!AppDialog.Confirm(this,
                "将重新扫描 data 目录并更新数据库索引（修改 userdict.txt 后建议执行）。\n是否继续？",
                "重建索引"))
            return;

        SetBusy(true, "正在重建索引…");
        MainTabs.SelectedIndex = 1; // 切到运行日志
        AppendGlobalLog("== 开始重建索引 ==");
        ProgressArea.Visibility = Visibility.Visible;
        RebuildProgress.Value = 0;
        ProgressText.Text = "正在统计文档…";
        var stopwatch = Stopwatch.StartNew();
        try
        {
            var stats = await Task.Run(() =>
                Indexer.IndexDocuments(
                    log: msg => Dispatcher.Invoke(() => AppendGlobalLog(msg)),
                    progress: (processed, total, file) => Dispatcher.Invoke(() =>
                    {
                        RebuildProgress.Maximum = total;
                        RebuildProgress.Value = processed;
                        // 按已用时间线性外推预计剩余时长
                        var elapsed = stopwatch.Elapsed.TotalSeconds;
                        var remaining = processed > 0
                            ? (int)Math.Round(elapsed / processed * (total - processed))
                            : 0;
                        ProgressText.Text = $"已处理 {processed}/{total} 篇 · 预计剩余 {remaining} 秒（{file}）";
                    })));
            AppendGlobalLog($"== 完成：新增 {stats.Added}，更新 {stats.Updated}，重分词 {stats.Reindexed}，" +
                            $"未变化跳过 {stats.Skipped}，失败 {stats.Failed}，库中总数 {stats.Total} ==");
            await RefreshCountAsync();
        }
        catch (Exception exc)
        {
            StatusText.Text = "重建失败";
            AppendGlobalLog($"[错误] 重建失败：{exc.Message}");
        }
        finally
        {
            ProgressArea.Visibility = Visibility.Collapsed;
            SetBusy(false);
        }
    }

    // ---------- 库统计 ----------

    private async void CountButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;
        SetBusy(true, "正在统计…");
        try
        {
            await RefreshCountAsync();
        }
        finally
        {
            SetBusy(false);
        }
    }

    // ---------- 导入 docx ----------

    private async void ImportButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "导入 docx 文档",
            Filter = "Word 文档 (*.docx)|*.docx",
            Multiselect = true,
        };
        if (dlg.ShowDialog(this) != true)
            return;

        SetBusy(true, "正在导入…");
        MainTabs.SelectedIndex = 1; // 运行日志
        AppendGlobalLog("== 开始导入 docx ==");
        try
        {
            var count = await Task.Run(() =>
            {
                System.IO.Directory.CreateDirectory(Paths.DataDir);
                var n = 0;
                foreach (var file in dlg.FileNames)
                {
                    var dest = System.IO.Path.Combine(Paths.DataDir, System.IO.Path.GetFileName(file));
                    System.IO.File.Copy(file, dest, overwrite: true);
                    n++;
                }
                Indexer.IndexDocuments(log: null); // 增量索引
                return n;
            });
            AppendGlobalLog($"== 导入完成：{count} 个 docx 已复制并索引 ==");
            await RefreshCountAsync();
        }
        catch (Exception exc)
        {
            StatusText.Text = "导入失败";
            AppendGlobalLog($"[错误] 导入失败：{exc.Message}");
        }
        finally
        {
            SetBusy(false);
        }
    }

    // ---------- 检查更新（GitHub 增量拉取） ----------

    private async void UpdateButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;
        var cfg = GitHubSync.LoadConfig();
        if (string.IsNullOrEmpty(cfg.Url))
        {
            AppDialog.Info(this, "尚未配置更新源。请先在「设置 → 数据与更新」中填写仓库地址。", "检查更新");
            return;
        }

        SetBusy(true, "正在检查更新…");
        MainTabs.SelectedIndex = 1; // 运行日志
        AppendGlobalLog($"== 开始检查更新（{cfg.Url}） ==");
        try
        {
            var result = await GitHubSync.RunAsync(cfg, msg => Dispatcher.Invoke(() => AppendGlobalLog(msg)));
            AppendGlobalLog($"== 更新完成：新增 {result.Added}，更新 {result.Updated}，失败 {result.Failed} ==");
            foreach (var err in result.Errors)
                AppendGlobalLog($"  [失败] {err}");
            await RefreshCountAsync();
        }
        catch (Exception exc)
        {
            StatusText.Text = "检查更新失败";
            AppendGlobalLog($"[错误] 检查更新失败：{exc.Message}");
        }
        finally
        {
            SetBusy(false);
        }
    }

    // ---------- 设置（直接打开设置窗口） ----------

    private void SettingsButton_Click(object sender, RoutedEventArgs e)
    {
        var win = new SettingsWindow(0) { Owner = this };
        win.ShowDialog();
        RefreshChatList(_currentChatPath); // 设置中可能清空/重命名了对话，刷新历史列表
        if (win.CheckUpdateRequested)
            _ = CheckAppVersionAsync(silentOnNoUpdate: false); // 关于页触发的版本检查
    }

    // ---------- 聊天：AI 问答（hanser 流式） ----------

    private void ChatInputBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter)
            return;
        if (Keyboard.Modifiers.HasFlag(ModifierKeys.Control))
            return; // Ctrl+Enter：保留默认行为插入换行
        e.Handled = true; // 隧道事件中拦截 Enter，阻止换行并发送
        SendButton_Click(sender, e);
    }

    private async void SendButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;
        var question = ChatInputBox.Text.Trim();
        if (question.Length == 0)
            return;
        ChatInputBox.Clear();

        LLMClient client;
        try
        {
            client = new LLMClient(dryRun: DryRunCheck.IsChecked == true);
        }
        catch (Exception exc)
        {
            AppDialog.Info(this, exc.Message, "AI 配置错误");
            return;
        }

        SetBusy(true, "正在问答…");
        _chatItems.Add(ChatMessageVm.User(question));
        AppendGlobalLog($"== 开始 AI 问答：{question} ==");
        try
        {
            // 阶段 1：重装小兔（Project Bunny 关键词 + 数据库搜索）
            SetFlowStatus(StageBunny);
            AppendGlobalLog($"[1/3] {StageBunny}（Project Bunny 提取关键词 + 数据库搜索）");
            var (keywords, _) = await new ProjectBunny().RunAsync(client, question);
            AppendGlobalLog("  关键词：" + string.Join("、", keywords));

            var results = await Task.Run(() =>
            {
                using var conn = Db.GetConnection();
                Db.InitDb(conn);
                return Search.SearchDocuments(conn, keywords, TopN);
            });
            if (results.Count == 0)
                AppendGlobalLog("  未检索到相关文档。");
            else
                AppendGlobalLog($"  检索到 {results.Count} 条候选：\n" +
                    string.Join("\n", results.Take(5).Select(r => $"    - {r.Filename}（命中 {r.Hits} 次）")));

            // 阶段 2：普罗米修斯（锚定最相关文档）
            SetFlowStatus(StagePrometheus);
            AppendGlobalLog($"[2/3] {StagePrometheus}（Prometheus 锚定最相关文档）");
            var (anchored, _) = await new Prometheus().RunAsync(client, question, keywords, results);
            AppendGlobalLog("  锚定：" + (anchored.Count > 0 ? string.Join("、", anchored) : "无"));

            // 阶段 3：憨憨（流式生成最终回答）
            SetFlowStatus(StageHanser);
            AppendGlobalLog($"[3/3] {StageHanser}（hanser 流式生成最终回答）");
            var answerBubble = ChatMessageVm.Streaming("");
            _chatItems.Add(answerBubble);
            ScrollChatToEnd();

            var sb = new StringBuilder();
            await new Hanser.Core.Hanser().RunStreamingAsync(client, question, anchored, delta =>
            {
                sb.Append(delta);
                answerBubble.Content = sb.ToString();
                ScrollChatToEnd();
            });

            // 完成：流程气泡淡出，回答切换为 Markdown 渲染，文档列表折叠保存
            SetFlowStatus(null);
            var full = sb.ToString();
            _chatItems[_chatItems.IndexOf(answerBubble)] =
                ChatMessageVm.Assistant(full, BuildDocument(full));
            if (results.Count > 0)
                _chatItems.Add(ChatMessageVm.DocList(
                    $"📄 本次检索到 {results.Count} 篇相关文档（点击展开）",
                    results.Select(r => new DocListItemVm(r, keywords)).ToList()));
            AppendGlobalLog("== 回答完成 ==");
            StatusText.Text = "问答完成";
            SaveCurrentConversation(); // 保存到当前对话存档（首次自动新建对话）
            ScrollChatToEnd();
        }
        catch (Exception exc)
        {
            StatusText.Text = "问答失败";
            SetFlowStatus(null);
            AppendGlobalLog($"[错误] AI 问答失败：{exc.Message}");
            _chatItems.Add(ChatMessageVm.Assistant($"（出错：{exc.Message}）",
                BuildDocument($"（出错：{exc.Message}）")));
            SaveCurrentConversation(); // 失败也保存（问题 + 错误信息）
            ScrollChatToEnd();
        }
        finally
        {
            SetBusy(false);
        }
    }

    /// <summary>
    /// 流程状态：以打字机小气泡出现在聊天流（用户问题之后），
    /// 切换时旧气泡淡出消失、新气泡淡入并逐字打出；text 为 null 时仅淡出移除。
    /// </summary>
    private void SetFlowStatus(string? text)
    {
        _flowToken++; // 取消进行中的打字
        FadeOutFlowBubble(() =>
        {
            _flowBubble = null;
            if (text == null)
                return;
            var bubble = ChatMessageVm.Flow("");
            _flowBubble = bubble;
            _chatItems.Add(bubble);
            ScrollChatToEnd();
            // 等容器生成后淡入
            Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () =>
            {
                if (_flowBubble != bubble)
                    return;
                if (ChatItems.ItemContainerGenerator.ContainerFromItem(bubble) is FrameworkElement fc)
                {
                    fc.Opacity = 0.1;
                    fc.BeginAnimation(UIElement.OpacityProperty,
                        new DoubleAnimation(0.1, 1.0, TimeSpan.FromMilliseconds(300)));
                }
            });
            _ = TypewriteAsync(bubble, text);
        });
    }

    /// <summary>打字机效果：逐字更新气泡内容（阶段切换时自动停止）。</summary>
    private async Task TypewriteAsync(ChatMessageVm bubble, string text)
    {
        var token = _flowToken;
        for (var i = 1; i <= text.Length; i++)
        {
            if (token != _flowToken || _flowBubble != bubble)
                return; // 阶段已切换或气泡已移除
            bubble.Content = text[..i];
            ScrollChatToEnd();
            await Task.Delay(60);
        }
        if (token == _flowToken && _flowBubble == bubble)
            bubble.Content = text;
    }

    /// <summary>淡出并移除当前流程气泡，完成后回调。</summary>
    private void FadeOutFlowBubble(Action onDone)
    {
        var bubble = _flowBubble;
        if (bubble == null)
        {
            onDone();
            return;
        }
        var container = ChatItems.ItemContainerGenerator.ContainerFromItem(bubble) as FrameworkElement;
        if (container == null)
        {
            _chatItems.Remove(bubble);
            onDone();
            return;
        }
        var anim = new DoubleAnimation(1.0, 0.0, TimeSpan.FromMilliseconds(250));
        anim.Completed += (_, _) =>
        {
            _chatItems.Remove(bubble);
            onDone();
        };
        container.BeginAnimation(UIElement.OpacityProperty, anim);
    }

    /// <summary>将回答文本渲染为 FlowDocument（Markdig.Wpf）。</summary>
    private static FlowDocument BuildDocument(string text)
    {
        var doc = Markdig.Wpf.Markdown.ToFlowDocument(text ?? "", MarkdownPipeline) ?? new FlowDocument();
        doc.FontFamily = new FontFamily("Microsoft YaHei UI");
        doc.FontSize = 13;
        return doc;
    }

    private void ScrollChatToEnd()
        => Dispatcher.BeginInvoke(DispatcherPriority.Background, () => ChatScroll.ScrollToEnd());

    // ---------- 文档显示（检索选中 / 折叠列表点击跳转 + 段落高亮） ----------

    /// <summary>构建文档 FlowDocument；keywords 非空时，包含任一分词 token 的段落以淡黄背景高亮。</summary>
    private static FlowDocument BuildDocumentWithHighlight(string content, List<string>? keywords)
    {
        var doc = new FlowDocument
        {
            FontFamily = new FontFamily("Microsoft YaHei UI"),
            FontSize = 12,
        };
        var tokens = keywords == null || keywords.Count == 0
            ? new HashSet<string>()
            : new HashSet<string>(Tokenizer.Tokenize(string.Join(" ", keywords)));
        foreach (var raw in (content ?? "").Split('\n'))
        {
            var para = new Paragraph(new Run(raw.Length > 0 ? raw : " "));
            if (tokens.Count > 0 && tokens.Any(t => raw.Contains(t, StringComparison.Ordinal)))
                para.Background = new SolidColorBrush(Color.FromRgb(0xFE, 0xF3, 0xC7)); // 淡黄高亮
            doc.Blocks.Add(para);
        }
        return doc;
    }

    /// <summary>在左侧文档区显示文档；keywords 非空则高亮相关段落并滚动到首个高亮段。</summary>
    private void ShowDocument(string filename, string content, List<string>? keywords)
    {
        DocTitle.Text = filename;
        DocViewer.Document = BuildDocumentWithHighlight(content, keywords);
        if (keywords != null && keywords.Count > 0)
            ScrollToFirstHighlight();
    }

    /// <summary>滚动到首个高亮段落（等布局完成后计算位置）。</summary>
    private void ScrollToFirstHighlight()
    {
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () =>
        {
            var doc = DocViewer.Document;
            if (doc == null)
                return;
            Paragraph? first = null;
            foreach (var block in doc.Blocks)
            {
                if (block is Paragraph p && p.Background != null)
                {
                    first = p;
                    break;
                }
            }
            if (first == null)
                return;
            var sv = FindVisualChild<ScrollViewer>(DocViewer);
            if (sv == null)
                return;
            var rect = first.ContentStart.GetCharacterRect(LogicalDirection.Forward);
            sv.ScrollToVerticalOffset(Math.Max(0, rect.Top - 30));
        });
    }

    private static T? FindVisualChild<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T match)
                return match;
            var found = FindVisualChild<T>(child);
            if (found != null)
                return found;
        }
        return null;
    }

    /// <summary>聊天列表点击：若点击到折叠文档列表项则跳转左侧文档区并高亮相关段落。</summary>
    private void ChatList_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is FrameworkElement fe && fe.DataContext is DocListItemVm item)
            _ = OpenDocAsync(item);
    }

    private async Task OpenDocAsync(DocListItemVm item)
    {
        try
        {
            var content = await Task.Run(() =>
            {
                using var conn = Db.GetConnection();
                return Db.GetDocumentContentByFilename(conn, item.Filename);
            });
            ShowDocument(item.Filename, content, item.Keywords);
            AppendGlobalLog($"[查看] {item.Filename}（高亮 {item.Keywords?.Count ?? 0} 个关键词）");
        }
        catch (Exception exc)
        {
            AppendGlobalLog($"[错误] 打开文档失败：{exc.Message}");
        }
    }

    // ---------- 检索结果选中：左侧显示正文 ----------

    private async void ResultList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ResultList.SelectedItem is not SearchResultVm vm)
            return;
        DocTitle.Text = vm.Filename;
        try
        {
            var content = await Task.Run(() =>
            {
                using var conn = Db.GetConnection();
                return Db.GetDocumentContent(conn, vm.Id);
            });
            DocViewer.Document = BuildDocumentWithHighlight(
                string.IsNullOrEmpty(content) ? "（该文档暂无正文）" : content, null);
        }
        catch (Exception exc)
        {
            DocViewer.Document = BuildDocumentWithHighlight($"（读取失败：{exc.Message}）", null);
        }
    }

    // ---------- 运行日志：打开日志文件夹 ----------

    private void OpenLogFolderButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var logDir = System.IO.Path.Combine(Paths.Root, "HanserWpf", "logs");
            System.IO.Directory.CreateDirectory(logDir);
            Process.Start(new ProcessStartInfo("explorer.exe", logDir) { UseShellExecute = true });
        }
        catch (Exception exc)
        {
            AppendGlobalLog($"[错误] 打开日志文件夹失败：{exc.Message}");
        }
    }

    // ---------- 辅助 ----------

    /// <summary>追加一行到运行日志页与本地日志文件。</summary>
    private void AppendGlobalLog(string line)
    {
        GlobalLogBox.AppendText(line + Environment.NewLine);
        GlobalLogBox.ScrollToEnd();
        LogFile.Append(line);
    }

    /// <summary>设置忙状态：禁用操作按钮（显示禁用态），不改变全局鼠标；状态栏提示进行中的操作。</summary>
    private void SetBusy(bool busy, string? status = null)
    {
        _busy = busy;
        SearchButton.IsEnabled = !busy;
        SendButton.IsEnabled = !busy;
        RebuildButton.IsEnabled = !busy;
        ImportButton.IsEnabled = !busy;
        CountButton.IsEnabled = !busy;
        UpdateButton.IsEnabled = !busy;
        SettingsButton.IsEnabled = !busy;
        if (status != null)
            StatusText.Text = status;
    }
}
